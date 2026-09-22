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
             "a": 1, "an": 1,
             # How people actually count when they are not counting.
             "couple": 2, "pair": 2, "few": 3, "several": 3, "dozen": 12}

UNITS = (r"(oz|ounce|ounces|g|gram|grams|lb|lbs|pound|pounds|cup|cups|tbsp|"
         r"tablespoon|tablespoons|tsp|teaspoon|teaspoons|slice|slices|piece|"
         r"pieces|serving|servings|scoop|scoops|can|cans|bottle|bottles)")


# "-es" is only a plural marker after a sibilant or an "o": box/boxes,
# dish/dishes, glass/glasses, potato/potatoes. Everywhere else the "e" belongs to
# the word and only the "s" is the plural: cake/cakes, apple/apples, grape/grapes.
#
# Stripping "es" everywhere turned "pancakes" into "pancak" while "pancake"
# stayed whole, so a search for "pancake" could not reach a row described as
# "Pancakes, NFS" -- it found "Pancake syrup" instead. 89 words in the USDA
# descriptions were affected, among them apples, cakes, grapes, noodles, juices,
# oranges, sausages and waffles.
PLURAL_ES = re.compile(r"(x|z|ch|sh|ss|o)es$")


def stem(w):
    """Cheap singularisation so "eggs"/"almonds" line up with "Egg"/"Almond"."""
    if len(w) > 3 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 3 and PLURAL_ES.search(w):
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


# ---------------------------------------------------------------------------
# Normalising what people actually type.
#
# Every pattern here was put in by a case from a real exported log, not by
# imagination. Dictated entries are messy in specific, repeatable ways:
# unicode fractions run into the next word ("⅓bell peper"), quantities arrive
# at the end ("carrot cake 2 servings"), and half of them open with a few words
# of throat-clearing ("I just had...", "dinner was about...").
# ---------------------------------------------------------------------------

UNICODE_FRACTIONS = {
    "\u00bd": 0.5, "\u2153": 1 / 3, "\u2154": 2 / 3, "\u00bc": 0.25, "\u00be": 0.75,
    "\u215b": 0.125, "\u215c": 0.375, "\u215d": 0.625, "\u215e": 0.875,
    "\u2155": 0.2, "\u2156": 0.4, "\u2157": 0.6, "\u2158": 0.8,
    "\u2159": 1 / 6, "\u215a": 5 / 6,
}

# Openers that carry no food meaning. Stripped before the quantity parser runs,
# because a leading "about" or "I had" hides the number behind it.
LEADING_FILLER = re.compile(
    r"^(?:i\s+(?:just\s+)?(?:had|ate|got|made)|"
    r"(?:this\s+)?(?:morning|afternoon|evening)\s+i\s+had|"
    r"(?:for\s+)?(?:breakfast|lunch|dinner|supper)\s+(?:i\s+had|was)|"
    r"today\s+i\s+had|"
    r"about|approximately|roughly|around|like|maybe|"
    r"my|the|a\s+bit\s+of|a\s+little\s+bit\s+of|a\s+little)\s+",
    re.I,
)

# "carrot cake 2 servings" -- the count trails the food instead of leading it.
TRAILING_QUANTITY = re.compile(
    rf"^(.*?)[,\s]+(\d+(?:\.\d+)?)\s*{UNITS}s?\b\.?$", re.I)

# "a cup and 1/4", "tablespoon and a half" -- a fraction bolted onto the unit.
ARTICLE_THEN_COUNT = re.compile(r"\ban?\s+(couple|pair|few|several|dozen)\b", re.I)
LEADING_NUMBER = re.compile(rf"^(?:\d|(?:{'|'.join(NUM_WORDS)})\b)", re.I)
INLINE_QUANTITY = re.compile(rf"\b(\d+(?:\.\d+)?)\s*{UNITS}\b", re.I)

AND_A_HALF = re.compile(r"\b(\d+(?:\.\d+)?)\s+and\s+(?:a\s+)?half\b", re.I)
UNIT_AND_A_HALF = re.compile(rf"\b(?:a\s+)?{UNITS}\s+and\s+(?:a\s+)?half\b", re.I)
UNIT_AND_FRACTION = re.compile(rf"\b(?:a\s+)?{UNITS}\s+and\s+(\d+(?:\.\d+)?)\b", re.I)
SLASH_FRACTION = re.compile(r"\b(\d+)\s*/\s*(\d+)\b")
# "half a serving", "two a slice" -- the article between count and unit is noise,
# but it stops the quantity parser from seeing them as one phrase.
NUM_A_UNIT = re.compile(
    rf"\b({'|'.join(NUM_WORDS)}|\d+(?:\.\d+)?)\s+an?\s+{UNITS}\b", re.I)


def preprocess(text):
    """Text in, cleaner text out. Safe to call more than once."""
    t = text.strip()

    # "\u2153bell peper" -> "0.333 bell peper". Spaces on both sides because the
    # glyph is regularly typed flush against the next word.
    for glyph, value in UNICODE_FRACTIONS.items():
        if glyph in t:
            t = t.replace(glyph, f" {value:.4g} ")

    # Order matters: "1/4" has to become 0.25 before UNIT_AND_FRACTION looks for
    # the number after "and", or "a cup and 1/4" collapses to "2 cup/4".
    t = SLASH_FRACTION.sub(
        lambda m: f"{float(m.group(1)) / float(m.group(2)):.4g}"
        if float(m.group(2)) else m.group(0), t)
    # "a cup and 1/4 of it" -> "1.25 cup of it"; "tablespoon and a half" -> "1.5 tablespoon".
    t = UNIT_AND_A_HALF.sub(lambda m: f"1.5 {m.group(1)}", t)
    t = UNIT_AND_FRACTION.sub(
        lambda m: f"{1 + _num(m.group(2)):.4g} {m.group(1)}", t)
    t = AND_A_HALF.sub(lambda m: f"{float(m.group(1)) + 0.5:.4g}", t)
    # "half a serving" -> "half serving", so the quantity parser sees the unit.
    t = NUM_A_UNIT.sub(lambda m: f"{m.group(1)} {m.group(2)}", t)
    # "a couple of eggs" -> "couple of eggs". The article was being read as the
    # count of one, and the word that carried the actual number was skipped.
    t = ARTICLE_THEN_COUNT.sub(lambda m: m.group(1), t)

    t = re.sub(r"\s+", " ", t).strip()
    while True:
        stripped = LEADING_FILLER.sub("", t, count=1).strip()
        if stripped == t or not stripped:
            break
        t = stripped

    # Move a count to the front so the one quantity parser sees it. People put it
    # wherever: "carrot cake 2 servings", "milk 2 cups whole milk".
    m = TRAILING_QUANTITY.match(t)
    if m and content_words(m.group(1)):
        return f"{m.group(2)} {m.group(3)} {m.group(1).rstrip('.,')}"
    if not LEADING_NUMBER.match(t):
        m = INLINE_QUANTITY.search(t)
        if m:
            rest = (t[:m.start()] + " " + t[m.end():]).strip()
            if content_words(rest):
                return f"{m.group(1)} {m.group(2)} {re.sub(r'\s+', ' ', rest)}"
    return t


def _num(s):
    try:
        return float(s)
    except ValueError:
        return 0.0


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


# Forms that are an ingredient or a stage, not a meal.
UNEATEN = re.compile(r"\b(dry mix|unprepared|dehydrated|condensed, undiluted|"
                     r"reconstituted|not reconstituted|mix, dry)\b")

CANDIDATES = 120


def candidate_sql(words, prefix_last=False):
    """
    Candidate shortlist from the hand-built inverted index.

    NOT FTS5: Android's system SQLite has no fts5 module, so the app cannot use
    it and neither does this, or the benchmark would measure something that
    never ships. Rows are shortlisted by how many query words they contain, and
    the real ranking happens in score() / FoodQuery.score().

    prefix_last exists for type-ahead, where the final word is half-typed
    ("chick" -> chicken). It is off by default because measuring it showed
    prefix matching buys NO accuracy on complete phrases (39/42 and 34/34 either
    way) while costing about 3.5x the time -- p50 3.5 ms exact vs 12.2 ms. So the
    app pays for it only on the screen that actually needs it.
    """
    terms = list(words)
    ors = " OR ".join(["w.word = ?"] * len(terms))
    params = list(terms)
    if prefix_last and terms:
        ors += " OR w.word LIKE ?"
        params.append(terms[-1] + "%")
    # The LENGTH tie-break is load-bearing, not cosmetic. For a single-word query
    # every candidate scores exactly one hit, so ordering by hits alone leaves the
    # LIMIT to truncate arbitrarily -- and "Egg, whole, raw" was being cut before
    # ranking ever saw it, leaving "egg" to resolve to "Egg burrito". USDA's
    # generic forms have the shortest descriptions, so length is a good proxy for
    # "the plain version of this food".
    #
    # It costs one match case: "tuna sandwich" degrades from "Tuna salad sandwich
    # wrap" to "Sandwich, NFS". That is worth fixing egg, eggs and milk.
    sql = (f"SELECT w.fdc_id FROM word_index w JOIN food f ON f.fdc_id = w.fdc_id"
           f" WHERE {ors} GROUP BY w.fdc_id"
           f" ORDER BY COUNT(DISTINCT w.word) DESC, LENGTH(f.description) ASC"
           f" LIMIT {CANDIDATES}")
    return sql, params


def _rank(db, words, limit):
    sql, params = candidate_sql(words)
    try:
        ids = [r[0] for r in db.execute(sql, params).fetchall()]
    except sqlite3.OperationalError:
        return []
    if not ids:
        return []
    rows = db.execute(
        f"{_SELECT} FROM food f WHERE f.fdc_id IN ({','.join('?' * len(ids))})", ids).fetchall()

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

        score = (matched * 12) + (in_head * 16) - (head_extra * 16) - (len(desc) / 60.0)
        # English puts the head noun LAST and USDA puts it FIRST: "raspberry jam"
        # is a jam, "apple pie" is a pie, "bell pepper" is a pepper. So the final
        # word of the query is the best single clue to what the food actually IS,
        # and a row whose head noun agrees with it is usually the right sense.
        # Without this, "raspberry jam" matched "Raspberries, raw".
        if words[-1] in head_words:
            score += 10
        # USDA's generic base forms are exactly what a bare food name means.
        if re.search(r"\bnfs\b", desc):
            score += 6
        if re.search(r"\braw\b", desc):
            score += 3
        # Ingredients nobody eats as such. "carrot cake" has no cooked row in
        # USDA, so it landed on "Cake, pudding-type, carrot, DRY MIX" and two
        # servings came to 4233 calories. Same family of error as raw rice: the
        # row is real, it is just not what a person means when they say they ate
        # something.
        if UNEATEN.search(desc):
            score -= 20
        # FNDDS ("survey") rows describe food AS EATEN and carry realistic
        # serving sizes; SR Legacy rows describe ingredients and often default to
        # a cup. Where both exist under the same name -- 81 descriptions do --
        # the tie was broken arbitrarily, and "greek yogurt with honey" drew the
        # SR Legacy honey with its 339 g cup: 1104 calories.
        if r[2] == "survey_fndds_food":
            score += 2
        scored.append((score, r))

    scored.sort(key=lambda x: -x[0])
    return [r for _, r in scored[:limit]]


# ---------------------------------------------------------------------------
# The rest of the logging pipeline.
#
# These mirror FoodQuery.kt and EntryRepository.offlineLookup(). They live here
# so a change can be measured against the real log (tools/bench_real_log.py) in
# seconds. Keep them in step with the Kotlin, or the benchmark stops describing
# the app.
# ---------------------------------------------------------------------------

MEAL_WORDS = {
    "breakfast", "lunch", "dinner", "supper", "brunch", "meal", "snack",
    "leftover", "usual", "food", "something", "stuff", "everything", "dessert",
}

VAGUE_MODIFIERS = {
    "solid", "good", "quick", "light", "heavy", "proper", "decent", "normal",
    "regular", "usual", "healthy", "nice", "full", "whole", "lot", "loads",
}

# "&" splits items. It also splits "PB&J" into "PB" and "J" -- which turns out to
# be harmless and even useful: the one-letter "J" is dropped as noise and "pb"
# reaches the curated PB&J alias. Narrowing this to a spaced "&" was measured and
# was WORSE, because "pb&j black currant" then lost the sandwich entirely.
ITEM_SEPARATORS = re.compile(r"\s*(?:,|\band\b|\bplus\b|\+|&)\s*")

STATED_PROTEIN = [
    re.compile(r"(\d+(?:\.\d+)?)\s*(?:g|gram|grams)\s*(?:of\s+)?protein"),
    re.compile(r"protein\s*[:=]?\s*(\d+(?:\.\d+)?)\s*(?:g|gram|grams)?"),
]


def is_vague(text):
    words = content_words(text)
    if not words:
        return True
    return all(stem(w) in MEAL_WORDS or w in VAGUE_MODIFIERS for w in words)


def split_items(text):
    parts = [p.strip() for p in ITEM_SEPARATORS.split(text.strip())]
    parts = [p for p in parts if p and content_words(p)]
    return parts if len(parts) > 1 else [text.strip()]


WITH_SEPARATOR = re.compile(r"\s+(?:with|on top of|topped with|and\s+some)\s+", re.I)


def split_on_with(items):
    """
    A second, more aggressive reading of the same text.

    "with" cannot be split unconditionally -- it would tear "spaghetti with meat
    sauce" and "tuna sandwich on wheat" into nonsense. But refusing to split it
    was losing an entire food: "two pancakes with a little bit of maple syrup"
    matched the SYRUP and logged 431 calories of nothing but syrup, and "one
    piece of bread with a serving of cream cheese" lost the bread.

    So produce both readings and let coverage decide which one the person meant.
    """
    out = []
    for item in items:
        pieces = [p.strip() for p in WITH_SEPARATOR.split(item) if p.strip()]
        out.extend(p for p in pieces if content_words(p))
    return out or items


def stated_protein_grams(text):
    low = text.lower()
    for pat in STATED_PROTEIN:
        m = pat.search(low)
        if m:
            v = float(m.group(1))
            if 0 < v < 500:
                return v
    return None


# Foods that are sold and described BY their protein content. For these, a
# leading "17g" is a protein claim, not a serving weight -- nobody eats 17 grams
# of protein bar. This list is short on purpose: "15g butter" really is a weight,
# so only nouns that are routinely marketed this way belong here.
PROTEIN_CLAIM_NOUNS = {"bar", "shake", "drink", "smoothie", "yogurt", "powder"}

GRAMS_PREFIX = re.compile(r"^(\d+(?:\.\d+)?)\s*(?:g|grams?)\b\s*(?:of\s+)?", re.I)
GRAMS_OF_PROTEIN = re.compile(
    r"\b(\d+(?:\.\d+)?)\s*(?:g|grams?)\s*(?:of\s+)?protein\b", re.I)


def strip_protein_claim(text):
    """
    -> (stated_protein_grams | None, text with the claim's NUMBER removed).

    "20g protein shake" used to parse as twenty grams OF protein powder: the
    protein came out right at 20 g and the calories came out at 70 instead of
    160, because the app weighed the shake as if it were a rounding error. The
    number has to be taken out of the text, not just read from it.
    """
    m = GRAMS_OF_PROTEIN.search(text)
    if m:
        return float(m.group(1)), (text[:m.start()] + "protein " + text[m.end():]).strip()

    m = GRAMS_PREFIX.match(text)
    if m:
        rest = text[m.end():]
        if any(stem(w) in PROTEIN_CLAIM_NOUNS for w in content_words(rest)):
            return float(m.group(1)), rest.strip()

    # "protein: 16 g" and other orderings -- read it, but leave the text alone;
    # these do not sit where the quantity parser would trip over them.
    for pat in STATED_PROTEIN:
        m = pat.search(text.lower())
        if m:
            v = float(m.group(1))
            if 0 < v < 500:
                return v, text
    return None, text


def coverage(query_words, description):
    if not query_words:
        return 0.0
    desc_words = set(stem(w) for w in re.findall(r"[a-z]+", description.lower()))
    return sum(1 for w in query_words if w in desc_words) / len(query_words)


# USDA lists portions BY SIZE -- "1 small pancake" 20 g, "1 medium pancake" 50 g,
# "1 large pancake" 90 g. The size words are stop words for SEARCHING (nobody
# wants "small" to steer which food is found), but throwing them away entirely
# meant "a small cookie" was logged as a 60 g cookie at 295 calories.
SIZE_WORDS = ["extra large", "jumbo", "large", "medium", "regular",
              "small", "miniature", "mini"]


def size_of(text):
    low = text.lower()
    for s in SIZE_WORDS:
        if re.search(rf"\b{s}\b", low):
            return s
    return None


GRAM_UNITS = {"g", "gram", "grams"}
OUNCE_UNITS = {"oz", "ounce", "ounces"}


def portions_for(db, fdc_id):
    return db.execute(
        "SELECT label, grams FROM portion WHERE fdc_id = ? ORDER BY grams LIMIT 12",
        (fdc_id,)).fetchall()


def grams_for(db, row, parsed, text=""):
    """Mirrors EntryRepository.gramsFor()."""
    count, unit, food = parsed
    count = count if count is not None else 1.0
    if unit in GRAM_UNITS:
        return count
    if unit in OUNCE_UNITS:
        return count * 28.3495

    portions = portions_for(db, row[0])
    if unit:
        singular = unit.rstrip("s")
        named = [(l.lower(), g) for l, g in portions if singular in l.lower()]
        if named:
            # Portions come back smallest first, so taking the first match meant
            # "4 slices of white bread" used a 10 g "1 slice, snack-size".
            return count * _plainest(named)

    # "a small cookie", "1 large pancake" -- a size the person named beats any
    # default, exactly as a unit would.
    size = size_of(text or food)
    if size:
        for label, grams in portions:
            if size in label.lower():
                return count * grams

    # A bare count of a countable food means that many OF IT, not that many
    # servings: "six pancakes" was logged as six 100 g servings, 1692 calories.
    # USDA usually lists the item itself as a portion ("1 medium pancake", 50 g),
    # so prefer that over the serving default whenever a count was given.
    default = db.execute("SELECT default_grams FROM food WHERE fdc_id=?",
                         (row[0],)).fetchone()[0] or 100.0
    if parsed[0] is not None and not unit:
        item = _item_portion(portions, row[1])
        # A quarter of the serving is the floor. USDA lists "1 chip" (2 g) for
        # potato chips, so "a bag of potato chips" came out at ELEVEN calories.
        # A real item portion is a sizeable fraction of a serving; a crumb is
        # USDA being thorough, not an answer to what someone ate.
        if item is not None and item >= default * 0.25:
            return count * item
    return count * default


def _item_portion(portions, description):
    """A portion that is one of the food itself, preferring the medium one."""
    head = head_of(description)
    nouns = {stem(w) for w in re.findall(r"[a-z]+", head) if len(w) > 2}
    if not nouns:
        return None
    named = []
    for label, grams in portions:
        low = label.lower()
        words = re.findall(r"[a-z]+", low)
        if not any(stem(w) in nouns for w in words):
            continue
        # "1 medium pancake" describes one pancake. "1 Bagel Thin" describes a
        # different product that happens to say bagel, and taking it made "a
        # bagel with cream cheese" a 46 g bagel. Accept only a size-qualified
        # portion or the bare "1 <food>".
        sized = any(s in low for s in ("medium", "regular", "small", "large"))
        bare = len([w for w in words if stem(w) not in nouns]) == 0
        if sized or bare:
            named.append((low, grams))
    if not named:
        return None
    return _plainest(named)


def _plainest(named):
    """
    The default-sized portion out of several labelled ones.

    USDA offers "1 slice, snack-size" (10 g) through "1 large or thick slice"
    (43 g) for the same bread. "Medium"/"regular" is USDA's own word for the
    normal one; failing that the shortest label is the least qualified.
    """
    for want in ("medium", "regular"):
        for label, grams in named:
            if want in label:
                return grams
    return min(named, key=lambda p: len(p[0]))[1]


def is_alias(db, text):
    return db.execute("SELECT 1 FROM alias WHERE term = ?",
                      (normalise(text),)).fetchone() is not None


def _resolve(db, items):
    """Look each item up, dropping the ones whose match is not credible."""
    found = []
    for item in items:
        parsed = parse_quantity(preprocess(item))
        hits = search(db, parsed[2], limit=1)
        if not hits:
            continue
        # A curated alias is authoritative by construction, and deliberately
        # points at rows whose head noun is nothing like the query --
        # "protein shake" -> "Nutritional powder mix, protein, NFS". Credibility
        # is a check on RANKING, so it must not second-guess curation.
        if not is_alias(db, parsed[2]) and not _credible(parsed[2], hits[0][1]):
            continue
        found.append((hits[0], grams_for(db, hits[0], parsed, item)))
    return found


def _credible(query_text, description):
    """
    Does this row even claim to be the kind of thing that was named?

    Splitting a sentence produces fragments that are not food at all -- "medium
    sized.", "1/4 of it", "medium frosty" -- and every one of them still found a
    nearest row, so the app added calories for food nobody ate: raw rice for
    "medium sized", Reese's cups for "1/4 of it". Requiring the match to share a
    word with what was asked for, in its HEAD noun, throws all of those out
    while keeping every real match, because USDA leads with the head noun.
    """
    words = content_words(query_text)
    if not words:
        return False
    head_words = {stem(w) for w in re.findall(r"[a-z]+", head_of(description))}
    return any(w in head_words for w in words)


def _explained(found, all_words):
    """How much of the original sentence a reading accounts for, 0..1."""
    if not found:
        return 0.0
    covered = set()
    for row, _ in found:
        desc_words = {stem(w) for w in re.findall(r"[a-z]+", row[1].lower())}
        covered |= {w for w in all_words if w in desc_words}
    return len(covered) / len(all_words) if all_words else 0.0


def offline_lookup(db, raw_text):
    """
    Mirrors EntryRepository.offlineLookup(). Returns a dict of the same shape the
    app would store, or None when nothing matched at all.
    """
    if is_vague(raw_text):
        return {"name": raw_text, "needs_detail": True, "vague": True,
                "matches": [], **{k: None for k in COLUMNS}}

    # The claim comes out of the RAW text: preprocess moves a stray "6 g" to the
    # front, which would split "6 g of protein" apart before it could be read.
    stated, text = strip_protein_claim(raw_text)
    text = preprocess(text)

    plain = [text] if is_alias(db, text) else split_items(text)
    readings = [_resolve(db, plain)]
    widened = split_on_with(plain)
    if widened != plain:
        readings.append(_resolve(db, widened))

    # Whichever reading accounts for more of what was actually said. Ties go to
    # the first, which is the conservative one that did not split on "with".
    all_words = content_words(text)
    found = max(readings, key=lambda f: _explained(f, all_words))
    if not found:
        return None

    totals = {k: 0.0 for k in COLUMNS}
    seen = {k: False for k in COLUMNS}
    for row, grams in found:
        for i, k in enumerate(COLUMNS):
            v = row[3 + i]
            if v is not None:
                totals[k] += v * grams / 100.0
                seen[k] = True

    if stated is not None:
        totals["protein_g"], seen["protein_g"] = stated, True

    weak = False
    if len(found) == 1:
        weak = coverage(content_words(parse_quantity(text)[2]),
                        found[0][0][1]) < 0.5

    return {
        "name": found[0][0][1] if len(found) == 1 else raw_text,
        "needs_detail": weak,
        "vague": False,
        "stated": stated is not None,
        "matches": [(r[1], round(g)) for r, g in found],
        **{k: (totals[k] if seen[k] else None) for k in COLUMNS},
    }
