#!/usr/bin/env python3
"""
Score a lookup method against cases with a known answer (tools/truth_set.py).

    python3 tools/bench_truth.py app/src/main/assets/food.db offline
    OPENAI_API_KEY=... python3 tools/bench_truth.py app/src/main/assets/food.db ai_v1 ai_v2

A case PASSES when calories and protein both land within the case's tolerance
(or within a small absolute amount, so 0.4 g of protein against 0.3 g is not a
33% miss). Carbs and fat are reported, not gated: they are what a friend
tracking something other than protein will look at, so they have to be seen,
but a label's carb figure is the least stable number on it.

A vague case passes only when the method asks for detail instead of inventing
a number.

AI answers are cached in tools/.cache/ (gitignored) keyed by method, model and
text, so a rerun after changing only the scorer costs nothing. --fresh ignores
the cache.
"""
import argparse, json, os, sqlite3, statistics, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import foodsearch as F
import truth_set as T

GATED = ["kcal", "protein_g"]
REPORTED = ["carbs_g", "fat_g", "sugar_g", "sodium_mg"]
# Below these, a relative error is noise.
FLOOR = {"kcal": 20, "protein_g": 2, "carbs_g": 3, "fat_g": 2, "sugar_g": 2,
         "sodium_mg": 40, "caffeine_mg": 10}

CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")


def within(pred, truth, key, tol):
    if pred is None:
        return False
    return abs(pred - truth) <= max(tol * abs(truth), FLOOR.get(key, 0))


def method_offline(db, text):
    out = F.offline_lookup(db, text)
    if out is None:
        return {"needs_detail": True}
    return out


def load_methods():
    methods = {"offline": method_offline}
    try:
        import offline_labels
        methods["offline_labels"] = offline_labels.method
    except ImportError:
        pass
    try:
        import ai_lookup
        methods.update(ai_lookup.METHODS)
    except ImportError:
        pass
    return methods


def cached(method, text, fn, fresh):
    os.makedirs(CACHE_DIR, exist_ok=True)
    path = os.path.join(CACHE_DIR, f"{method}.json")
    # Several runs share one file, so read it fresh each time rather than once.
    try:
        store = json.load(open(path))
    except (OSError, ValueError):
        store = {}
    if not fresh and text in store:
        return store[text]
    out = fn()
    if "error" not in out:
        store[text] = out
        json.dump(store, open(path, "w"), indent=1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("methods", nargs="+")
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--only", help="substring filter on the case text")
    ap.add_argument("--cat", help="only this category")
    ap.add_argument("--holdout", action="store_true",
                    help="score the held-out cases (truth_holdout.py) instead")
    ap.add_argument("--holdout2", action="store_true",
                    help="score the second, blind held-out set (truth_holdout2.py)")
    ap.add_argument("--labels", action="store_true",
                    help="score the real-package-label cases (truth_labels.py) instead")
    a = ap.parse_args()
    ap_sets = []
    if a.holdout:
        import truth_holdout
        ap_sets = truth_holdout.CASES
    if a.holdout2:
        import truth_holdout2
        ap_sets = truth_holdout2.CASES
    if a.labels:
        import truth_labels
        ap_sets = ap_sets + truth_labels.CASES
    if ap_sets:
        T.CASES = ap_sets

    db = sqlite3.connect(a.db)
    methods = load_methods()
    cases = [c for c in T.CASES
             if (not a.only or a.only.lower() in c["text"].lower())
             and (not a.cat or c["cat"] == a.cat)]

    summary = {}
    for name in a.methods:
        fn = methods.get(name)
        if fn is None:
            sys.exit(f"unknown method {name!r}; have {sorted(methods)}")
        rows = []
        started = time.time()
        for c in cases:
            if name.startswith("offline"):
                pred = fn(db, c["text"])
            else:
                pred = cached(name, c["text"], lambda: fn(db, c["text"]), a.fresh)
            rows.append((c, pred))
        summary[name] = report(name, rows, db, a.verbose, time.time() - started)

    if len(summary) > 1:
        print("\n" + "=" * 72)
        print(f"{'method':12s} {'pass':>9s} {'kcal MdAPE':>11s} {'prot MdAPE':>11s} "
              f"{'Na MdAPE':>9s}")
        for name, s in summary.items():
            print(f"{name:12s} {s['pass']:>9s} {s['kcal_mdape']:>10.1f}% "
                  f"{s['prot_mdape']:>10.1f}% {s.get('na_mdape', 0):>8.1f}%")


def ape(pred, truth, key):
    if pred is None:
        return None
    if abs(pred - truth) <= FLOOR.get(key, 0):
        return 0.0
    return abs(pred - truth) / max(abs(truth), FLOOR.get(key, 1)) * 100


def report(name, rows, db, verbose, seconds):
    by_cat = {}
    kcal_err, prot_err, other_err = [], [], {k: [] for k in REPORTED}
    fails = []
    for c, pred in rows:
        truth = T.truth_of(db, c)
        cat = c["cat"]
        by_cat.setdefault(cat, [0, 0])
        by_cat[cat][1] += 1
        if "error" in pred:
            fails.append((c, pred, truth, "error"))
            continue
        if truth is None:
            ok = bool(pred.get("needs_detail"))
            by_cat[cat][0] += ok
            if not ok:
                fails.append((c, pred, truth, "invented"))
            continue

        tol = c.get("tol", 0.15)
        ok = all(within(pred.get(k), truth[k], k, tol) for k in GATED if truth.get(k) is not None)
        by_cat[cat][0] += ok
        e = ape(pred.get("kcal"), truth["kcal"], "kcal")
        kcal_err.append(e if e is not None else 100.0)
        e = ape(pred.get("protein_g"), truth["protein_g"], "protein_g")
        prot_err.append(e if e is not None else 100.0)
        for k in REPORTED:
            if truth.get(k) is not None and pred.get(k) is not None:
                other_err[k].append(ape(pred[k], truth[k], k))
        if not ok or verbose:
            fails.append((c, pred, truth, "ok" if ok else
                          ("flagged" if pred.get("needs_detail") else "WRONG")))

    print(f"\n### {name}  ({seconds:.0f}s)")
    for c, pred, truth, why in fails:
        if why == "error":
            print(f"  [error] {c['text']!r}: {pred['error'][:120]}")
            continue
        if truth is None:
            print(f"  [{why}] {c['text']!r} -> kcal {fmt(pred.get('kcal'))}")
            continue
        print(f"  [{why}] {c['text']!r}")
        print(f"      truth kcal {fmt(truth['kcal'])}  P {fmt(truth['protein_g'])}  "
              f"C {fmt(truth.get('carbs_g'))}  F {fmt(truth.get('fat_g'))}")
        print(f"      got   kcal {fmt(pred.get('kcal'))}  P {fmt(pred.get('protein_g'))}  "
              f"C {fmt(pred.get('carbs_g'))}  F {fmt(pred.get('fat_g'))}  {explain(pred)}")

    total = sum(v[0] for v in by_cat.values()), sum(v[1] for v in by_cat.values())
    s = {"pass": f"{total[0]}/{total[1]}"}
    for cat in ("usda", "label", "vague", "label_db"):
        v = by_cat.get(cat, [0, 0])
        s[cat] = f"{v[0]}/{v[1]}"
    s["kcal_mdape"] = statistics.median(kcal_err) if kcal_err else 0
    s["prot_mdape"] = statistics.median(prot_err) if prot_err else 0
    within_15 = sum(1 for e in kcal_err if e <= 15) / len(kcal_err) * 100 if kcal_err else 0
    print(f"  PASS {s['pass']} = {total[0] / total[1] * 100:.1f}%   "
          f"usda {s['usda']}  label {s['label']}  vague {s['vague']}")
    print(f"  median error: kcal {s['kcal_mdape']:.1f}%  protein {s['prot_mdape']:.1f}%   "
          f"kcal within 15%: {within_15:.0f}%")
    s["na_mdape"] = statistics.median(other_err["sodium_mg"]) if other_err["sodium_mg"] else 0
    for k, v in other_err.items():
        s[k + "_bad"] = sum(1 for e in v if e > 20), len(v)
    print("  >20% off, reported only: " + "  ".join(
        f"{k} {s[k + '_bad'][0]}/{s[k + '_bad'][1]}" for k in other_err if other_err[k]))
    print("  median error, reported only: " + "  ".join(
        f"{k} {statistics.median(v):.0f}%" for k, v in other_err.items() if v))
    return s


def explain(pred):
    bits = []
    if pred.get("needs_detail"):
        bits.append("[asked for detail]")
    if pred.get("matches"):
        bits.append(str(pred["matches"])[:110])
    if pred.get("items"):
        bits.append("; ".join(f"{i.get('name')} {i.get('grams', '?')}g"
                              for i in pred["items"])[:140])
    return " ".join(bits)


def fmt(v):
    return f"{v:7.1f}" if isinstance(v, (int, float)) else f"{'-':>7}"


if __name__ == "__main__":
    main()
