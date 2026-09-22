#!/usr/bin/env python3
"""
Reference implementation of the offline food search.

This is the SAME algorithm as FoodSearch.kt in the app. It lives here so that
ranking changes can be measured (tools/bench_food_db.py) in seconds instead of
through an emulator. If you change one, change the other and re-run the bench.

The core insight, which is most of the accuracy: USDA descriptions lead with the
head noun -- "Banana, raw"; "Pie, apple"; "Oil, avocado". So a row whose head noun
is something the person did NOT say is almost always the wrong sense of the word.
Penalising that single signal is worth more than everything else combined.
"""
import re, sqlite3

STOP = {
    "i", "ate", "had", "a", "an", "the", "some", "of", "with", "and", "for",
    "my", "this", "that", "today", "then", "plus", "eating", "eat", "was",
    "were", "just", "about", "like", "bit", "little", "big", "large", "small",
}

NUM_WORDS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
             "seven": 7, "eight": 8, "nine": 9, "ten": 10, "half": 0.5,
             "a": 1, "an": 1}

UNITS = (r"(oz|ounce|ounces|g|gram|grams|lb|lbs|pound|pounds|cup|cups|tbsp|"
         r"tablespoon|tablespoons|tsp|teaspoon|teaspoons|slice|slices|piece|"
         r"pieces|serving|servings|scoop|scoops|can|cans|bottle|bottles)")


def stem(w):
    """Cheap singularisation so "eggs"/"almonds" line up with "Egg"/"Almond"."""
    if len(w) > 3 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 3 and w.endswith("es") and not w.endswith("ses"):
        return w[:-2]
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
        return w[:-1]
    return w


def parse_quantity(text):
    """-> (amount|None, unit|None, food_text). "8oz chicken" -> (8.0, "oz", "chicken")."""
    t = text.lower().strip()
    m = re.match(rf"^(\d+(?:\.\d+)?)\s*{UNITS}\b\s*(?:of\s+)?(.*)$", t)
    if m:
        return float(m.group(1)), m.group(2), m.group(3).strip()
    m = re.match(r"^(\d+(?:\.\d+)?)\s+(.*)$", t)
    if m:
        return float(m.group(1)), None, m.group(2).strip()
    parts = t.split()
    if parts and parts[0] in NUM_WORDS and len(parts) > 1:
        rest = " ".join(parts[1:])
        m2 = re.match(rf"^{UNITS}\b\s*(?:of\s+)?(.*)$", rest)
        if m2:
            return NUM_WORDS[parts[0]], m2.group(1), m2.group(2).strip()
        return NUM_WORDS[parts[0]], None, rest
    return None, None, t


def content_words(text):
    return [stem(w) for w in re.findall(r"[a-z]+", text.lower())
            if w not in STOP and len(w) > 1]


def head_of(desc):
    return desc.split(",")[0].strip().lower()


def normalise(text):
    """Alias key: lowercased content words, order-independent."""
    return " ".join(sorted(content_words(text)))


COLUMNS = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g",
           "sat_fat_g", "sodium_mg", "caffeine_mg"]

_SELECT = f"SELECT f.fdc_id, f.description, f.source, {', '.join('f.' + c for c in COLUMNS)}"


def search(db, text, limit=5, use_alias=True):
    """Ranked matches for a food phrase. Returns rows: (fdc_id, description, source, *nutrients)."""
    words = content_words(text)
    if not words:
        return []

    if use_alias:
        row = db.execute(
            f"{_SELECT} FROM alias a JOIN food f ON f.fdc_id = a.fdc_id WHERE a.term = ?",
            (normalise(text),)).fetchone()
        if row:
            # A curated or user-taught default wins outright; the whole point is
            # that the user should not have to correct the same food twice.
            rest = [r for r in _rank(db, words, limit + 1) if r[0] != row[0]]
            return [row] + rest[:limit - 1]

    return _rank(db, words, limit)


def _rank(db, words, limit):
    q = " OR ".join(f"{w}*" for w in words)
    try:
        rows = db.execute(
            f"{_SELECT}, bm25(food_fts) FROM food_fts JOIN food f ON f.fdc_id = food_fts.rowid"
            f" WHERE food_fts MATCH ? ORDER BY bm25(food_fts) LIMIT 400", (q,)).fetchall()
    except sqlite3.OperationalError:
        return []

    scored = []
    for r in rows:
        desc = r[1].lower()
        head = head_of(r[1])
        head_words = [stem(w) for w in re.findall(r"[a-z]+", head)]
        desc_words = set(stem(w) for w in re.findall(r"[a-z]+", desc))

        matched = sum(1 for w in words if w in desc_words)
        in_head = sum(1 for w in words if w in head_words)
        # The decisive term: every word in the head the person did NOT say is
        # evidence this is a different food ("Pie, apple" for "apple").
        head_extra = sum(1 for w in head_words if w not in words and len(w) > 2)

        score = (matched * 12) + (in_head * 16) - (head_extra * 14) - (len(desc) / 60.0)
        # USDA's generic base forms are exactly what a bare food name means.
        if re.search(r"\bnfs\b", desc):
            score += 6
        if re.search(r"\braw\b", desc):
            score += 3
        scored.append((score, r[:-1]))

    scored.sort(key=lambda x: -x[0])
    return [r for _, r in scored[:limit]]
