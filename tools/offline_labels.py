#!/usr/bin/env python3
"""
The offline lookup WITH the branded label database: what shipping (or
downloading) label data would buy a user who has no API key.

A phrase item that names a brand the label database knows ("quest", "premier
protein", "oikos") is looked up there instead of USDA; everything else goes
through the ordinary offline path. No model is involved, so brand detection
is by vocabulary: a word that appears in brand names but is not itself a food
word USDA knows.
"""
import os, re, sqlite3, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import foodsearch as F
import brandsearch as B
import ai_lookup as A

_bdb = None
_brands = None


def branded():
    global _bdb
    if _bdb is None:
        _bdb = sqlite3.connect(os.environ.get("NS_BRANDED_DB", B.DEFAULT_DB))
    return _bdb


def brand_words(db):
    """Words that are brands and not food: 'quest', 'kodiak', 'oikos' -- but not 'honey'."""
    global _brands
    if _brands is None:
        # Food words are the words USDA uses as HEAD nouns ("Yogurt, Greek, ...,
        # CHOBANI" makes "yogurt" a food word, not "chobani").
        usda = set()
        for (d,) in db.execute("SELECT description FROM food"):
            usda |= {F.stem(w) for w in re.findall(r"[a-z]+", F.head_of(d))}
        # A brand word is one that, across all labels, mostly appears IN the brand
        # rather than in product descriptions: "quest", "kodiak", "chobani" -- but
        # not "cooked", "maple" or "party", which brands use too and descriptions
        # use far more.
        common = {r[0] for r in branded().execute(
            "SELECT word FROM bword GROUP BY word "
            "HAVING SUM(in_brand) >= 5 AND SUM(in_brand) * 1.0 / COUNT(*) >= 0.6")}
        # Brands whose name is also an ordinary word, so the ratio above cannot
        # find them. Kept short: each is a word that will now always be read as
        # the brand.
        always = {"built", "killer", "oscar", "mayer", "thomas", "tillamook", "cheerio",
                  "ritz", "dorito", "goldfish", "lunchable", "digiorno", "maruchan", "barilla",
                  "prego", "hot pocket", "pocket", "celsius", "gatorade", "monster", "oikos",
                  "siggi", "rxbar", "belvita", "sabra", "annie", "quaker", "clif", "oreo"}
        _brands = ({w for w in common if w not in usda} | {F.stem(w) for w in always})
    return _brands


def method(db, text):
    if F.is_vague(text):
        return {"needs_detail": True, "vague": True}
    stated, t = F.strip_protein_claim(text)
    t = F.preprocess(t)
    items = F.split_items(t)
    bw = brand_words(db)
    parts, shown, rest = [], [], []
    for item in items:
        words = F.content_words(item)
        if any(w in bw for w in words):
            count, unit, food = F.parse_quantity(F.preprocess(item))
            found = B.search(branded(), food, limit=1)
            if found:
                p = found[0]
                sv = A._label_servings(p, count, unit, None)
                parts.append(A._per_label_serving(p, sv))
                shown.append((f"{p['brand']} {p['description']}"[:50], round(A._serving_grams(p) * sv)))
                continue
        rest.append(item)
    if rest:
        off = F.offline_lookup(db, " and ".join(rest))
        if off and not off.get("vague"):
            parts.append({k: off.get(k) for k in F.COLUMNS})
            shown += off.get("matches", [])
        elif not parts:
            return off or {"needs_detail": True}
    res = A._sum(parts)
    if stated is not None:
        res["protein_g"] = stated
    res.update(needs_detail=False, matches=shown)
    return res
