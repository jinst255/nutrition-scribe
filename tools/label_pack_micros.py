#!/usr/bin/env python3
"""
Add the label micronutrients to a built label pack (format 3 -> 4).

    python3 tools/label_pack_micros.py labels.db [--csv <branded food_nutrient.csv>]

A US label only has to print a handful of micronutrients: calcium, iron,
potassium and vitamin D (since 2020), plus cholesterol. Those five are what this
adds, measured over the pack: roughly 60-90% of products carry each, while
everything else (magnesium, zinc, B12...) is on under 6% and is left out rather
than shipped as mostly-empty columns.

This runs as a second step, not inside build_label_pack.py, because the branded
source database was built without micronutrients and rebuilding it means reading
the 1.5 GB food_nutrient.csv anyway. Here it is read once, only for the products
already in the pack.

Values are stored like the macros: per ONE serving, as integers x10.
Vitamin D is stored in µg; a label that gave only IU is converted (40 IU = 1 µg).
"""
import argparse, csv, os, sqlite3

DEFAULT_CSV = os.path.expanduser(
    "~/.cache/fdc-bench/FoodData_Central_branded_food_csv_2026-04-30/food_nutrient.csv")

IDS = {1087: "calcium_mg", 1089: "iron_mg", 1092: "potassium_mg", 1114: "vitamin_d_ug",
       1253: "cholesterol_mg", 1110: "vit_d_iu"}
COLUMNS = ["calcium_mg", "iron_mg", "potassium_mg", "vitamin_d_ug", "cholesterol_mg"]


def main(pack, src):
    db = sqlite3.connect(pack)
    fmt = dict(db.execute("SELECT key, value FROM meta")).get("format")
    if fmt != "3":
        raise SystemExit(f"expected a format 3 pack, found format {fmt!r}")
    serving = {fid: sg for fid, sg in db.execute("SELECT id, serving_g FROM product")}
    print(f"{len(serving):,} products; reading {src}")

    per100 = {}
    with open(src, newline="", encoding="utf-8", errors="replace") as f:
        for r in csv.DictReader(f):
            try:
                fid = int(r["fdc_id"])
            except (ValueError, KeyError):
                continue
            if fid not in serving:
                continue
            nid = r.get("nutrient_id", "")
            col = IDS.get(int(nid)) if nid.isdigit() else None
            if not col:
                continue
            try:
                per100.setdefault(fid, {})[col] = float(r["amount"])
            except (ValueError, TypeError):
                pass

    for c in COLUMNS:
        db.execute(f"ALTER TABLE product ADD COLUMN {c} INTEGER")
    rows = []
    for fid, v in per100.items():
        if "vitamin_d_ug" not in v and "vit_d_iu" in v:
            v["vitamin_d_ug"] = v["vit_d_iu"] / 40.0
        sg = serving[fid] or 0
        vals = [None if v.get(c) is None else round(v[c] * sg / 100.0 * 10) for c in COLUMNS]
        rows.append((*vals, fid))
    db.executemany(f"UPDATE product SET {', '.join(c + ' = ?' for c in COLUMNS)} WHERE id = ?", rows)
    db.execute("UPDATE meta SET value = '4' WHERE key = 'format'")
    db.commit()
    for c in COLUMNS:
        n = db.execute(f"SELECT COUNT({c}) FROM product").fetchone()[0]
        print(f"  {c:<15} {n:>7,}  {100.0 * n / len(serving):5.1f}%")
    db.execute("VACUUM")
    db.close()
    print(f"{os.path.getsize(pack) / 1e6:.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("pack")
    ap.add_argument("--csv", default=DEFAULT_CSV)
    a = ap.parse_args()
    main(a.pack, a.csv)
