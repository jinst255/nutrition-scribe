#!/usr/bin/env python3
"""
Build a searchable database of PACKAGE LABELS from USDA's Branded Foods dataset.

WHY
---
The user judges accuracy against the Nutrition Facts on the package, and the
generic USDA rows are averages: "Nutrition bar" is not a Built bar, "Tortilla,
flour" is not a Mission medium flour tortilla. The Branded Foods dataset is the
labels themselves, as submitted by manufacturers (GDSN / Label Insight), about
450k products. Nutrients are stored per 100 g or 100 ml, derived from the label,
and serving_size is the label's own serving.

This builds the FULL table for the tools. What ships in the app is a filtered
cut of it -- see ship_branded() below.

Usage:
    python3 tools/build_branded_db.py <unzipped branded csv dir> -o branded.db
"""
import argparse, csv, os, re, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from foodsearch import stem

csv.field_size_limit(10**9)

# FDC nutrient ids. Energy is 1008 on most labels; a few carry only the Atwater
# variants 2047/2048, which are the same label number under another id.
NUTRIENTS = {1008: "kcal", 2047: "kcal_alt", 2048: "kcal_alt", 1003: "protein_g",
             1005: "carbs_g", 1004: "fat_g", 1079: "fiber_g", 2000: "sugar_g",
             1258: "sat_fat_g", 1093: "sodium_mg", 1057: "caffeine_mg"}
COLUMNS = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g",
           "sat_fat_g", "sodium_mg", "caffeine_mg"]


def read(path):
    with open(path, newline="", encoding="utf-8", errors="replace") as f:
        yield from csv.DictReader(f)


def build(src, out):
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript(f"""
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
        CREATE TABLE product (
            fdc_id INTEGER PRIMARY KEY,
            description TEXT, brand_owner TEXT, brand_name TEXT, subbrand TEXT,
            gtin TEXT, category TEXT,
            serving_size REAL, serving_unit TEXT, household TEXT,
            modified TEXT, discontinued TEXT,
            {", ".join(c + " REAL" for c in COLUMNS)}
        );
    """)

    print("reading food.csv")
    desc = {}
    for r in read(os.path.join(src, "food.csv")):
        if r.get("data_type") == "branded_food":
            desc[int(r["fdc_id"])] = r["description"].strip()
    print(f"  {len(desc):,} products")

    print("reading food_nutrient.csv (1.5 GB)")
    vals = {}
    for r in read(os.path.join(src, "food_nutrient.csv")):
        col = NUTRIENTS.get(int(r["nutrient_id"]) if r["nutrient_id"].isdigit() else 0)
        if not col:
            continue
        try:
            fid, amt = int(r["fdc_id"]), float(r["amount"])
        except ValueError:
            continue
        vals.setdefault(fid, {})[col] = amt

    print("reading branded_food.csv")
    rows = []
    for r in read(os.path.join(src, "branded_food.csv")):
        fid = int(r["fdc_id"])
        v = vals.get(fid, {})
        if "kcal" not in v and "kcal_alt" in v:
            v["kcal"] = v["kcal_alt"]
        try:
            size = float(r["serving_size"]) if r["serving_size"] else None
        except ValueError:
            size = None
        rows.append((fid, desc.get(fid, ""), r["brand_owner"], r["brand_name"],
                     r["subbrand_name"], r["gtin_upc"], r["branded_food_category"],
                     size, r["serving_size_unit"], r["household_serving_fulltext"],
                     r["modified_date"], r["discontinued_date"],
                     *[v.get(c) for c in COLUMNS]))
        if len(rows) >= 50000:
            db.executemany(f"INSERT OR REPLACE INTO product VALUES ({','.join('?' * 21)})", rows)
            rows = []
    db.executemany(f"INSERT OR REPLACE INTO product VALUES ({','.join('?' * 21)})", rows)
    db.commit()
    n = db.execute("SELECT COUNT(*) FROM product").fetchone()[0]
    print(f"  {n:,} rows")
    db.execute("CREATE INDEX idx_gtin ON product(gtin)")
    db.commit()
    db.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("-o", "--out", default="branded.db")
    a = ap.parse_args()
    build(a.src, a.out)
