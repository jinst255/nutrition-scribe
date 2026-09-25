#!/usr/bin/env python3
"""Find products in the branded label database. Tool for writing truth cases.

    python3 tools/find_label.py [--db branded.db] word [word ...]

Every word must appear in the brand or the description. Prints the label's own
serving and the per-serving numbers, most recently updated first.
"""
import argparse, os, sqlite3

ap = argparse.ArgumentParser()
ap.add_argument("words", nargs="+")
ap.add_argument("--db", default=os.path.expanduser("~/.cache/fdc-bench/branded_full.db"))
ap.add_argument("-n", type=int, default=12)
a = ap.parse_args()
db = sqlite3.connect(a.db)
where = " AND ".join(["(UPPER(description)||' '||UPPER(brand_name)||' '||UPPER(brand_owner)) LIKE ?"] * len(a.words))
rows = db.execute(
    f"SELECT fdc_id, brand_name, description, serving_size, serving_unit, household, "
    f"kcal, protein_g, carbs_g, fat_g, sugar_g, sodium_mg FROM latest WHERE {where} "
    f"ORDER BY modified DESC LIMIT ?", [f"%{w.upper()}%" for w in a.words] + [a.n]).fetchall()
for r in rows:
    s = (r[3] or 0) / 100
    f = lambda v: "-" if v is None else f"{v * s:.0f}"
    print(f"{r[0]} | {r[1][:14]:14s} | {r[2][:60]:60s} | {r[3]}{r[4]} ({r[5]}) | "
          f"kcal {f(r[6])} P {f(r[7])} C {f(r[8])} F {f(r[9])} sug {f(r[10])} Na {f(r[11])}")
