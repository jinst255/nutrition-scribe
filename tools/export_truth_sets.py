#!/usr/bin/env python3
"""
Write the three answer-key sets (truth_set, truth_holdout, truth_labels) as one JSON
file the Kotlin resolver tests can read: tools/.cache/truth_sets.json.

    python3 tools/export_truth_sets.py

The truths are computed here, from food.db and the branded label database, exactly
as tools/bench_truth.py computes them, so the Kotlin and Python numbers are scored
against the same answers.
"""
import json, os, sqlite3, sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here)
import truth_set, truth_holdout, truth_labels, truth_holdout2
from bench_truth import FLOOR

db = sqlite3.connect(os.path.join(here, "..", "app", "src", "main", "assets", "food.db"))
out = {"floor": FLOOR, "sets": {}}
for name, mod in (("tuning", truth_set), ("holdout", truth_holdout), ("labels", truth_labels),
                  ("holdout2", truth_holdout2)):
    cases = []
    for c in mod.CASES:
        t = truth_set.truth_of(db, c)
        cases.append({"text": c["text"], "cat": c["cat"], "tol": c.get("tol", 0.15),
                      "ambiguous": c.get("ambiguous", False),
                      "truth": None if t is None else {k: v for k, v in t.items() if v is not None}})
    out["sets"][name] = cases
path = os.path.join(here, ".cache", "truth_sets.json")
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(out, open(path, "w"), indent=1)
print({k: len(v) for k, v in out["sets"].items()}, "->", path)
