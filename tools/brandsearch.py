#!/usr/bin/env python3
"""
Search the branded label database (tools/build_branded_db.py).

    python3 tools/brandsearch.py index  [--db ~/.cache/fdc-bench/branded_full.db]
    python3 tools/brandsearch.py search "premier protein chocolate shake"

`index` adds a word index over brand + description to the `latest` table (one
row per barcode, newest label, discontinued products dropped). Same hand-built
inverted index as food.db, for the same reason: it has to be what the app can
run, and Android's SQLite has no FTS5.

Ranking: a product must match the BRAND the person said if they said one --
"premier protein shake" should never return a store brand -- then by how many of
their words it contains, then shorter descriptions (the plain product rather
than the variety pack), then the newest label.
"""
import argparse, math, os, re, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from foodsearch import stem, STOP

DEFAULT_DB = os.path.expanduser("~/.cache/fdc-bench/branded_full.db")
COLUMNS = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g",
           "sat_fat_g", "sodium_mg", "caffeine_mg"]

# Label words that never help pick a product.
LABEL_NOISE = {"oz", "fl", "ct", "count", "pack", "pk", "box", "bag", "bottle", "can",
               "jar", "size", "value", "family", "net", "wt", "per"}


def words_of(text):
    out = []
    for w in re.findall(r"[a-z0-9]+", text.lower().replace("'", "")):
        if w in STOP and not w.isdigit():
            continue
        if w in LABEL_NOISE or len(w) < 2:
            continue
        out.append(stem(w))
    return out


def index(db):
    db.executescript("""
        DROP TABLE IF EXISTS bword;
        CREATE TABLE bword (word TEXT NOT NULL, fdc_id INTEGER NOT NULL, in_brand INTEGER);
    """)
    rows = db.execute("SELECT fdc_id, brand_name, brand_owner, description FROM latest "
                      "WHERE kcal IS NOT NULL AND serving_size > 0").fetchall()
    batch = []
    for fid, brand, owner, desc in rows:
        bw = set(words_of(f"{brand} {owner}"))
        dw = set(words_of(desc))
        for w in bw | dw:
            batch.append((w, fid, 1 if w in bw else 0))
        if len(batch) > 200000:
            db.executemany("INSERT INTO bword VALUES (?,?,?)", batch)
            batch = []
    db.executemany("INSERT INTO bword VALUES (?,?,?)", batch)
    db.execute("CREATE INDEX idx_bword ON bword(word)")
    db.commit()
    print(f"indexed {len(rows):,} products")


_brand_words = None


def brand_vocab(db):
    """Words that name a brand somewhere. Used to tell a brand word from a food word."""
    global _brand_words
    if _brand_words is None:
        _brand_words = {r[0] for r in db.execute(
            "SELECT word FROM bword WHERE in_brand=1 GROUP BY word HAVING COUNT(*) >= 3")}
    return _brand_words


def search(db, text, limit=8, brand=None):
    """
    -> list of dicts: fdc_id, brand, description, serving_g, household, per100 {..}
    `brand`, when the caller knows it (the AI said so), is required to match.
    """
    words = list(dict.fromkeys(words_of(text)))
    if not words:
        return []
    need_brand = list(dict.fromkeys(words_of(brand))) if brand else []
    q = " OR ".join(["word = ?"] * len(words))
    sql = (f"SELECT fdc_id, COUNT(DISTINCT word) n, SUM(in_brand) b FROM bword "
           f"WHERE {q} GROUP BY fdc_id ORDER BY n DESC LIMIT 400")
    cands = db.execute(sql, words).fetchall()
    if not cands:
        return []
    ids = [c[0] for c in cands]
    hits = {c[0]: c[1] for c in cands}
    rows = db.execute(
        f"SELECT fdc_id, brand_name, brand_owner, description, serving_size, serving_unit, "
        f"household, modified, {', '.join(COLUMNS)} FROM latest "
        f"WHERE fdc_id IN ({','.join('?' * len(ids))})", ids).fetchall()
    scored = []
    for r in rows:
        bw = set(words_of(f"{r[1]} {r[2]}"))
        if need_brand and not all(w in bw for w in need_brand):
            continue
        dw = set(words_of(r[3]))
        extra = len(dw - set(words))
        score = hits[r[0]] * 10 - extra * 1.5 + (5 if bw & set(words) else 0)
        # How many label versions this exact product has had. The plain, long-
        # selling product has dozens (every barcode, every pack size, every year);
        # a limited edition has one. Without this, "3 oreos" ranked "Oreo fudge
        # covered bites" level with the cookie everyone means.
        score += min(6.0, math.log1p(_popularity(db, r[1] or r[2], r[3])) * 1.5)
        scored.append((score, r[7] or "", r))
    scored.sort(key=lambda x: (-x[0], x[1]), reverse=False)
    # newest label first among equal scores
    scored.sort(key=lambda x: (-x[0], "".join(chr(255 - ord(c)) for c in x[1])))
    out = []
    seen = set()
    for _s, _m, r in scored:
        key = (r[1].lower(), r[3].lower(), r[4])
        if key in seen:
            continue
        seen.add(key)
        out.append({
            "fdc_id": r[0], "brand": r[1] or r[2], "description": r[3],
            "serving": f"{r[4]:g} {r[5]}".strip(), "serving_size": r[4],
            "household": r[6], "per100": dict(zip(COLUMNS, r[8:])),
        })
        if len(out) >= limit:
            break
    return out


def _popularity(db, brand, desc):
    try:
        r = db.execute("SELECT n FROM pop WHERE b=? AND d=?",
                       ((brand or "").lower(), (desc or "").lower())).fetchone()
    except sqlite3.OperationalError:
        return 0
    return r[0] if r else 0


def per_serving(p, servings=1.0):
    s = (p["serving_size"] or 0) / 100.0 * servings
    return {k: (v * s if v is not None else None) for k, v in p["per100"].items()}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["index", "search"])
    ap.add_argument("text", nargs="?")
    ap.add_argument("--db", default=DEFAULT_DB)
    ap.add_argument("--brand")
    a = ap.parse_args()
    db = sqlite3.connect(a.db)
    if a.cmd == "index":
        index(db)
    else:
        for p in search(db, a.text, brand=a.brand):
            n = per_serving(p)
            print(f"{p['fdc_id']} | {p['brand'][:16]:16s} | {p['description'][:55]:55s} | "
                  f"{p['serving']} ({p['household']}) | kcal {n['kcal']:.0f} P {n['protein_g'] or 0:.0f}")
