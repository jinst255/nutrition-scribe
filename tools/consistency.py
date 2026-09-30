#!/usr/bin/env python3
"""
Send every real log entry through a method TWICE and measure how often the two
answers disagree. A food log that gives 500 kcal on Monday and 600 on Tuesday
for the same words feels "20% off one way or another" even when both answers
are defensible.

    OPENAI_API_KEY=... python3 tools/consistency.py food.db export.json ai_pick
"""
import json, os, statistics, sys, sqlite3
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_lookup as A

db = sqlite3.connect(sys.argv[1])
texts = [e["rawText"].strip() for e in json.load(open(sys.argv[2])) if (e.get("rawText") or "").strip()]
texts = list(dict.fromkeys(texts))
fn = A.METHODS[sys.argv[3]]
diffs, flagged, errors, secs, big = [], 0, 0, [], []
out = []
for t in texts:
    a, b = fn(db, t), fn(db, t)
    out.append({"text": t, "a": a, "b": b})
    if "error" in a or "error" in b:
        errors += 1
        continue
    secs += [a.get("seconds", 0), b.get("seconds", 0)]
    flagged += bool(a.get("needs_detail")) + bool(b.get("needs_detail"))
    x, y = a.get("kcal"), b.get("kcal")
    if x and y:
        d = abs(x - y) / max(x, y)
        diffs.append(d)
        if d > 0.15:
            big.append((t[:60], round(x), round(y)))
n = len(texts)
print(f"{sys.argv[3]}: {n} entries x2")
print(f"  errors {errors}   flagged needs-detail {flagged}/{2 * n} ({flagged / (2 * n) * 100:.0f}%)")
print(f"  kcal differs >15% between identical requests: {sum(d > 0.15 for d in diffs)}/{len(diffs)}")
secs.sort()
print(f"  latency p50 {secs[len(secs) // 2]:.1f}s p90 {secs[int(len(secs) * .9)]:.1f}s")
for b_ in big:
    print("   ", b_)
json.dump(out, open(os.path.join(os.path.dirname(__file__), ".cache", f"consistency_{sys.argv[3]}.json"), "w"), indent=1)
