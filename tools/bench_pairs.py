#!/usr/bin/env python3
"""
Propose which logged item answers which truth item, for the benchmark's graders.

    python3 tools/bench_pairs.py --prompts P.jsonl --truth T.jsonl --run R.jsonl --out J.jsonl [--name improved]

For each case it pairs predicted and truth items greedily by shared food words
(with a few synonyms), breaking ties by how close the calories are. The output is
in bench_score.py's "judged" format with identity "same" for every pair, plus a
"review" field listing what a grader should look at: low word overlap, a big
calorie gap, extra or missing items. Graders correct it by hand; the script only
saves them from pairing 700 items from scratch.

With --prior-run and --prior-judged, a case whose logged items are unchanged since
an already-judged run keeps that judgement (same foods, calories and grams within 3%); only the
changed cases need a grader.
"""
import argparse, json, re

STOP = set("a an the of with and or in on my some little bit about like probably maybe just one two three half "
           "cup cups tbsp tsp oz ounce ounces g gram grams large small medium piece pieces slice slices serving servings "
           "had ate got then also plus from for".split())
SYN = {"toast": "bread", "oatmeal": "oat", "oats": "oat", "yoghurt": "yogurt", "fries": "fry", "soda": "cola",
       "coke": "cola", "pop": "cola", "chips": "chip", "eggs": "egg", "potatoes": "potato", "tomatoes": "tomato"}


def words(s):
    out = set()
    for w in re.findall(r"[a-z]+", (s or "").lower()):
        if w in STOP or len(w) < 3:
            continue
        w = SYN.get(w, w)
        if w.endswith("ies"):
            w = w[:-3] + "y"
        elif w.endswith("s") and not w.endswith("ss"):
            w = w[:-1]
        out.add(w)
    return out


def load(path):
    return [json.loads(l) for l in open(path) if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--truth", required=True)
    ap.add_argument("--run", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", default="improved")
    ap.add_argument("--prior-run", help="an earlier run of the same prompts")
    ap.add_argument("--prior-judged", help="the graders' judgements of --prior-run")
    a = ap.parse_args()
    prior = {r["id"]: r for r in load(a.prior_run)} if a.prior_run else {}
    prior_j = {j["id"]: j for j in load(a.prior_judged)} if a.prior_judged else {}

    def close(a, b):
        return abs(a - b) <= max(3.0, 0.03 * max(abs(a), abs(b)))

    def same_items(r0, r1):
        """The same foods in the same order, at calories and grams within 3%: the model's
        wording ("Saltwater taffy" / "saltwater taffy") may change between runs."""
        x0, x1 = r0.get("items") or [], r1.get("items") or []
        return len(x0) == len(x1) and all(
            words(a.get("name")) == words(b.get("name"))
            and close(a.get("grams") or 0, b.get("grams") or 0)
            and close((a.get("nutrients") or {}).get("calories") or 0, (b.get("nutrients") or {}).get("calories") or 0)
            for a, b in zip(x0, x1))
    prompts = {p["id"]: p for p in load(a.prompts)}
    truth = {t["id"]: t for t in load(a.truth)}
    out = []
    for r in load(a.run):
        pid = r["id"]
        p, t = prompts.get(pid), truth.get(pid)
        if p is None or t is None:
            continue
        if pid in prior and pid in prior_j and same_items(prior[pid], r):
            out.append(dict(prior_j[pid], run=a.name, carried=True))
            continue
        preds = r.get("items") or []
        if r.get("meal") and not preds:
            preds = [{"name": r["meal"], "nutrients": r.get("total", {})}]
        if p.get("vague"):
            out.append({"id": pid, "run": a.name, "pairs": [], "extra": [], "missing": [],
                        "vague_ok": all(not (x.get("nutrients") or {}).get("calories") for x in preds),
                        "review": ["vague case: check the app asked rather than invented"]})
            continue
        titems = t.get("items", [])
        cand = []
        for i, pr in enumerate(preds):
            pw = words(pr.get("name")) | words(pr.get("said"))
            for ti in titems:
                tw = words(ti.get("food")) | words(p["items"][ti["idx"]].get("said") if ti["idx"] < len(p["items"]) else "")
                overlap = len(pw & tw) / max(1, min(len(pw), len(tw)))
                kp = (pr.get("nutrients") or {}).get("calories") or 0.0
                kt = ti.get("kcal") or 0.0
                gap = abs(kp - kt) / max(kt, 20.0)
                cand.append((overlap, -gap, i, ti["idx"]))
        cand.sort(reverse=True)
        used_p, used_t, pairs, review = set(), set(), [], []
        for overlap, neg_gap, i, j in cand:
            if overlap <= 0 or i in used_p or j in used_t:
                continue
            used_p.add(i); used_t.add(j)
            pairs.append({"pred": i, "truth": j, "identity": "same", "overlap": round(overlap, 2), "kcal_gap": round(-neg_gap, 2)})
            if overlap < 0.5 or -neg_gap > 0.5:
                review.append(f"pair p{i}->t{j} overlap {overlap:.2f} kcal gap {-neg_gap:.2f}")
        extra = [i for i in range(len(preds)) if i not in used_p]
        missing = [ti["idx"] for ti in titems if ti["idx"] not in used_t]
        if extra:
            review.append(f"extra predicted {extra}")
        if missing:
            review.append(f"missing truth {missing}")
        if prior:
            review.insert(0, "changed since the prior run")
        out.append({"id": pid, "run": a.name, "pairs": pairs, "extra": extra, "missing": missing, "vague_ok": None, "review": review})
    with open(a.out, "w") as f:
        for o in out:
            f.write(json.dumps(o) + "\n")
    carried = sum(1 for o in out if o.get("carried"))
    print(f"{len(out)} cases, {carried} carried over, {sum(1 for o in out if o.get('review') and not o.get('carried'))} to review")


if __name__ == "__main__":
    main()
