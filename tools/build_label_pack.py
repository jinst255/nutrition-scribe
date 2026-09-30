#!/usr/bin/env python3
"""
Build the optional label pack: real package labels the app can search offline and
look up by barcode.

    python3 tools/build_label_pack.py -o labels.db [--since 2022-01-01]

Input is ~/.cache/fdc-bench/branded_full.db (tools/build_branded_db.py, then
tools/brandsearch.py index). That is USDA Branded Foods: the Nutrition Facts panels
manufacturers themselves submit (GDSN), which is the "truth" the app is judged
against — a label, not an average.

WHY A DOWNLOAD, NOT PART OF THE APK
-----------------------------------
All products since 2022 come to ~40 MB on the phone (~17 MB compressed). That is
too much to force on everyone, and exactly right to offer: tested on 54 real
labels, reading the label rather than estimating it took packaged-food accuracy
from 83% to 91% and halved the sodium misses.

SCHEMA (read by shared/.../LabelPackSource.kt — keep them in step)
------
  product(id, brand, description, serving_g, serving_text, count, count_unit,
          measure, measure_unit,
          kcal, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sat_fat_g, sodium_mg,
          caffeine_mg, pop, owner)
      Nutrients are FOR ONE SUBMITTED SERVING, as integers x10 (0.1 g / 0.1 mg,
      finer than any label prints). Integers because SQLite stores a label's small
      whole numbers in a byte or two, where a REAL is always eight: it halves the
      download. serving_g is the submitted serving, which is sometimes the whole
      package; "count"/"count_unit" come from the household text ("2 cookies") and
      are what a spoken count is divided by; "measure"/"measure_unit" likewise
      from "1/4 cup (28 g)", so "a cup of it" can be weighed.
  gtin(gtin, id)              every barcode the product has been sold under
  pword(word, id, in_brand)   word index over brand + description
  brand_word(word)            words that name a brand somewhere
  meta(key, value)
"""
import argparse, math, os, re, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from foodsearch import stem, STOP

DEFAULT_SRC = os.path.expanduser("~/.cache/fdc-bench/branded_full.db")
COLUMNS = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g",
           "sat_fat_g", "sodium_mg", "caffeine_mg"]
NOISE = {"oz", "fl", "ct", "count", "pack", "pk", "box", "bag", "bottle", "can",
         "jar", "size", "value", "family", "net", "wt", "per", "brand"}
KEEP = {"big", "mini", "light", "lite", "zero", "diet", "original", "kind", "simply", "just", "pure", "good"}
# Household units that are a weight or volume, not a count of things.
MEASURE_UNITS = {"g", "grm", "gram", "grams", "oz", "oza", "onz", "ounce", "ounces", "ml",
                 "mlt", "l", "fl", "floz", "cup", "cups", "tbsp", "tsp", "tablespoon",
                 "tablespoons", "teaspoon", "teaspoons", "lb", "lbs", "mg"}


# Brands whose names are also ordinary words, so the ratio rule cannot find them.
ALWAYS_BRANDS = ["built", "killer", "oscar", "mayer", "thomas", "tillamook", "cheerio", "ritz",
                 "dorito", "goldfish", "lunchable", "digiorno", "maruchan", "barilla", "prego",
                 "celsius", "gatorade", "monster", "oikos", "siggi", "rxbar", "belvita", "sabra",
                 "annie", "quaker", "clif", "oreo", "kind", "fairlife", "chobani", "kodiak",
                 "premier", "quest", "sargento", "kirkland", "halo", "pepperidge",
                 "kellogg", "kraft", "nabisco", "lays", "lay's", "pringles", "cheetos", "tostitos"]


def words_of(text):
    """Must stay identical to LabelPackSource.words() in Kotlin."""
    out = []
    for w in re.findall(r"[a-z0-9]+", (text or "").lower().replace("'", "")):
        if len(w) < 2 or w in NOISE:
            continue
        if not (any(c.isdigit() for c in w) or w not in STOP or w in KEEP):
            continue
        out.append(stem(w))
    return out


def count_of(household):
    """'2 cookies (29g)' -> (2.0, 'cookie'); '1 BAR' -> (1.0, 'bar'); '85 GRM' -> None."""
    if not household:
        return None, None
    m = re.match(r"\s*(\d+(?:\.\d+)?|\d+\s*/\s*\d+)\s+([a-zA-Z]+)", household)
    if not m:
        return None, None
    num = m.group(1).replace(" ", "")
    if "/" in num:
        a, b = num.split("/")
        n = float(a) / float(b) if float(b) else None
    else:
        n = float(num)
    unit = m.group(2).lower()
    if unit in MEASURE_UNITS or not n:
        return None, None
    # Shown to people ("1 cookie (14 g)"), so a plain singular, not the search stem.
    if unit.endswith("ies") and unit not in ("cookies", "pies"):
        unit = unit[:-3] + "y"
    elif unit.endswith("es") and unit[:-2].endswith(("ch", "sh", "x")):
        unit = unit[:-2]
    elif unit.endswith("s") and not unit.endswith("ss"):
        unit = unit[:-1]
    return n, unit


MEASURES = {"cup": "cup", "cups": "cup", "tbsp": "tbsp", "tablespoon": "tbsp", "tablespoons": "tbsp",
            "tsp": "tsp", "teaspoon": "tsp", "teaspoons": "tsp"}


def measure_of(household):
    """'1/4 cup (28g)' -> (0.25, 'cup'); '2 tbsp (32 g)' -> (2.0, 'tbsp'); '1 bar' -> None."""
    if not household:
        return None, None
    m = re.match(r"\s*(\d+(?:\.\d+)?|\d+\s*/\s*\d+|\d+\s+\d+\s*/\s*\d+)\s*([a-zA-Z]+)", household)
    if not m:
        return None, None
    unit = MEASURES.get(m.group(2).lower())
    if not unit:
        return None, None
    num = m.group(1).strip()
    try:
        if " " in num:
            whole, frac = num.split(None, 1)
            a, b = frac.replace(" ", "").split("/")
            n = float(whole) + float(a) / float(b)
        elif "/" in num:
            a, b = num.replace(" ", "").split("/")
            n = float(a) / float(b)
        else:
            n = float(num)
    except (ValueError, ZeroDivisionError):
        return None, None
    return (n, unit) if n > 0 else (None, None)


def serving_text(household, serving_g, unit):
    h = (household or "").strip()
    g = f"{serving_g:g} {'ml' if (unit or '').lower() == 'ml' else 'g'}"
    if not h or re.fullmatch(r"[\d.]+\s*(grm|g|mlt|ml|oza|onz)", h.lower()):
        return g
    if re.search(r"\d\s*(g|ml)\b", h.lower()):
        return h.lower()          # "1 bar (49g)" already says it
    return f"{h.lower()} ({g})"


def build(src, out, since):
    s = sqlite3.connect(src)
    if os.path.exists(out):
        os.remove(out)
    d = sqlite3.connect(out)
    d.executescript(f"""
        PRAGMA journal_mode=OFF; PRAGMA page_size=4096;
        CREATE TABLE product (id INTEGER PRIMARY KEY, brand TEXT, description TEXT,
            serving_g REAL, serving_text TEXT, count REAL, count_unit TEXT,
            measure REAL, measure_unit TEXT,
            {", ".join(c + " INTEGER" for c in COLUMNS)}, pop INTEGER, owner TEXT);
        CREATE TABLE pword (word TEXT NOT NULL, id INTEGER NOT NULL, in_brand INTEGER,
            PRIMARY KEY (word, id)) WITHOUT ROWID;
        CREATE TABLE brand_word (word TEXT PRIMARY KEY);
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    """)
    pop = {(b, dd): n for b, dd, n in s.execute("SELECT b, d, n FROM pop")}
    rows = s.execute(
        f"SELECT fdc_id, gtin, brand_name, brand_owner, description, serving_size, serving_unit, household, "
        f"{', '.join(COLUMNS)} FROM latest WHERE kcal IS NOT NULL AND serving_size > 0 "
        f"AND modified >= ? ORDER BY modified DESC", (since,)).fetchall()
    seen, prods, pwords, brand_counts = set(), [], [], {}
    for r in rows:
        fid, gtin, bname, bowner, desc, sg, su, hh = r[:8]
        brand = (bname or bowner or "").strip()
        key = (brand.lower(), (desc or "").lower(), sg)
        gtin_digits = re.sub(r"\D", "", gtin or "")
        if key in seen:
            # Same product under another barcode: keep the barcode, not a second row.
            if gtin_digits:
                prods.append(("gtin_alias", gtin_digits, key))
            continue
        seen.add(key)
        n, cu = count_of(hh)
        mn, mu = measure_of(hh)
        popularity = pop.get(((bname or bowner or "").lower(), (desc or "").lower()), 1)
        per_serving = [None if v is None else round(v * sg / 100.0 * 10) for v in r[8:]]
        # Fat, carbs, protein, sugars and sodium are mandatory on a US label, so
        # an empty one on a label that lists calories is a zero nobody typed.
        if per_serving[0] is not None:
            for i, c in enumerate(COLUMNS):
                if c in ("protein_g", "carbs_g", "fat_g", "sugar_g", "sodium_mg") and per_serving[i] is None:
                    per_serving[i] = 0
        # A serving over 1.2 kg is a package weight typed into the serving field
        # (a "2160 g" can of Monster): its per-serving numbers are fiction.
        if sg > 1200:
            continue
        owner = (bowner or "").strip()
        prods.append((fid, gtin_digits, brand.title(), desc, sg, serving_text(hh, sg, su), n, cu, mn, mu,
                      *per_serving, popularity, owner.title() if owner and owner.lower() != brand.lower() else None))
        bw = set(words_of(f"{bname} {bowner}"))
        for w in bw:
            brand_counts[w] = brand_counts.get(w, 0) + 1
        for w in bw | set(words_of(desc)):
            pwords.append((w, fid, 1 if w in bw else 0))

    # Every barcode a product has been sold under resolves to its row.
    by_key = {}
    real = []
    for p in prods:
        if p[0] == "gtin_alias":
            continue
        by_key[(p[2].lower(), (p[3] or "").lower(), p[4])] = p[0]
        real.append(p)
    d.executemany(f"INSERT INTO product VALUES ({','.join('?' * (11 + len(COLUMNS)))})",
                  [(p[0], *p[2:]) for p in real])
    d.execute("CREATE TABLE gtin (gtin TEXT PRIMARY KEY, id INTEGER NOT NULL) WITHOUT ROWID")
    gt = {}
    for p in real:
        if p[1]:
            gt.setdefault(p[1], p[0])
    for p in prods:
        if p[0] == "gtin_alias":
            b, dd, sg = p[2]
            fid = by_key.get((b, dd, sg))
            if fid and p[1]:
                gt.setdefault(p[1], fid)
    d.executemany("INSERT INTO gtin VALUES (?,?)", gt.items())
    d.executemany("INSERT OR IGNORE INTO pword VALUES (?,?,?)", pwords)
    # A brand word mostly appears IN brand names across all labels — "chobani",
    # "kodiak", "quest" — not "honey" or "maple", which brands use and descriptions
    # use far more. USDA head nouns are never brands ("Yogurt, Greek, CHOBANI" does
    # not make "yogurt" one). tools/offline_labels.py measured this rule.
    desc_counts = {}
    for w, _fid, in_b in pwords:
        if not in_b:
            desc_counts[w] = desc_counts.get(w, 0) + 1
    usda_heads = set()
    food_db = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "food.db")
    for (desc,) in sqlite3.connect(food_db).execute("SELECT description FROM food"):
        usda_heads |= {stem(w) for w in re.findall(r"[a-z]+", desc.split(",")[0].lower())}
    brand_words = {w for w, c in brand_counts.items()
                   if c >= 5 and c / (c + desc_counts.get(w, 0)) >= 0.6 and w not in usda_heads and len(w) > 2}
    brand_words |= {stem(w) for w in ALWAYS_BRANDS}
    d.executemany("INSERT INTO brand_word VALUES (?)", [(w,) for w in sorted(brand_words)])
    d.executemany("INSERT INTO meta VALUES (?,?)", [
        ("format", "3"), ("source", "USDA FoodData Central, Branded Foods"),
        ("since", since), ("products", str(len(real))),
    ])
    d.commit()
    d.execute("VACUUM")
    d.close()
    size = os.path.getsize(out)
    print(f"{len(real):,} products, {len(gt):,} barcodes, {size / 1e6:.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default="labels.db")
    ap.add_argument("--src", default=DEFAULT_SRC)
    ap.add_argument("--since", default="2022-01-01")
    a = ap.parse_args()
    build(a.src, a.out, a.since)
