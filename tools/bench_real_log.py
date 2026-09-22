#!/usr/bin/env python3
"""
Score the offline lookup against a REAL food log.

WHY THIS EXISTS
---------------
tools/bench_food_db.py measures the search on phrases I wrote, which means it
measures what I imagined people would type. An exported log measures what someone
actually said to the app — and the two turned out to look nothing alike. Real
entries are dictated, rambling, full of typos and unicode fractions, and they lean
hard on brands and stated amounts:

    "this morning I had some protein pancakes I estimate that it was probably
     about 30 g of protein in those pancakes and then there was also a little
     bit of milk and an egg and then I also had some raspberry jam"

Every entry in an export already carries the AI's answer, so an export is a free
labelled dataset: no key, no tokens, no network. The AI is not ground truth — it
estimates too — but it is the bar the offline path has to clear, because it is
what the user is choosing between.

Usage:
    python3 tools/bench_real_log.py app/src/main/assets/food.db export.json
    python3 tools/bench_real_log.py ... --verbose     # every case, not just misses

An export is personal data. It is gitignored, and this script only reads it.
"""
import argparse, json, os, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import foodsearch as F

# Within this much of the AI's number counts as agreement. 40% is loose on
# purpose: both sides are estimating a portion nobody weighed, so anything
# tighter would measure noise. What matters is catching the 3x errors.
TOLERANCE = 0.40


def close(a, b, tol=TOLERANCE):
    if a is None or b is None:
        return False
    if b == 0:
        return a == 0
    return abs(a - b) / abs(b) <= tol


def verdict(off, ai_p, ai_c):
    """One of: ok, protein_only, calories_only, off, vague, no_match."""
    if off is None:
        return "no_match"
    if off.get("vague"):
        return "vague"
    p, c = close(off["protein_g"], ai_p), close(off["kcal"], ai_c)
    if p and c:
        return "ok"
    if p:
        return "protein_only"
    if c:
        return "calories_only"
    return "off"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("export")
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("--only", help="substring filter on the raw text")
    a = ap.parse_args()

    entries = json.load(open(a.export))
    db = sqlite3.connect(a.db)

    counts, rows = {}, []
    for e in entries:
        raw = (e.get("rawText") or "").strip()
        if not raw or (a.only and a.only.lower() not in raw.lower()):
            continue
        ai_p, ai_c = e.get("proteinGrams"), e.get("calories")
        off = F.offline_lookup(db, raw)
        v = verdict(off, ai_p, ai_c)
        counts[v] = counts.get(v, 0) + 1
        rows.append((v, raw, ai_p, ai_c, off))

    total = len(rows)
    print(f"{total} real entries\n")
    for v in ("ok", "protein_only", "calories_only", "off", "vague", "no_match"):
        n = counts.get(v, 0)
        if n:
            print(f"  {v:<14} {n:>3}  {n / total:>5.1%}")
    agree = counts.get("ok", 0)
    print(f"\n  both within {TOLERANCE:.0%}: {agree}/{total} = {agree / total:.1%}")

    print("\n" + "=" * 78)
    for v, raw, ai_p, ai_c, off in rows:
        if not a.verbose and v == "ok":
            continue
        short = raw.replace("\n", " ")
        print(f"\n[{v}] {short[:100]!r}")
        print(f"    AI       P{fmt(ai_p)}  C{fmt(ai_c)}")
        if off is None:
            print("    OFFLINE  (no match)")
        else:
            print(f"    OFFLINE  P{fmt(off['protein_g'])}  C{fmt(off['kcal'])}"
                  f"   {'[needs detail] ' if off['needs_detail'] else ''}"
                  f"{off['matches']}")


def fmt(v):
    return f"{v:>7.1f}" if isinstance(v, (int, float)) else f"{'-':>7}"


if __name__ == "__main__":
    main()
