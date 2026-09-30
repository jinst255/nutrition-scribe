#!/usr/bin/env python3
"""
Cut the full branded label database down to something an app could carry.

    python3 tools/ship_branded.py -o label.db [--since 2020-01-01]

One row per distinct (brand, product, serving): the dataset lists the same
product under every barcode it has ever been sold with, and every size of pack.
Nutrients are stored per SERVING as integers x10 (0.1 g / 0.1 mg resolution,
which is finer than any label prints), and brands are a lookup table: both
matter, since this file ships inside the APK.
"""
import argparse, os, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from brandsearch import words_of, COLUMNS, DEFAULT_DB


def build(src, out, since):
    s = sqlite3.connect(src)
    if os.path.exists(out):
        os.remove(out)
    d = sqlite3.connect(out)
    d.executescript(f"""
        PRAGMA journal_mode=OFF; PRAGMA page_size=4096;
        CREATE TABLE brand (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE product (id INTEGER PRIMARY KEY, brand INTEGER, description TEXT,
            serving_g REAL, household TEXT, {", ".join(c + " INTEGER" for c in COLUMNS)});
        CREATE TABLE pword (word TEXT NOT NULL, id INTEGER NOT NULL);
    """)
    rows = s.execute(
        f"SELECT fdc_id, COALESCE(NULLIF(brand_name,''), brand_owner), description, serving_size, "
        f"household, {', '.join(COLUMNS)} FROM latest WHERE kcal IS NOT NULL AND serving_size > 0 "
        f"AND modified >= ? ORDER BY modified DESC", (since,)).fetchall()
    brands, seen, prods, words = {}, set(), [], []
    for r in rows:
        brand = (r[1] or "").strip().title()
        key = (brand.lower(), r[2].lower(), r[3])
        if key in seen:
            continue
        seen.add(key)
        bid = brands.setdefault(brand, len(brands) + 1)
        per = [None if v is None else round(v * r[3] / 100.0 * 10) for v in r[5:]]
        prods.append((r[0], bid, r[2], r[3], r[4], *per))
        for w in set(words_of(f"{brand} {r[2]}")):
            words.append((w, r[0]))
    d.executemany("INSERT INTO brand VALUES (?,?)", [(i, n) for n, i in brands.items()])
    d.executemany(f"INSERT INTO product VALUES ({','.join('?' * (5 + len(COLUMNS)))})", prods)
    d.executemany("INSERT INTO pword VALUES (?,?)", words)
    d.execute("CREATE INDEX idx_pword ON pword(word)")
    d.commit()
    d.execute("VACUUM")
    d.close()
    size = os.path.getsize(out)
    print(f"{len(prods):,} products, {len(brands):,} brands, {size / 1e6:.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default="label.db")
    ap.add_argument("--src", default=DEFAULT_DB)
    ap.add_argument("--since", default="0000")
    a = ap.parse_args()
    build(a.src, a.out, a.since)
