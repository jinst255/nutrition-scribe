#!/usr/bin/env python3
"""
Build the bundled offline nutrition database from USDA FoodData Central CSVs.

This is what lets the app work with no API key and no network: ~13k foods with
real numbers, household portions, and a full-text index, in about 4 MB.

Usage:
    python3 tools/build_food_db.py <dir-with-unzipped-FDC-csvs> [-o out.db]

Source data (public domain, no attribution required but see README):
    https://fdc.nal.usda.gov/download-datasets.html
    - SR Legacy          (whole foods, "Banana, raw")
    - FNDDS Survey       (as-eaten foods, "Chicken, grilled, NFS")

Every nutrient is captured for every food regardless of which goals the user has
switched on, because a goal added in March should still be able to show January.
That is the whole point of "Record full nutrition" in Settings.
"""
import argparse, csv, glob, os, re, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from foodsearch import stem

# The two downloads identify nutrients DIFFERENTLY and nothing warns you: SR
# Legacy uses FDC nutrient.id (1003, 1008, ...) while FNDDS Survey uses the
# legacy nutrient_nbr (203, 208, ...). Reading only one scheme silently yields
# rows with perfect names and every nutrient NULL — it cost a day once.
NUTRIENTS = {
    # --- FDC nutrient.id ---
    1008: "kcal", 1003: "protein_g", 1005: "carbs_g", 1004: "fat_g",
    1079: "fiber_g", 2000: "sugar_g", 1063: "sugar_g",
    1258: "sat_fat_g", 1093: "sodium_mg", 1057: "caffeine_mg",
    # --- legacy nutrient_nbr ---
    208: "kcal", 203: "protein_g", 205: "carbs_g", 204: "fat_g",
    291: "fiber_g", 269: "sugar_g",
    606: "sat_fat_g", 307: "sodium_mg", 262: "caffeine_mg",
}

# Units are NOT uniform: sodium and caffeine are milligrams, everything else is
# grams or kcal. Storing them in one "amount" column without this in mind is how
# a 1180 mg sodium reading turns into 1180 g.
COLUMNS = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g",
           "sat_fat_g", "sodium_mg", "caffeine_mg"]


def csv_dirs(base):
    """FDC zips extract to a nested dir of the same name; accept either level."""
    seen = []
    for root, _dirs, files in os.walk(base):
        if "food.csv" in files:
            seen.append(root)
    if not seen:
        sys.exit(f"no food.csv found anywhere under {base!r} — unzip the FDC downloads there first")
    return seen


def read_csv(path):
    if not os.path.exists(path):
        return
    with open(path, newline="", encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            yield row


def build(base, out):
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript(f"""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        CREATE TABLE food (
            fdc_id INTEGER PRIMARY KEY,
            description TEXT NOT NULL,
            source TEXT NOT NULL,
            {", ".join(c + " REAL" for c in COLUMNS)},
            -- How much of this food "one of it" means, when the user names no
            -- quantity. See the note where this is computed.
            default_grams REAL
        );
        CREATE TABLE portion (fdc_id INTEGER, label TEXT, grams REAL);
        CREATE TABLE word_index (word TEXT NOT NULL, fdc_id INTEGER NOT NULL);
        -- Curated default senses: a bare "chicken breast" should not land on
        -- "Chicken breast, fried, coated". Populated by curate_food_db.py.
        CREATE TABLE alias (term TEXT PRIMARY KEY, fdc_id INTEGER NOT NULL);
    """)

    dirs = csv_dirs(base)
    print(f"reading {len(dirs)} dataset(s)")

    foods = {}
    for d in dirs:
        for r in read_csv(os.path.join(d, "food.csv")):
            try:
                fid = int(r["fdc_id"])
            except (ValueError, KeyError):
                continue
            desc = (r.get("description") or "").strip()
            if desc:
                foods[fid] = (desc, r.get("data_type", ""))
    print(f"foods: {len(foods)}")

    vals = {}
    kept = 0
    for d in dirs:
        for r in read_csv(os.path.join(d, "food_nutrient.csv")):
            try:
                nid = int(float(r["nutrient_id"]))
            except (ValueError, KeyError, TypeError):
                continue
            col = NUTRIENTS.get(nid)
            if not col:
                continue
            try:
                fid = int(r["fdc_id"])
                amt = float(r["amount"])
            except (ValueError, KeyError, TypeError):
                continue
            if fid in foods:
                # setdefault: the first dataset to supply a nutrient wins, so a
                # food present in both downloads doesn't flip-flop between them.
                vals.setdefault(fid, {}).setdefault(col, amt)
                kept += 1
    print(f"nutrient values kept: {kept}")

    db.executemany(
        f"INSERT INTO food (fdc_id, description, source, {', '.join(COLUMNS)})"
        f" VALUES (?,?,?,{','.join('?' * len(COLUMNS))})",
        [(fid, desc, src, *[vals.get(fid, {}).get(c) for c in COLUMNS])
         for fid, (desc, src) in foods.items()],
    )

    units = {}
    for d in dirs:
        for r in read_csv(os.path.join(d, "measure_unit.csv")):
            units[r["id"]] = r.get("name", "")

    portions = 0
    for d in dirs:
        for r in read_csv(os.path.join(d, "food_portion.csv")):
            try:
                fid = int(r["fdc_id"])
                grams = float(r["gram_weight"])
            except (ValueError, KeyError, TypeError):
                continue
            if fid not in foods:
                continue
            label = (r.get("portion_description") or "").strip()
            if not label or label == "Quantity not specified":
                mod = (r.get("modifier") or "").strip()
                unit = units.get(r.get("measure_unit_id", ""), "")
                label = " ".join(x for x in [unit if unit != "undetermined" else "", mod] if x).strip()
            amt = (r.get("amount") or "").strip()
            if amt and amt not in ("0", "0.0"):
                label = f"{amt} {label}".strip()
            if label:
                db.execute("INSERT INTO portion VALUES (?,?,?)", (fid, label, grams))
                portions += 1
    print(f"portions: {portions}")

    # DEFAULT SERVING SIZE.
    #
    # Someone who logs "banana" named no quantity, so the app has to choose one,
    # and the obvious choices are both wrong. The smallest portion gives "1 slice"
    # = 6 g of banana and "1 nut" = 1.2 g of almonds. A flat 100 g gives two eggs
    # as 200 g.
    #
    # FNDDS already answers this: portion code 90000 is its "quantity not
    # specified" amount -- what a person eats when they don't say. It lines up
    # with common sense everywhere it exists: banana 126 g (one banana), bread
    # 28 g (one slice), milk 244 g (one cup), egg 50 g, apple 200 g (one medium).
    # It leaks into the labels as a literal "90000", which is also why that string
    # was showing up in the UI.
    #
    # Only the ~5,500 FNDDS foods carry it, so SR Legacy foods fall back to a
    # household measure that reads like one item, and then to 100 g.
    ITEM_PATTERNS = [
        r"^1 (whole|medium|large|item|serving|package)\b",
        r"^1 cup\b",
        r"^1 oz\b",
        r"^1 fl oz\b",
    ]

    by_food = {}
    for fid, label, grams in db.execute("SELECT fdc_id, label, grams FROM portion"):
        by_food.setdefault(fid, []).append((label, grams))

    defaults = []
    for fid, (desc, _src) in foods.items():
        opts = by_food.get(fid, [])
        chosen = next((g for lbl, g in opts if lbl == "90000"), None)

        if chosen is None:
            head = re.split(r"[,(]", desc)[0].strip().lower()
            head_word = head.split()[0] if head else ""
            patterns = ITEM_PATTERNS + ([rf"^1 {re.escape(head_word)}\b"] if head_word else [])
            for pat in patterns:
                match = next((g for lbl, g in opts if re.match(pat, lbl.lower())), None)
                if match:
                    chosen = match
                    break

        # 100 g is the basis USDA measures on, so it is the honest last resort.
        defaults.append((chosen if chosen and chosen > 0 else 100.0, fid))

    db.executemany("UPDATE food SET default_grams = ? WHERE fdc_id = ?", defaults)
    # The raw FNDDS code is not a label anyone should ever see.
    db.execute("UPDATE portion SET label = '1 serving' WHERE label = '90000'")

    # A HAND-BUILT INVERTED INDEX, NOT FTS5.
    #
    # The obvious choice here is `CREATE VIRTUAL TABLE ... USING fts5`, and it
    # works fine on desktop Python. It does not work on Android: the system
    # SQLite (3.39.2 on API 34) is compiled WITHOUT the fts5 module, so every
    # query dies with "no such module: fts5". Nothing warns you at build time --
    # it only fails on a device.
    #
    # The alternatives were bundling a custom SQLite (~4 MB per ABI, for one
    # feature) or dropping to FTS4, which has no bm25(). This index costs a few
    # hundred KB, works on every Android version, and has the side benefit that
    # the Python reference and the Kotlin app now run the identical query.
    #
    # Stemming MUST stay in step with FoodQuery.stem() in Kotlin, or words
    # indexed here will never be found by the app.
    words = {}
    for fid, (desc, _src) in foods.items():
        for w in set(stem(x) for x in re.findall(r"[a-z]+", desc.lower()) if len(x) > 1):
            words.setdefault(w, []).append(fid)
    db.executemany("INSERT INTO word_index (word, fdc_id) VALUES (?,?)",
                   ((w, fid) for w, ids in words.items() for fid in ids))
    print(f"index: {len(words)} distinct words")

    db.executescript("""
        -- (word) only, not (word, fdc_id). The covering index was measured: it
        -- left p50 unchanged at 51 ms and moved p95 from 75 ms to 67 ms, for
        -- 370 KB more in every install. Not worth it.
        CREATE INDEX idx_word ON word_index(word);
        CREATE INDEX idx_portion ON portion(fdc_id);
    """)
    db.commit()
    db.execute("VACUUM")

    have = {c: db.execute(f"SELECT COUNT({c}) FROM food").fetchone()[0] for c in COLUMNS}
    db.close()
    print("\ncoverage (foods carrying a value):")
    for c in COLUMNS:
        print(f"  {c:<12} {have[c]:>6}")
    print(f"\ndb bytes: {os.path.getsize(out):,}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("source", help="directory containing unzipped FDC csv folders")
    p.add_argument("-o", "--out", default="food.db")
    a = p.parse_args()
    build(a.source, a.out)
