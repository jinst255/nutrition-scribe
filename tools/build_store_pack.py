#!/usr/bin/env python3
"""
Build the store packs: extra package labels for one store each (Great Value,
Kirkland Signature, Trader Joe's ...), downloaded by the app when someone wants them.

    python3 tools/build_store_pack.py -o out/            # every store in tools/store_packs.json
    python3 tools/build_store_pack.py -o out/ kirkland   # just one

Writes out/<id>.db.gz per store plus out/stores.json, the catalogue the app reads
(products, size and sha256 of each pack). The GitHub workflow store-packs.yml runs
this and attaches the files to the release tagged "store-packs".

WHERE THE DATA COMES FROM, AND WHY IT IS ALLOWED
------------------------------------------------
Open Food Facts only: products people photographed and typed in, published under
the Open Database License (ODbL 1.0). The license lets anyone reuse the data,
commercially or not, as long as (1) it is credited ("© Open Food Facts
contributors") and (2) a database made from it is shared under the same license.
The packs are public release assets under ODbL, and the app credits Open Food
Facts wherever the numbers show. Nothing is taken from store websites, whose terms
forbid it. USDA Branded Foods (public domain) is already in the built-in pack.

It reads Open Food Facts' daily export (en.openfoodfacts.org.products.csv.gz, about
1.5 GB), the route they ask bulk users to take; the search API throttles bulk reads.
The file is kept in ~/.cache/ns-off/ for a week (public data, nothing personal).

SCHEMA: the label pack's (build_label_pack.py, format 4 with label micros), plus
meta kind=store. Product ids start at slot * 1,000,000,000 so they never collide
with the built-in pack's or another store's, and stay the same between builds for
the same barcode, because logged foods keep pointing at them.
"""
import argparse, csv, gzip, hashlib, json, os, re, shutil, sqlite3, sys, time, urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from build_label_pack import words_of, count_of, measure_of  # noqa: E402  (same word rules as the app)

CATALOG = os.path.join(HERE, "store_packs.json")
CACHE = os.path.expanduser("~/.cache/ns-off")
EXPORT_URL = "https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz"
EXPORT_FILE = os.path.join(CACHE, "products.csv.gz")
# No email or name: CLAUDE.md, and OFF only asks that the app be identifiable.
USER_AGENT = "NutritionScribe-StorePacks/1.0 (open-source app; github.com/jinst255/nutrition-scribe)"
SLOT_SIZE = 1_000_000_000
FORMAT = "4"

MACROS = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g", "sat_fat_g", "sodium_mg", "caffeine_mg"]
MICROS = ["calcium_mg", "iron_mg", "potassium_mg", "vitamin_d_ug", "cholesterol_mg"]
# Open Food Facts nutriment key -> (column, factor from OFF's unit). OFF keeps
# minerals in grams; the pack keeps milligrams (vitamin D: micrograms).
OFF_KEYS = {
    "kcal": ("energy-kcal", 1), "protein_g": ("proteins", 1), "carbs_g": ("carbohydrates", 1),
    "fat_g": ("fat", 1), "fiber_g": ("fiber", 1), "sugar_g": ("sugars", 1),
    "sat_fat_g": ("saturated-fat", 1), "sodium_mg": ("sodium", 1000), "caffeine_mg": ("caffeine", 1000),
    "calcium_mg": ("calcium", 1000), "iron_mg": ("iron", 1000), "potassium_mg": ("potassium", 1000),
    "vitamin_d_ug": ("vitamin-d", 1_000_000), "cholesterol_mg": ("cholesterol", 1000),
}


def load_catalog(path=CATALOG):
    with open(path) as f:
        return json.load(f)["stores"]


def download_export(path=EXPORT_FILE, max_age_days=7):
    """The daily export, kept for a week. 1.5 GB: Open Food Facts asks bulk users to
    use it instead of the search API, which throttles anything bigger than an app's
    occasional lookups (the first build tried the API and got 503s and 401s)."""
    if os.path.exists(path) and time.time() - os.path.getmtime(path) < max_age_days * 86400:
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    print(f"downloading {EXPORT_URL}", flush=True)
    req = urllib.request.Request(EXPORT_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as out:
        shutil.copyfileobj(r, out, 1 << 20)
    os.replace(path + ".part", path)
    return path


def read_export(path, stores):
    """{store id: {barcode: product}} for US products of each store's brand tags, in one pass.
    A product is a dict shaped like the API's: code, product_name, brands, serving_size,
    serving_quantity, unique_scans_n and nutriments (the *_100g columns)."""
    csv.field_size_limit(1 << 30)
    by_tag = {t: s["id"] for s in stores for t in s["tags"]}
    out = {s["id"]: {} for s in stores}
    rows = 0
    with gzip.open(path, "rt", encoding="utf-8", errors="replace", newline="") as f:
        reader = csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE)
        header = next(reader)
        col = {name: i for i, name in enumerate(header)}
        need = ["code", "brands_tags", "countries_tags", "product_name"]
        missing = [n for n in need if n not in col]
        if missing:
            raise SystemExit(f"export has no {missing} column; its format changed")
        nut_cols = [(name, i) for name, i in col.items() if name.endswith("_100g")]
        def get(r, name):
            i = col.get(name)
            return r[i] if i is not None and i < len(r) else ""
        for r in reader:
            rows += 1
            if rows % 500_000 == 0:
                print(f"  {rows:,} rows read", flush=True)
            tags = get(r, "brands_tags")
            if not tags:
                continue
            ids = {by_tag[t] for t in tags.split(",") if t in by_tag}
            if not ids or "en:united-states" not in get(r, "countries_tags").split(","):
                continue
            code = re.sub(r"\D", "", get(r, "code"))
            if not code:
                continue
            nutriments = {}
            for name, i in nut_cols:
                v = r[i] if i < len(r) else ""
                if v:
                    try:
                        nutriments[name] = float(v)
                    except ValueError:
                        pass
            p = {"code": code, "product_name": get(r, "product_name"), "brands": get(r, "brands"),
                 "serving_size": get(r, "serving_size"), "serving_quantity": get(r, "serving_quantity"),
                 "unique_scans_n": get(r, "unique_scans_n") or 1, "nutriments": nutriments}
            for sid in ids:
                out[sid].setdefault(code, p)
    print(f"  {rows:,} rows read in all", flush=True)
    return out


def serving_grams(p):
    """The label serving in grams, or None. OFF's serving_quantity is grams (or mL)."""
    try:
        q = float(p.get("serving_quantity") or 0)
    except (TypeError, ValueError):
        q = 0
    return q if 1 <= q <= 1200 else None


def row_of(p):
    """(serving_g, serving_text, values per ONE serving x10) or None when the label is unusable."""
    n = p.get("nutriments") or {}
    sg = serving_grams(p)
    per100 = {}
    for col, (key, factor) in OFF_KEYS.items():
        v = n.get(f"{key}_100g")
        if v is None and sg is not None and n.get(f"{key}_serving") is not None:
            try:
                v = float(n[f"{key}_serving"]) / sg * 100.0
            except (TypeError, ValueError):
                v = None
        try:
            per100[col] = None if v is None else float(v) * factor
        except (TypeError, ValueError):
            per100[col] = None
    kcal = per100.get("kcal")
    if kcal is None or kcal < 0 or kcal > 950:
        return None
    macro_sum = sum(per100.get(c) or 0 for c in ("protein_g", "carbs_g", "fat_g"))
    if macro_sum > 105 or (kcal > 40 and macro_sum == 0):
        return None
    if sg is None:
        sg, text = 100.0, "100 g"
    else:
        raw = (p.get("serving_size") or "").strip().lower()
        text = raw if raw and re.search(r"\d", raw) else f"{sg:g} g"
    vals = {c: (None if v is None else round(v * sg / 100.0 * 10)) for c, v in per100.items()}
    for c in ("protein_g", "carbs_g", "fat_g", "sugar_g", "sodium_mg"):
        if vals[c] is None:
            vals[c] = 0
    return sg, text, vals


def name_of(p, brand_names):
    name = (p.get("product_name_en") or p.get("product_name") or "").strip()
    name = re.sub(r"\s+", " ", name)
    # "Kirkland Signature Cottage Cheese", "Great value, cottage cheese" -> "Cottage Cheese":
    # the brand is its own column, and the app shows "Brand — Name".
    for t in sorted(brand_names, key=len, reverse=True):
        m = re.match(re.escape(t) + r"(?:['’]s)?\b[\s,:\-–—]+", name, re.IGNORECASE)
        if m and len(name) > m.end() + 2:
            name = name[m.end():]
            break
    if name and name[0].islower():
        name = name[0].upper() + name[1:]
    return name if len(name) >= 3 else None


def build(store, products, out_dir, built):
    path = os.path.join(out_dir, f"{store['id']}.db")
    if os.path.exists(path):
        os.remove(path)
    d = sqlite3.connect(path)
    d.executescript(f"""
        PRAGMA journal_mode=OFF; PRAGMA page_size=4096;
        CREATE TABLE product (id INTEGER PRIMARY KEY, brand TEXT, description TEXT,
            serving_g REAL, serving_text TEXT, count REAL, count_unit TEXT,
            measure REAL, measure_unit TEXT,
            {", ".join(c + " INTEGER" for c in MACROS)}, pop INTEGER, owner TEXT,
            {", ".join(c + " INTEGER" for c in MICROS)});
        CREATE TABLE gtin (gtin TEXT PRIMARY KEY, id INTEGER NOT NULL) WITHOUT ROWID;
        CREATE TABLE pword (word TEXT NOT NULL, id INTEGER NOT NULL, in_brand INTEGER,
            PRIMARY KEY (word, id)) WITHOUT ROWID;
        CREATE TABLE brand_word (word TEXT PRIMARY KEY);
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    """)
    base = store["slot"] * SLOT_SIZE
    brand = store["title"].split(" and ")[0]
    brand_words = set(words_of(brand))
    seen, used, rows, gtins, pwords = {}, set(), [], [], []
    # Barcode order: the same product gets the same id on every rebuild.
    for code in sorted(products):
        p = products[code]
        name = name_of(p, {brand, *store["aliases"], *store["title"].split(" by ")})
        r = row_of(p)
        if not name or not r:
            continue
        sg, text, vals = r
        key = (name.lower(), sg)
        if key in seen:                        # same product, another barcode
            gtins.append((code, seen[key]))
            continue
        pid = base + int(code[-9:]) % (SLOT_SIZE - 1) + 1
        while pid in used:
            pid = base + (pid - base) % (SLOT_SIZE - 1) + 1
        used.add(pid)
        seen[key] = pid
        n, cu = count_of(text)
        mn, mu = measure_of(text)
        pop = max(1, int(p.get("unique_scans_n") or 1))
        rows.append((pid, brand, name, sg, text, n, cu, mn, mu, *[vals[c] for c in MACROS], pop, None,
                     *[vals[c] for c in MICROS]))
        gtins.append((code, pid))
        for w in brand_words | set(words_of(name)):
            pwords.append((w, pid, 1 if w in brand_words else 0))
    if not rows:
        d.close()
        os.remove(path)
        return None
    d.executemany(f"INSERT INTO product VALUES ({','.join('?' * len(rows[0]))})", rows)
    d.executemany("INSERT OR IGNORE INTO gtin VALUES (?,?)", gtins)
    d.executemany("INSERT OR IGNORE INTO pword VALUES (?,?,?)", pwords)
    d.executemany("INSERT OR IGNORE INTO brand_word VALUES (?)", [(w,) for w in sorted(set(store["brand_words"]))])
    d.executemany("INSERT INTO meta VALUES (?,?)", [
        ("format", FORMAT), ("kind", "store"), ("store", store["id"]), ("title", store["title"]),
        ("slot", str(store["slot"])), ("source", "Open Food Facts"), ("license", "ODbL-1.0"),
        ("attribution", "© Open Food Facts contributors, openfoodfacts.org"),
        ("built", built), ("products", str(len(rows))),
    ])
    d.commit()
    d.execute("VACUUM")
    d.close()
    gz = path + ".gz"
    # No time or name in the gzip header, so the same data gives the same bytes and sha256.
    with open(path, "rb") as src, open(gz, "wb") as raw, \
            gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as dst:
        shutil.copyfileobj(src, dst)
    os.remove(path)
    with open(gz, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    return {"id": store["id"], "title": store["title"], "shop": store["shop"], "slot": store["slot"],
            "aliases": store["aliases"], "products": len(rows), "barcodes": len(set(g[0] for g in gtins)),
            "bytes": os.path.getsize(gz), "sha256": sha, "file": os.path.basename(gz)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="*", help="store ids (default: all)")
    ap.add_argument("-o", "--out", default="store-packs")
    ap.add_argument("--export", help="a products.csv.gz already on disk (default: download it)")
    a = ap.parse_args()
    stores = load_catalog()
    if a.ids:
        stores = [s for s in stores if s["id"] in a.ids]
    os.makedirs(a.out, exist_ok=True)
    built = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    entries = []
    export = a.export or download_export()
    print(f"reading {export}", flush=True)
    found = read_export(export, stores)
    for s in stores:
        products = found[s["id"]]
        print(f"{s['title']}: {len(products):,} US products in Open Food Facts", flush=True)
        e = build(s, products, a.out, built)
        if e:
            entries.append(e)
            print(f"  {e['products']:,} products, {e['barcodes']:,} barcodes, {e['bytes'] / 1e6:.2f} MB", flush=True)
        else:
            print("  nothing usable; skipped", flush=True)
    with open(os.path.join(a.out, "stores.json"), "w") as f:
        json.dump({"format": 1, "built": built, "source": "Open Food Facts", "license": "ODbL-1.0",
                   "attribution": "© Open Food Facts contributors", "stores": entries}, f, indent=1)
    print(f"wrote {len(entries)} packs and stores.json to {a.out}")


if __name__ == "__main__":
    main()
