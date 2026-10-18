#!/usr/bin/env python3
"""
Score an accuracy benchmark run: what the app logged against researched truth.

    python3 tools/bench_score.py --prompts P.jsonl --truth T.jsonl --judged J.jsonl \
        --run improved=R1.jsonl --run baseline=R0.jsonl --out scores.json

Inputs (all outside the repo; they hold real food logs):
  prompts  {"id", "text", "group", "kind", "style", "tags", "vague", "items": [{"food", "grams", "ambiguity"}]}
  truth    {"id", "items": [{"idx", "grams", "kcal", "protein_g", "carbs_g", "fat_g", ...}]}
  judged   {"id", "run", "pairs": [{"pred", "truth", "identity": same|close|wrong, "cause"}], "extra": [i or {"pred", "cause"}],
            "missing": [j or {"truth", "cause"}],
            "vague_ok": bool|null}
  run      BenchRunTest output: {"id", "items": [{"name", "origin", "grams", "nutrients"}], "total", "calls", "ms"}

Item detection (precision / recall / F1): a predicted item is a true positive when it
was paired with a truth item and judged the same food (or a close variant, e.g.
"2% milk" for "whole milk", which is counted separately as "close"). Extra items
and wrong-food pairs are false positives; missed truth items and wrong-food pairs
are false negatives.

Amounts: for every correctly identified item, the relative error of its calories,
protein and grams; for every entry, the relative error of its total calories and
protein. Small values use an absolute floor so 0.4 g against 0.3 g is not a 33% miss.
"""
import argparse, collections, json, math, statistics

FLOOR = {"kcal": 10.0, "protein_g": 1.0, "carbs_g": 2.0, "fat_g": 1.0, "grams": 5.0}
KEYS = {"kcal": "calories", "protein_g": "protein", "carbs_g": "carbs", "fat_g": "fat",
        "fiber_g": "fiber", "sugar_g": "sugar", "sodium_mg": "sodium"}
BANDS = [0.05, 0.10, 0.20, 0.30, 0.50]


def load(path):
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def rel(pred, truth, key):
    """Relative error with an absolute floor; None when the truth is unknown."""
    if truth is None:
        return None
    pred = pred or 0.0
    return abs(pred - truth) / max(abs(truth), FLOOR.get(key, 1.0))


def signed(pred, truth, key):
    if truth is None:
        return None
    pred = pred or 0.0
    return (pred - truth) / max(abs(truth), FLOOR.get(key, 1.0))


def within(errors, band):
    xs = [e for e in errors if e is not None]
    return (sum(1 for e in xs if e <= band) / len(xs)) if xs else None


def summary(errors):
    xs = sorted(e for e in errors if e is not None)
    if not xs:
        return None
    return {
        "n": len(xs),
        "median": statistics.median(xs),
        "mean": statistics.fmean(xs),
        "p90": xs[min(len(xs) - 1, int(math.ceil(0.9 * len(xs))) - 1)],
        "within": {f"{int(b * 100)}%": within(xs, b) for b in BANDS},
        "cdf": [[round(x, 4), round((i + 1) / len(xs), 4)] for i, x in enumerate(xs)],
    }


def score_run(name, results, prompts, truth, judged):
    per = []
    tp = fp = fn = close = 0
    item_err = collections.defaultdict(list)
    entry_err = collections.defaultdict(list)
    entry_bias = collections.defaultdict(list)
    causes = collections.Counter()
    for p in prompts:
        pid = p["id"]
        r = results.get(pid)
        t = truth.get(pid)
        j = judged.get((pid, name))
        if r is None or t is None or j is None:
            continue
        preds = r.get("items") or []
        if r.get("meal") and not preds:
            # A saved meal logs as one total; it is judged as one item.
            preds = [{"name": r["meal"], "origin": "PERSONAL", "nutrients": r.get("total", {}), "grams": None}]
        titems = {ti["idx"]: ti for ti in t.get("items", [])}
        row = {"id": pid, "group": p.get("group"), "kind": p.get("kind"), "style": p.get("style"),
               "tags": p.get("tags", []), "vague": p.get("vague", False), "n_truth": len(titems), "n_pred": len(preds)}
        if p.get("vague"):
            ok = bool(j.get("vague_ok"))
            row["vague_ok"] = ok
            row["entry_ok10"] = ok
            row["entry_ok20"] = ok
            per.append(row)
            continue
        c_tp = c_fp = c_fn = 0
        item_rows = []
        for pair in j.get("pairs", []):
            pi, ti = pair.get("pred"), pair.get("truth")
            if pi is None or ti is None or pi >= len(preds) or ti not in titems:
                continue
            ident = pair.get("identity", "wrong")
            pr, tr = preds[pi], titems[ti]
            if pair.get("cause"):
                causes[f"pair:{pair['cause']}"] += 1
            if ident in ("same", "close"):
                c_tp += 1
                if ident == "close":
                    close += 1
                n = pr.get("nutrients") or {}
                e = {k: rel(n.get(KEYS[k]), tr.get(k), k) for k in ("kcal", "protein_g", "carbs_g", "fat_g")}
                e["grams"] = rel(pr.get("grams"), tr.get("grams"), "grams") if pr.get("grams") is not None else None
                for k, v in e.items():
                    item_err[k].append(v)
                amb = next((it.get("ambiguity") for it in [p["items"][ti]] if ti < len(p["items"])), None) if p.get("items") else None
                item_err[f"kcal@{amb}"].append(e["kcal"])
                item_err[f"kcal@{pr.get('origin')}"].append(e["kcal"])
                item_rows.append({"pred": pr.get("name"), "truth": tr.get("food") or p["items"][ti]["food"] if ti < len(p.get("items", [])) else None,
                                  "origin": pr.get("origin"), "kcal_err": e["kcal"], "grams_err": e["grams"],
                                  "kcal_pred": n.get("calories"), "kcal_truth": tr.get("kcal"),
                                  "grams_pred": pr.get("grams"), "grams_truth": tr.get("grams")})
            else:
                c_fp += 1
                c_fn += 1
        c_fp += len(j.get("extra", []))
        c_fn += len(j.get("missing", []))
        for x in j.get("extra", []):
            causes[f"extra:{x.get('cause') if isinstance(x, dict) else None}"] += 1
        for x in j.get("missing", []):
            causes[f"missing:{x.get('cause') if isinstance(x, dict) else None}"] += 1
        tp += c_tp; fp += c_fp; fn += c_fn
        # Entry totals.
        tot = r.get("total") or {}
        t_tot = {k: sum((ti.get(k) or 0.0) for ti in titems.values()) for k in ("kcal", "protein_g", "carbs_g", "fat_g")}
        ek = rel(tot.get("calories"), t_tot["kcal"], "kcal")
        ep = rel(tot.get("protein"), t_tot["protein_g"], "protein_g")
        entry_err["kcal"].append(ek)
        entry_err["protein_g"].append(ep)
        entry_err["carbs_g"].append(rel(tot.get("carbs"), t_tot["carbs_g"], "carbs_g"))
        entry_err["fat_g"].append(rel(tot.get("fat"), t_tot["fat_g"], "fat_g"))
        entry_bias["kcal"].append(signed(tot.get("calories"), t_tot["kcal"], "kcal"))
        all_found = c_fp == 0 and c_fn == 0
        row.update({"tp": c_tp, "fp": c_fp, "fn": c_fn, "kcal_err": ek, "protein_err": ep,
                    "kcal_pred": tot.get("calories"), "kcal_truth": t_tot["kcal"],
                    "entry_ok10": all_found and ek is not None and ek <= 0.10 and (ep is None or ep <= 0.10),
                    "entry_ok20": all_found and ek is not None and ek <= 0.20 and (ep is None or ep <= 0.20),
                    "items": item_rows, "ms": r.get("ms"),
                    "cost": cost(r.get("calls", []), r.get("model"))})
        per.append(row)

    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = 2 * precision * recall / (precision + recall) if precision and recall else None
    scored = [x for x in per if "entry_ok10" in x]

    def acc(rows, key):
        return sum(1 for x in rows if x.get(key)) / len(rows) if rows else None

    breakdown = {}
    for dim in ("group", "kind", "style"):
        groups = collections.defaultdict(list)
        for x in scored:
            groups[x.get(dim)].append(x)
        breakdown[dim] = {k: {"n": len(v), "acc10": acc(v, "entry_ok10"), "acc20": acc(v, "entry_ok20"),
                              "kcal_median": statistics.median([e for e in (y.get("kcal_err") for y in v) if e is not None] or [0])}
                          for k, v in groups.items()}
    tags = collections.defaultdict(list)
    for x in scored:
        for tg in x.get("tags", []):
            tags[tg].append(x)
    breakdown["tag"] = {k: {"n": len(v), "acc10": acc(v, "entry_ok10"), "acc20": acc(v, "entry_ok20")} for k, v in tags.items()}
    costs = [x["cost"] for x in scored if x.get("cost") is not None]
    ms = [x["ms"] for x in scored if x.get("ms") is not None]
    return {
        "run": name, "n": len(scored),
        "items": {"tp": tp, "fp": fp, "fn": fn, "close": close, "precision": precision, "recall": recall, "f1": f1},
        "entry_acc10": acc(scored, "entry_ok10"), "entry_acc20": acc(scored, "entry_ok20"),
        "entry": {k: summary(v) for k, v in entry_err.items()},
        "entry_bias_kcal_median": statistics.median([b for b in entry_bias["kcal"] if b is not None] or [0]),
        "item": {k: summary(v) for k, v in item_err.items()},
        "breakdown": breakdown,
        "cost_total": sum(costs), "cost_per_entry": (sum(costs) / len(costs)) if costs else None,
        "latency_median_ms": statistics.median(ms) if ms else None,
        "latency_p90_ms": sorted(ms)[int(0.9 * (len(ms) - 1))] if ms else None,
        "causes": dict(causes.most_common()),
        "per": per,
    }


RATES = {"gpt-6-luna": (0.10, 0.01, 0.50), "gpt-5.6-luna": (0.20, 0.02, 1.20)}


def cost(calls, model):
    r = RATES.get(model or "", (0.10, 0.01, 0.50))
    total = 0.0
    for c in calls:
        uncached = max(0, c.get("in", 0) - c.get("cached", 0))
        total += (uncached * r[0] + c.get("cached", 0) * r[1] + c.get("out", 0) * r[2]) / 1e6
    return total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--truth", required=True)
    ap.add_argument("--judged", required=True)
    ap.add_argument("--run", action="append", required=True, help="name=results.jsonl")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    prompts = load(a.prompts)
    truth = {t["id"]: t for t in load(a.truth)}
    judged = {(j["id"], j["run"]): j for j in load(a.judged)}
    out = {}
    for spec in a.run:
        name, path = spec.split("=", 1)
        results = {r["id"]: r for r in load(path)}
        out[name] = score_run(name, results, prompts, truth, judged)
        s = out[name]
        print(f"{name}: n={s['n']} P={s['items']['precision']:.3f} R={s['items']['recall']:.3f} F1={s['items']['f1']:.3f} "
              f"entry acc@10%={s['entry_acc10']:.3f} @20%={s['entry_acc20']:.3f} "
              f"kcal median err={s['entry']['kcal']['median']:.3f} cost=${s['cost_total']:.4f}")
    with open(a.out, "w") as f:
        json.dump(out, f)


if __name__ == "__main__":
    main()
