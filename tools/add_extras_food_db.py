#!/usr/bin/env python3
"""
Add the "More to track" nutrients to an existing food.db, in place, by FDC id.

The shipped food.db is built by build_food_db.py and then curated (aliases,
default portions). Rebuilding it from scratch to add columns would redo all of
that; this only adds columns and fills them, so every row, alias and index stays
exactly as it was.

Usage:
    python3 tools/add_extras_food_db.py <dir-with-unzipped-FDC-csvs> [food.db]

The FDC downloads are SR Legacy (2018-04) and FNDDS Survey (2022-10-28), the same
releases build_food_db.py read. Absent stays NULL: "not known" is never 0.
"""
import csv, os, sqlite3, sys

# FDC nutrient.id (SR Legacy) and legacy nutrient_nbr (FNDDS) for each column.
EXTRAS = {
    "copper_mg": (1098, 312),
    "manganese_mg": (1101, 315),
    "phosphorus_mg": (1091, 305),
    "choline_mg": (1180, 421),
    "thiamin_mg": (1165, 404),
    "riboflavin_mg": (1166, 405),
    "niacin_mg": (1167, 406),
    "vitamin_b6_mg": (1175, 415),
    "alcohol_g": (1018, 221),
    # ALA: the n-3 isomer where it was measured, else all 18:3 (nearly all ALA in food).
    "ala_g": (1404, 851),
}
ALA_FALLBACK = (1270, 619)


def csv_dirs(base):
    for root, _dirs, files in os.walk(base, followlinks=True):
        if "food_nutrient.csv" in files:
            yield root


def main(base, path):
    by_id = {}
    for col, ids in EXTRAS.items():
        for i in ids:
            by_id[i] = col
    fallback = set(ALA_FALLBACK)
    db = sqlite3.connect(path)
    have = {r[0] for r in db.execute("SELECT fdc_id FROM food")}
    cols = {r[1] for r in db.execute("PRAGMA table_info(food)")}
    for col in EXTRAS:
        if col not in cols:
            db.execute(f"ALTER TABLE food ADD COLUMN {col} REAL")
    vals = {}
    ala18 = {}
    for d in csv_dirs(base):
        with open(os.path.join(d, "food_nutrient.csv"), newline="", encoding="utf-8", errors="replace") as f:
            for r in csv.DictReader(f):
                try:
                    fid = int(r["fdc_id"]); nid = int(float(r["nutrient_id"])); amt = float(r["amount"])
                except (ValueError, KeyError):
                    continue
                if fid not in have:
                    continue
                if nid in fallback:
                    ala18[fid] = amt
                col = by_id.get(nid)
                if col:
                    vals.setdefault(fid, {})[col] = amt
    for fid, a in ala18.items():
        vals.setdefault(fid, {}).setdefault("ala_g", a)
    n = 0
    for fid, v in vals.items():
        sets = ", ".join(f"{c} = ?" for c in v)
        db.execute(f"UPDATE food SET {sets} WHERE fdc_id = ?", list(v.values()) + [fid])
        n += 1
    db.commit()
    for col in EXTRAS:
        known = db.execute(f"SELECT COUNT(*) FROM food WHERE {col} IS NOT NULL").fetchone()[0]
        print(f"{col}: {known} of {len(have)} foods")
    db.execute("VACUUM")
    db.close()
    print(f"updated {n} foods")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "food.db"))
