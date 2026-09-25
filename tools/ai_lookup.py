#!/usr/bin/env python3
"""
AI lookup methods for tools/bench_truth.py.

  ai_v1     -- the prompt the app ships today (OpenAiBackend.kt): one call, the
               model states every total itself.
  ai_items  -- one call, but the model itemises: each food with its weight and
               its nutrients per 100 g. The code does the multiplication and the
               sum. Tests whether arithmetic alone is the problem.
  ai_ground -- the model itemises and estimates weights; each generic food is
               then looked up in the bundled USDA database and the model picks
               the matching row from real candidates. Nutrient DENSITY comes from
               USDA measurements, not from the model's memory. Branded and
               restaurant food keeps the model's label figures, because USDA
               cannot know them.

The key is read from OPENAI_API_KEY and never written anywhere.
"""
import json, os, re, sys, time, urllib.error, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import foodsearch as F

MODEL = os.environ.get("NS_MODEL", "gpt-5.6-luna")
COLUMNS = F.COLUMNS
URL = "https://api.openai.com/v1/chat/completions"


def _key():
    k = os.environ.get("OPENAI_API_KEY", "").strip()
    if not k:
        raise SystemExit("set OPENAI_API_KEY")
    return k


def chat(messages, schema=None, max_tokens=4000, effort=None, timeout=120):
    body = {"model": MODEL, "max_completion_tokens": max_tokens, "messages": messages}
    if effort == "none" or MODEL.startswith("gpt-4"):
        # Same words, same answer. Only accepted with reasoning off; measured on
        # the real log, repeat requests differed by >15% on 1 entry in 5 without it.
        body["temperature"] = 0
        body["seed"] = 7
    if schema:
        body["response_format"] = {"type": "json_schema",
                                   "json_schema": {"name": "out", "strict": True, "schema": schema}}
    else:
        body["response_format"] = {"type": "json_object"}
    if effort:
        body["reasoning_effort"] = effort
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={
        "Authorization": f"Bearer {_key()}", "Content-Type": "application/json"})
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                payload = json.load(r)
            content = payload["choices"][0]["message"]["content"]
            return json.loads(content), payload.get("usage", {})
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}: {e.read()[:300].decode('utf-8', 'replace')}"
            if e.code not in (429, 500, 502, 503):
                break
        except Exception as e:  # timeouts, bad JSON
            last = f"{type(e).__name__}: {e}"
        time.sleep(2 + 3 * attempt)
    raise RuntimeError(last)


# --------------------------------------------------------------------------
# ai_v1: exactly what the app sends today.
# --------------------------------------------------------------------------
import compare_ai_offline as _V1  # noqa: E402

V1_MAP = {"calories": "kcal", "protein_grams": "protein_g", "carbs_grams": "carbs_g",
          "fat_grams": "fat_g", "fiber_grams": "fiber_g", "sugar_grams": "sugar_g",
          "saturated_fat_grams": "sat_fat_g", "sodium_mg": "sodium_mg",
          "caffeine_mg": "caffeine_mg"}


def ai_v1(db, text):
    t0 = time.time()
    try:
        out, usage = chat([{"role": "system", "content": _V1.SYSTEM},
                           {"role": "user", "content": text}], max_tokens=2000)
    except RuntimeError as e:
        return {"error": str(e)}
    res = {V1_MAP[k]: out.get(k) for k in V1_MAP}
    res.update(needs_detail=bool(out.get("needs_detail")), name=out.get("name"),
               seconds=round(time.time() - t0, 1), tokens=usage.get("total_tokens"))
    return res


# --------------------------------------------------------------------------
# Shared pieces of the itemised methods.
# --------------------------------------------------------------------------
NUTRIENT_PROPS = {k: {"type": ["number", "null"]} for k in COLUMNS}


def _obj(props, required=None):
    return {"type": "object", "properties": props,
            "required": required or list(props), "additionalProperties": False}


ITEM_RULES = """\
Rules for reading the description:
- One item per distinct food actually eaten. A sandwich is ONE item only if it is \
a single known thing (a Big Mac); a homemade sandwich is its parts (bread, filling, \
spread), because the parts are what decide the numbers.
- Never count the same food twice. "A PB&J with 2 tbsp of peanut butter" is bread + \
2 tbsp peanut butter + jelly, not a sandwich plus peanut butter.
- grams is the edible weight actually eaten, as eaten (cooked weight for cooked \
food). Convert the stated amount: 1 cup cooked rice = 158 g, 1 cup milk = 244 g, \
1 tbsp peanut butter = 16 g, 1 tbsp oil = 13.5 g, 1 large egg = 50 g, 1 slice \
bread = 28-32 g, 1 oz = 28.35 g. With no amount, use one ordinary serving of that \
food as a person would eat it (a medium banana 118 g, one chicken breast 170 g).
- "a little bit of", "a splash" = a small amount (about a tablespoon), not a serving.
- Fat level words matter: "0%", "nonfat", "2%", "4%", "85/15", "lean". Put them in \
the search terms.
- kind is "brand" for a packaged product named by brand, "restaurant" for an item \
from a named chain, otherwise "generic". Unbranded packaged staples (bread, milk, \
eggs) are generic.
- For brand and restaurant items give label_nutrients: the published nutrition \
facts for the WHOLE amount eaten, from the maker's own figures. For generic items \
label_nutrients is all null.
- stated_nutrients: numbers the person said themselves for this item ("a 20 g \
protein yogurt", "bread which was 6 g of protein"), for the whole amount eaten; \
null where they said nothing. A stated number always wins over any estimate.
- needs_detail is true only when the description names no identifiable food at \
all ("a solid breakfast", "leftovers", "the usual") - then return no items.\
"""


def _sum(parts):
    tot = {k: None for k in COLUMNS}
    for p in parts:
        for k in COLUMNS:
            v = p.get(k)
            if v is not None:
                tot[k] = (tot[k] or 0.0) + v
    return tot


# --------------------------------------------------------------------------
# ai_items: itemise, model supplies per-100 g density, code multiplies.
# --------------------------------------------------------------------------
ITEMS_SCHEMA = _obj({
    "needs_detail": {"type": "boolean"},
    "items": {"type": "array", "items": _obj({
        "name": {"type": "string"},
        "kind": {"type": "string", "enum": ["generic", "brand", "restaurant"]},
        "grams": {"type": "number"},
        "per_100g": _obj(NUTRIENT_PROPS),
        "label_nutrients": _obj(NUTRIENT_PROPS),
        "stated_nutrients": _obj(NUTRIENT_PROPS),
    })},
})

ITEMS_SYSTEM = f"""You turn a plain-language description of food someone ate into \
structured data for a nutrition log. Accuracy is what matters most.

{ITEM_RULES}
- per_100g: nutrients per 100 g of the food as eaten, from USDA FoodData Central \
where the food is generic. kcal, grams for macros, mg for sodium and caffeine."""


def _item_nutrients(it, per100):
    stated = it.get("stated_nutrients") or {}
    label = it.get("label_nutrients") or {}
    out = {}
    for k in COLUMNS:
        if stated.get(k) is not None:
            out[k] = stated[k]
        elif it.get("kind") != "generic" and label.get(k) is not None:
            out[k] = label[k]
        elif per100 and per100.get(k) is not None:
            out[k] = per100[k] * it["grams"] / 100.0
        elif label.get(k) is not None:
            out[k] = label[k]
        else:
            out[k] = None
    return out


def ai_items(db, text):
    t0 = time.time()
    try:
        out, usage = chat([{"role": "system", "content": ITEMS_SYSTEM},
                           {"role": "user", "content": text}], schema=ITEMS_SCHEMA)
    except RuntimeError as e:
        return {"error": str(e)}
    parts = [_item_nutrients(it, it.get("per_100g")) for it in out["items"]]
    res = _sum(parts)
    res.update(needs_detail=out["needs_detail"] or not out["items"],
               items=[{"name": i["name"], "kind": i["kind"], "grams": round(i["grams"])}
                      for i in out["items"]],
               seconds=round(time.time() - t0, 1), tokens=usage.get("total_tokens"))
    return res


# --------------------------------------------------------------------------
# ai_ground: itemise, then choose among real USDA rows.
# --------------------------------------------------------------------------
PARSE_SCHEMA = _obj({
    "needs_detail": {"type": "boolean"},
    "items": {"type": "array", "items": _obj({
        "name": {"type": "string"},
        "kind": {"type": "string", "enum": ["generic", "brand", "restaurant"]},
        "search_terms": {"type": "array", "items": {"type": "string"}},
        "amount_said": {"type": "string"},
        "grams": {"type": "number"},
        "per_100g": _obj(NUTRIENT_PROPS),
        "label_nutrients": _obj(NUTRIENT_PROPS),
        "stated_nutrients": _obj(NUTRIENT_PROPS),
    })},
})

PARSE_SYSTEM = f"""You turn a plain-language description of food someone ate into \
structured data for a nutrition log. Accuracy is what matters most.

{ITEM_RULES}
- search_terms: two or three short phrases for finding the food in the USDA \
FoodData Central database, most specific first, in USDA's vocabulary: \
"chicken breast roasted", "rice white cooked", "cottage cheese creamed", \
"yogurt greek plain nonfat", "beef ground 85% lean patty". Include how it was \
cooked when said or obvious. Give search terms for every item, brand ones too \
(for the generic equivalent).
- per_100g: your best figures per 100 g as eaten. Used only if no database row \
fits, so keep them honest."""

CHOOSE_SCHEMA = _obj({
    "choices": {"type": "array", "items": _obj({
        "item": {"type": "integer"},
        "fdc_id": {"type": ["integer", "null"]},
        "grams": {"type": "number"},
    })},
})

CHOOSE_SYSTEM = """You are matching foods someone ate to rows of the USDA FoodData \
Central database. For each item you get the person's words, an estimated weight, \
and candidate rows with their nutrients per 100 g and USDA's portion weights.

For each item:
- fdc_id: the candidate that is the same food in the same form (cooked vs raw, \
fat level, with or without added fat or sauce). Prefer the plain form unless the \
person said otherwise. null if no candidate is that food - a wrong row is worse \
than none.
- grams: the weight eaten. Where the person gave a household measure (cup, tbsp, \
slice, "a medium one"), use the chosen row's own portion weight for it. Otherwise \
keep the estimate.
Return one choice per item, in order."""


def _candidates(db, terms, limit=6):
    seen, rows = set(), []
    for t in terms:
        for r in F.search(db, t, limit=limit):
            if r[0] not in seen:
                seen.add(r[0])
                rows.append(r)
    return rows[:10]


def _row_nutrients(row, grams):
    return {k: (row[3 + i] * grams / 100.0 if row[3 + i] is not None else None)
            for i, k in enumerate(COLUMNS)}


def ai_ground(db, text):
    t0 = time.time()
    tokens = 0
    try:
        parsed, usage = chat([{"role": "system", "content": PARSE_SYSTEM},
                              {"role": "user", "content": text}], schema=PARSE_SCHEMA)
    except RuntimeError as e:
        return {"error": str(e)}
    tokens += usage.get("total_tokens", 0)
    items = parsed["items"]
    if parsed["needs_detail"] or not items:
        return {"needs_detail": True, **{k: None for k in COLUMNS},
                "seconds": round(time.time() - t0, 1), "tokens": tokens}

    cands = {}
    brief = []
    for i, it in enumerate(items):
        if it["kind"] != "generic":
            continue
        rows = _candidates(db, it["search_terms"])
        cands[i] = {r[0]: r for r in rows}
        brief.append({
            "item": i, "words": it["name"], "amount_said": it["amount_said"],
            "estimated_grams": it["grams"],
            "candidates": [{
                "fdc_id": r[0], "description": r[1],
                "per_100g": {"kcal": r[3], "protein_g": r[4], "carbs_g": r[5], "fat_g": r[6]},
                "portions": [f"{l} = {g:g} g" for l, g in F.portions_for(db, r[0])][:8],
            } for r in rows],
        })

    choices = {}
    if brief:
        try:
            chosen, usage = chat([{"role": "system", "content": CHOOSE_SYSTEM},
                                  {"role": "user", "content": json.dumps(
                                      {"description": text, "items": brief})}],
                                 schema=CHOOSE_SCHEMA)
        except RuntimeError as e:
            return {"error": str(e)}
        tokens += usage.get("total_tokens", 0)
        choices = {c["item"]: c for c in chosen["choices"]}

    parts, shown = [], []
    for i, it in enumerate(items):
        c = choices.get(i)
        row = cands.get(i, {}).get(c["fdc_id"]) if c and c["fdc_id"] else None
        if row is not None:
            grams = c["grams"]
            base = _row_nutrients(row, grams)
            n = {k: (it["stated_nutrients"][k] if it["stated_nutrients"].get(k) is not None
                     else base[k]) for k in COLUMNS}
            shown.append({"name": row[1], "grams": round(grams), "kind": "usda"})
        else:
            # No USDA row fits (or a brand): the model's own label figures.
            n = _item_nutrients(it, it.get("per_100g"))
            shown.append({"name": it["name"], "grams": round(it["grams"]), "kind": it["kind"]})
        parts.append(n)

    res = _sum(parts)
    res.update(needs_detail=False, items=shown,
               seconds=round(time.time() - t0, 1), tokens=tokens)
    return res


# --------------------------------------------------------------------------
# ai_label: ai_ground plus the branded label database. A named product is
# matched to its real Nutrition Facts, and the model only has to say WHICH
# product and HOW MANY of the label's servings -- the two things it is good
# at -- while the numbers come off the label.
# --------------------------------------------------------------------------
import brandsearch as B  # noqa: E402

_bdb = None


def _branded():
    global _bdb
    if _bdb is None:
        import sqlite3
        _bdb = sqlite3.connect(os.environ.get("NS_BRANDED_DB", B.DEFAULT_DB))
    return _bdb


LABEL_PARSE_SCHEMA = _obj({
    "needs_detail": {"type": "boolean"},
    "items": {"type": "array", "items": _obj({
        "name": {"type": "string"},
        "kind": {"type": "string", "enum": ["generic", "brand", "restaurant"]},
        "brand": {"type": ["string", "null"]},
        "product_search": {"type": ["string", "null"]},
        "search_terms": {"type": "array", "items": {"type": "string"}},
        "amount_said": {"type": "string"},
        "grams": {"type": "number"},
        "per_100g": _obj(NUTRIENT_PROPS),
        "label_nutrients": _obj(NUTRIENT_PROPS),
        "stated_nutrients": _obj(NUTRIENT_PROPS),
    })},
})

LABEL_PARSE_SYSTEM = PARSE_SYSTEM + """
- brand: the brand as a shopper would say it ("Premier Protein", "Quaker", "Ritz"), \
null for generic food. product_search: brand plus product words for finding the \
package in a label database ("premier protein chocolate shake", "quaker chewy \
chocolate chip granola bar"), null for generic food."""

LABEL_CHOOSE_SCHEMA = _obj({
    "choices": {"type": "array", "items": _obj({
        "item": {"type": "integer"},
        "fdc_id": {"type": ["integer", "null"]},
        "servings": {"type": ["number", "null"]},
        "grams": {"type": ["number", "null"]},
    })},
})

LABEL_CHOOSE_SYSTEM = """You are matching foods someone ate to database rows. Items \
come in two kinds.

GENERIC items get USDA candidates with nutrients per 100 g and USDA's portion \
weights. Choose the same food in the same form (cooked vs raw, fat level, with or \
without added fat or sauce), preferring the plain form unless the person said \
otherwise. Give grams eaten, using the row's own portion weight for any household \
measure the person used. Set servings null.

BRAND items get candidate packages with their label serving and nutrition per \
serving. Choose the product the person named; when several flavours or sizes fit \
equally, choose the most ordinary one (original flavour, standard size). Give \
servings = how many LABEL servings were eaten: if the label serving is "2 cookies" \
and they ate 3 cookies, servings = 1.5; "a bar" when the serving is 1 bar is 1. \
Set grams null.

fdc_id null if no candidate is that food -- a wrong row is worse than none. Return \
one choice per item, in order."""


def ai_label(db, text):
    t0 = time.time()
    tokens = 0
    try:
        parsed, usage = chat([{"role": "system", "content": LABEL_PARSE_SYSTEM},
                              {"role": "user", "content": text}], schema=LABEL_PARSE_SCHEMA)
    except RuntimeError as e:
        return {"error": str(e)}
    tokens += usage.get("total_tokens", 0)
    items = parsed["items"]
    if parsed["needs_detail"] or not items:
        return {"needs_detail": True, **{k: None for k in COLUMNS},
                "seconds": round(time.time() - t0, 1), "tokens": tokens}

    usda, brands, brief = {}, {}, []
    for i, it in enumerate(items):
        if it["kind"] == "brand" and it.get("product_search"):
            found = B.search(_branded(), it["product_search"], limit=8, brand=it.get("brand"))
            if not found:
                found = B.search(_branded(), it["product_search"], limit=8)
            brands[i] = {p["fdc_id"]: p for p in found}
            brief.append({"item": i, "kind": "brand", "words": it["name"],
                          "amount_said": it["amount_said"], "candidates": [{
                              "fdc_id": p["fdc_id"], "brand": p["brand"],
                              "product": p["description"],
                              "label_serving": f"{p['household'] or ''} ({p['serving']})",
                              "per_serving": {k: (round(v, 1) if v is not None else None)
                                              for k, v in B.per_serving(p).items()
                                              if k in ("kcal", "protein_g", "carbs_g", "fat_g")},
                          } for p in found]})
        elif it["kind"] == "generic":
            rows = _candidates(db, it["search_terms"])
            usda[i] = {r[0]: r for r in rows}
            brief.append({"item": i, "kind": "generic", "words": it["name"],
                          "amount_said": it["amount_said"], "estimated_grams": it["grams"],
                          "candidates": [{
                              "fdc_id": r[0], "description": r[1],
                              "per_100g": {"kcal": r[3], "protein_g": r[4], "carbs_g": r[5],
                                           "fat_g": r[6]},
                              "portions": [f"{l} = {g:g} g" for l, g in F.portions_for(db, r[0])][:8],
                          } for r in rows]})

    choices = {}
    if brief:
        try:
            chosen, usage = chat([{"role": "system", "content": LABEL_CHOOSE_SYSTEM},
                                  {"role": "user", "content": json.dumps(
                                      {"description": text, "items": brief})}],
                                 schema=LABEL_CHOOSE_SCHEMA)
        except RuntimeError as e:
            return {"error": str(e)}
        tokens += usage.get("total_tokens", 0)
        choices = {c["item"]: c for c in chosen["choices"]}

    parts, shown = [], []
    for i, it in enumerate(items):
        c = choices.get(i) or {}
        stated = it["stated_nutrients"]
        if i in brands and c.get("fdc_id") in brands[i]:
            p = brands[i][c["fdc_id"]]
            sv = c.get("servings") or 1.0
            base = B.per_serving(p, sv)
            shown.append({"name": f"{p['brand']} {p['description']}"[:60], "grams": round(
                (p["serving_size"] or 0) * sv), "kind": f"label x{sv:g}"})
        elif i in usda and c.get("fdc_id") in usda[i]:
            row = usda[i][c["fdc_id"]]
            g = c.get("grams") or it["grams"]
            base = _row_nutrients(row, g)
            shown.append({"name": row[1], "grams": round(g), "kind": "usda"})
        else:
            base = _item_nutrients({**it, "stated_nutrients": {}}, it.get("per_100g"))
            shown.append({"name": it["name"], "grams": round(it["grams"]), "kind": it["kind"]})
        parts.append({k: (stated[k] if stated.get(k) is not None else base.get(k)) for k in COLUMNS})

    res = _sum(parts)
    res.update(needs_detail=False, items=shown,
               seconds=round(time.time() - t0, 1), tokens=tokens)
    return res


METHODS = {"ai_v1": ai_v1, "ai_items": ai_items, "ai_ground": ai_ground, "ai_label": ai_label}


# --------------------------------------------------------------------------
# ai_fast: ONE short call. The model only reads the sentence -- which foods,
# which brand, how much, in what unit -- and writes no nutrient numbers at all,
# which is most of what made the other methods slow (output tokens are the
# latency). Matching, portion conversion and arithmetic are all code, so the
# same words always give the same numbers.
# --------------------------------------------------------------------------
FAST_SCHEMA = _obj({
    "needs_detail": {"type": "boolean"},
    "items": {"type": "array", "items": _obj({
        "food": {"type": "string"},
        "brand": {"type": ["string", "null"]},
        "restaurant": {"type": ["string", "null"]},
        "search": {"type": "string"},
        "alt_search": {"type": "array", "items": {"type": "string"}},
        "count": {"type": ["number", "null"]},
        "unit": {"type": ["string", "null"]},
        "grams": {"type": "number"},
        "stated_protein_g": {"type": ["number", "null"]},
        "stated_kcal": {"type": ["number", "null"]},
    })},
})

FAST_SYSTEM = """Read what someone ate and list each distinct food. Output JSON only.

- One entry per food actually eaten; a homemade sandwich is its parts, a named \
product (Big Mac, Built bar) is one entry. Never list the same food twice.
- brand: the brand if a packaged product was named ("Quaker", "Premier Protein"), \
else null. restaurant: the chain if it came from one, else null.
- search: words to find it. For a brand: brand + product + flavour ("quaker chewy \
chocolate chip granola bar"). For generic food: USDA style, food first, then form \
and fat level ("chicken breast roasted", "rice white cooked", "cottage cheese 4%", \
"yogurt greek plain nonfat", "beef ground 85% patty").
- alt_search: one or two other ways to search for the same food (a simpler name, \
USDA's wording: "salmon atlantic cooked", "cheese cottage creamed").
- count + unit: the amount as said, unit singular ("bar", "cookie", "slice", "cup", \
"tbsp", "oz", "g", "serving", "container", "can", "bottle", "pack"). "2 bars" is \
count 2 unit "bar"; "a banana" is count 1 unit null; nothing said is count null.
- grams: your best estimate of the weight eaten, as eaten.
- "a little bit", "a splash" = about 1 tbsp.
- stated_*: only numbers the person said for that food, else null. A number of \
grams attached to a protein product ("38 g protein shake", "protein shake 38g", \
"17g built bar", "20 g protein yogurt") is its PROTEIN, not its weight: set \
stated_protein_g and estimate grams as one normal serving (a shake ~330 g).
- Home-cooked, unseasoned food is the plain version: search for it plainly and do \
not add "with salt", "fat added" or "sauce" unless the person said so.
- needs_detail true only if no identifiable food is named ("a solid breakfast")."""

VOLUME_ML = {"cup": 240, "tbsp": 15, "tablespoon": 15, "tsp": 5, "teaspoon": 5,
             "fl oz": 29.57, "ml": 1}
MASS_G = {"g": 1, "gram": 1, "oz": 28.35, "ounce": 28.35, "lb": 453.6}


ONE_OF_IT = {"", "bar", "can", "bottle", "container", "cup_container", "pack", "packet",
             "pouch", "piece", "serving", "shake", "box", "bag", "sandwich", "pizza",
             "item", "each", "whole", "package"}


def _unit_key(w):
    """Singular form for comparing units. stem() alone maps "cookies" to "cooky"
    but leaves "cookie" alone, so pluralise first and stem both the same way."""
    w = (w or "").lower().strip()
    if not w or w in MASS_G or w in VOLUME_ML:
        return w
    return F.stem(w if w.endswith("s") else w + "s")


def _serving_grams(p):
    """
    The label serving in grams. The household text wins when it states grams:
    some submissions put the PACKAGE weight in serving_size (string cheese at
    340 g "per serving", household "1 Piece (28g)").
    """
    hh = p.get("household") or ""
    m = re.search(r"\((\d+(?:\.\d+)?)\s*g\b", hh, re.I)
    size = p.get("serving_size") or 0
    if m:
        g = float(m.group(1))
        if g > 0 and (not size or abs(g - size) / max(g, size) > 0.2):
            return g
    return size


def _label_servings(p, count, unit, est_grams=None):
    """
    How many of the LABEL's servings the said amount is.

    Tries, in order: a weight ("1 oz"), the household measure in the person's own
    unit ("2 cookies" against "3 cookies"), a volume conversion, "one of it" units
    (a bar, a can), and finally the model's own weight estimate, which is better
    than assuming one serving.
    """
    import fractions
    if count is None:
        return 1.0
    u = _unit_key(unit)
    size = _serving_grams(p)
    if u in MASS_G and size:
        return count * MASS_G[u] / size
    hh = (p.get("household") or "").lower()
    m = re.match(r"\s*(\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?)\s*([a-z][a-z ]*)?", hh)
    if m:
        try:
            qty = float(sum(fractions.Fraction(x) for x in m.group(1).split()))
        except (ValueError, ZeroDivisionError):
            qty = 0
        hunit = _unit_key((m.group(2) or "").strip().split(" ")[0]) if m.group(2) else ""
        if qty and u and hunit and (u == hunit or u.startswith(hunit) or hunit.startswith(u)):
            return count / qty
        if qty and u in VOLUME_ML and hunit in VOLUME_ML:
            return count * VOLUME_ML[u] / (qty * VOLUME_ML[hunit])
    if u in VOLUME_ML and size and str(p.get("serving", "")).lower().endswith(("ml", "mlt")):
        return count * VOLUME_ML[u] / size
    if u in {_unit_key(x) for x in ONE_OF_IT} or not u:
        return count
    if est_grams and size:
        return est_grams / size
    return count


def _usda_grams(db, row, count, unit, est):
    if count is None:
        return est
    u = (unit or "").lower().rstrip("s")
    if u in MASS_G:
        return count * MASS_G[u]
    portions = F.portions_for(db, row[0])
    names = {"tbsp": ("tbsp", "tablespoon"), "tablespoon": ("tbsp", "tablespoon"),
             "tsp": ("tsp", "teaspoon"), "teaspoon": ("tsp", "teaspoon")}.get(u, (u,) if u else ())
    for want in names:
        hit = [(l.lower(), g) for l, g in portions if re.search(rf"\b{re.escape(want)}", l.lower())]
        if hit:
            return count * F._plainest(hit)
    return est


def ai_fast(db, text, model=None):
    t0 = time.time()
    global MODEL
    saved = MODEL
    if model:
        MODEL = model
    try:
        out, usage = chat([{"role": "system", "content": FAST_SYSTEM},
                           {"role": "user", "content": text}], schema=FAST_SCHEMA,
                          effort=None if (model or MODEL).startswith("gpt-4") else "none",
                          max_tokens=1500)
    except RuntimeError as e:
        return {"error": str(e)}
    finally:
        MODEL = saved
    items = out["items"]
    if out["needs_detail"] or not items:
        return {"needs_detail": True, **{k: None for k in COLUMNS},
                "seconds": round(time.time() - t0, 1), "tokens": usage.get("total_tokens")}
    parts, shown, unsure = [], [], False
    for it in items:
        n = None
        if it.get("brand"):
            found = B.search(_branded(), it["search"], limit=3, brand=it["brand"])
            if found:
                p = found[0]
                sv = _label_servings(p, it.get("count"), it.get("unit"))
                n = B.per_serving(p, sv)
                shown.append({"name": f"{p['brand']} {p['description']}"[:60],
                              "grams": round((p["serving_size"] or 0) * sv), "kind": f"label x{sv:.2g}"})
        if n is None and not it.get("restaurant"):
            hits = F.search(db, it["search"], limit=1)
            if hits and F._credible(it["search"], hits[0][1]):
                g = _usda_grams(db, hits[0], it.get("count"), it.get("unit"), it["grams"])
                n = _row_nutrients(hits[0], g)
                shown.append({"name": hits[0][1], "grams": round(g), "kind": "usda"})
        if n is None:
            unsure = True
            n = {k: None for k in COLUMNS}
            shown.append({"name": it["food"], "grams": round(it["grams"]), "kind": "unmatched"})
        if it.get("stated_protein_g") is not None:
            n["protein_g"] = it["stated_protein_g"]
        if it.get("stated_kcal") is not None:
            n["kcal"] = it["stated_kcal"]
        parts.append(n)
    res = _sum(parts)
    res.update(needs_detail=unsure, items=shown,
               seconds=round(time.time() - t0, 1), tokens=usage.get("total_tokens"))
    return res


METHODS["ai_fast"] = ai_fast
for _m in ("gpt-5.4-mini", "gpt-5.4-nano", "gpt-4.1-mini", "gpt-5.6-sol", "gpt-5.6-terra"):
    METHODS[f"ai_fast@{_m}"] = (lambda m: lambda db, t: ai_fast(db, t, model=m))(_m)


# --------------------------------------------------------------------------
# ai_pick: ai_fast plus a second, TINY call in which the model chooses among
# shortlisted rows by index. The reply is a handful of integers, so it costs
# about a second, and it fixes what deterministic ranking gets wrong ("Oreo
# fudge covered bites" for "3 oreos"). Restaurant items carry the chain's
# published figures from the model, since no database here has them.
# --------------------------------------------------------------------------
PICK_PARSE_SCHEMA = _obj({
    "needs_detail": {"type": "boolean"},
    "items": {"type": "array", "items": _obj({
        "food": {"type": "string"},
        "brand": {"type": ["string", "null"]},
        "restaurant": {"type": ["string", "null"]},
        "search": {"type": "string"},
        "alt_search": {"type": "array", "items": {"type": "string"}},
        "count": {"type": ["number", "null"]},
        "unit": {"type": ["string", "null"]},
        "grams": {"type": "number"},
        "stated_protein_g": {"type": ["number", "null"]},
        "stated_kcal": {"type": ["number", "null"]},
        "restaurant_facts": {"type": ["object", "null"], "properties": {
            k: {"type": ["number", "null"]} for k in ("kcal", "protein_g", "carbs_g", "fat_g",
                                                      "sugar_g", "sodium_mg")},
            "required": ["kcal", "protein_g", "carbs_g", "fat_g", "sugar_g", "sodium_mg"],
            "additionalProperties": False},
    })},
})

PICK_PARSE_SYSTEM = FAST_SYSTEM + """
- restaurant_facts: ONLY for restaurant items, the chain's published nutrition for \
the whole amount eaten; null for everything else."""

PICK_SCHEMA = _obj({"picks": {"type": "array", "items": _obj({
    "item": {"type": "integer"}, "pick": {"type": ["integer", "null"]}})}})

PICK_SYSTEM = """For each item, choose the option that is the food the person ate.

- Branded: the same product line. If they did not say the flavour or size, pick the \
most ordinary one (original / plain / standard single-serve size) - that is still a \
match. Never pick a diet, zero-sugar, light, lactose-free, mini, family-size or \
variety-pack version unless they said so.
- Generic: the same food in the same form (cooked vs raw, fat level). Prefer rows \
with no added salt, fat or sauce unless the person said so.
- Skip an option whose calories are clearly wrong for that food (a regular soda \
at 0 kcal is a mislabelled diet one).
- pick = the option number. null ONLY if every option is a different kind of food.
Output JSON only."""


def ai_pick(db, text, model=None):
    t0 = time.time()
    global MODEL
    saved = MODEL
    if model:
        MODEL = model
    eff = None if MODEL.startswith("gpt-4") else "none"
    tokens = 0
    try:
        out, usage = chat([{"role": "system", "content": PICK_PARSE_SYSTEM},
                           {"role": "user", "content": text}], schema=PICK_PARSE_SCHEMA,
                          effort=eff, max_tokens=1500)
        tokens += usage.get("total_tokens", 0)
        items = out["items"]
        if out["needs_detail"] or not items:
            return {"needs_detail": True, **{k: None for k in COLUMNS},
                    "seconds": round(time.time() - t0, 1), "tokens": tokens}

        options, menu = {}, []
        for i, it in enumerate(items):
            if it.get("restaurant") and it.get("restaurant_facts"):
                continue
            opts = []
            if it.get("brand"):
                for p in B.search(_branded(), it["search"], limit=6, brand=it["brand"]):
                    opts.append(("label", p))
            if not opts:
                terms = [it["search"], *it.get("alt_search", []), it["food"]]
                for r in _candidates(db, terms, limit=5)[:9]:
                    opts.append(("usda", r))
            options[i] = opts
            if opts:
                menu.append(f"item {i}: {it['food']} ({it.get('brand') or 'generic'})")
                for j, (kind, o) in enumerate(opts):
                    # Calories are shown so an obviously wrong label (a "Cherry
                    # Coke" submitted at 0 kcal) can be passed over.
                    if kind == "label":
                        kc = _per_label_serving(o, 1).get("kcal")
                        menu.append(f"  {j}. {o['brand']} {o['description']} "
                                    f"[{o['household'] or o['serving']}; {kc:.0f} kcal]")
                    else:
                        menu.append(f"  {j}. {o[1]} [{o[3]:.0f} kcal/100 g]")
        picks = {}
        if menu:
            chosen, usage = chat([{"role": "system", "content": PICK_SYSTEM},
                                  {"role": "user", "content": f"They said: {text}\n\n" + "\n".join(menu)}],
                                 schema=PICK_SCHEMA, effort=eff, max_tokens=600)
            tokens += usage.get("total_tokens", 0)
            picks = {p["item"]: p["pick"] for p in chosen["picks"]}
    except RuntimeError as e:
        return {"error": str(e)}
    finally:
        MODEL = saved

    parts, shown, unsure = [], [], False
    for i, it in enumerate(items):
        n = None
        if it.get("restaurant") and it.get("restaurant_facts"):
            n = {k: it["restaurant_facts"].get(k) for k in COLUMNS}
            shown.append({"name": f"{it['restaurant']} {it['food']}", "grams": round(it["grams"]),
                          "kind": "restaurant"})
        else:
            opts = options.get(i, [])
            j = picks.get(i)
            if j is not None and 0 <= j < len(opts):
                kind, o = opts[j]
                if kind == "label":
                    sv = _label_servings(o, it.get("count"), it.get("unit"), it.get("grams"))
                    n = _per_label_serving(o, sv)
                    shown.append({"name": f"{o['brand']} {o['description']}"[:60],
                                  "grams": round(_serving_grams(o) * sv), "kind": f"label x{sv:.2g}"})
                else:
                    g = _usda_grams(db, o, it.get("count"), it.get("unit"), it["grams"])
                    n = _row_nutrients(o, g)
                    shown.append({"name": o[1], "grams": round(g), "kind": "usda"})
        if n is None:
            unsure = True
            n = {k: None for k in COLUMNS}
            shown.append({"name": it["food"], "grams": round(it["grams"]), "kind": "unmatched"})
        if it.get("stated_protein_g") is not None:
            n["protein_g"] = it["stated_protein_g"]
        if it.get("stated_kcal") is not None:
            n["kcal"] = it["stated_kcal"]
        parts.append(n)
    res = _sum(parts)
    res.update(needs_detail=unsure, items=shown,
               seconds=round(time.time() - t0, 1), tokens=tokens)
    return res


def _per_label_serving(p, servings):
    """
    Nutrients for `servings` label servings.

    Scaled by the SUBMITTED serving_size, even where that is wrong: USDA derives
    its per-100 g figures by dividing the label by that same number, so the two
    errors cancel and per100 x serving_size is exactly what the label printed.
    Only the serving COUNT uses the corrected weight (_serving_grams).
    """
    g = (p.get("serving_size") or 0) * servings
    return {k: (v * g / 100.0 if v is not None else None) for k, v in p["per100"].items()}


METHODS["ai_pick"] = ai_pick
for _m in ("gpt-5.4-mini", "gpt-5.4-nano", "gpt-4.1-mini", "gpt-5.6-sol", "gpt-5.6-terra"):
    METHODS[f"ai_pick@{_m}"] = (lambda m: lambda db, t: ai_pick(db, t, model=m))(_m)


def ai_v1_t0(db, text):
    """Today's prompt, but with reasoning off and temperature 0: the smallest possible change."""
    t0 = time.time()
    try:
        out, usage = chat([{"role": "system", "content": _V1.SYSTEM},
                           {"role": "user", "content": text}], max_tokens=2000, effort="none")
    except RuntimeError as e:
        return {"error": str(e)}
    res = {V1_MAP[k]: out.get(k) for k in V1_MAP}
    res.update(needs_detail=bool(out.get("needs_detail")), name=out.get("name"),
               seconds=round(time.time() - t0, 1), tokens=usage.get("total_tokens"))
    return res


METHODS["ai_v1_t0"] = ai_v1_t0


# --------------------------------------------------------------------------
# ai_hybrid: what the measurements point to.
#
#  * On generic food the model's own estimate (today's prompt) is already about
#    as good as USDA grounding -- 90% either way -- and grounding costs latency.
#  * On packaged food the model misremembers labels (sodium +10% median, 17 of 51
#    off by >20%) and the label database fixes that.
#
# So: one itemised call in which the model estimates every item itself, as today,
# and then -- only for items that name a brand -- a tiny pick among real labels,
# whose numbers replace the model's for that item.
# --------------------------------------------------------------------------
HYBRID_SCHEMA = _obj({
    "needs_detail": {"type": "boolean"},
    "items": {"type": "array", "items": _obj({
        "food": {"type": "string"},
        "brand": {"type": ["string", "null"]},
        "product_search": {"type": ["string", "null"]},
        "count": {"type": ["number", "null"]},
        "unit": {"type": ["string", "null"]},
        "grams": {"type": "number"},
        **{k: {"type": ["number", "null"]} for k in COLUMNS},
    })},
})

HYBRID_SYSTEM = f"""You turn a plain-language description of food someone ate into \
structured data for a nutrition log. Accuracy is what matters most.

{ITEM_RULES}
- For every item give its nutrients for the WHOLE amount eaten (kcal; grams for \
macros; mg for sodium and caffeine) from your nutrition knowledge -- USDA for \
generic food, the published label for a brand or chain. Home-cooked plain food \
has no added salt unless the person said so.
- A number of grams next to a protein product ("38 g protein shake", "17g built \
bar") is its protein, not its weight: protein_g is that number.
- brand: the brand of a packaged product ("Quaker", "Premier Protein"), else null. \
product_search: brand + product + flavour for finding its label ("quaker chewy \
chocolate chip granola bar"), else null. Restaurant food has brand null.
- count + unit: the amount as said, unit singular ("bar", "cookie", "cup", "tbsp", \
"oz", "g", "container"); count null when no amount was said."""


def ai_hybrid(db, text, effort=None):
    t0 = time.time()
    tokens = 0
    try:
        out, usage = chat([{"role": "system", "content": HYBRID_SYSTEM},
                           {"role": "user", "content": text}], schema=HYBRID_SCHEMA,
                          effort=effort, max_tokens=4000)
        tokens += usage.get("total_tokens", 0)
        items = out["items"]
        if out["needs_detail"] or not items:
            return {"needs_detail": True, **{k: None for k in COLUMNS},
                    "seconds": round(time.time() - t0, 1), "tokens": tokens}
        options, menu = {}, []
        for i, it in enumerate(items):
            if not (it.get("brand") and it.get("product_search")):
                continue
            found = B.search(_branded(), it["product_search"], limit=6, brand=it["brand"])
            if not found:
                continue
            options[i] = found
            menu.append(f"item {i}: {it['food']} ({it['brand']})")
            for j, o in enumerate(found):
                kc = _per_label_serving(o, 1).get("kcal")
                menu.append(f"  {j}. {o['brand']} {o['description']} "
                            f"[{o['household'] or o['serving']}; {kc:.0f} kcal]")
        picks = {}
        if menu:
            global MODEL
            chosen, usage = chat([{"role": "system", "content": PICK_SYSTEM},
                                  {"role": "user", "content": f"They said: {text}\n\n" + "\n".join(menu)}],
                                 schema=PICK_SCHEMA, effort="none", max_tokens=600)
            tokens += usage.get("total_tokens", 0)
            picks = {p["item"]: p["pick"] for p in chosen["picks"]}
    except RuntimeError as e:
        return {"error": str(e)}

    parts, shown = [], []
    for i, it in enumerate(items):
        own = {k: it.get(k) for k in COLUMNS}
        j = picks.get(i)
        if i in options and j is not None and 0 <= j < len(options[i]):
            o = options[i][j]
            sv = _label_servings(o, it.get("count"), it.get("unit"), it.get("grams"))
            label = _per_label_serving(o, sv)
            # A protein figure the person stated still wins over the label.
            n = {k: (label[k] if label.get(k) is not None else own[k]) for k in COLUMNS}
            shown.append({"name": f"{o['brand']} {o['description']}"[:60],
                          "grams": round(_serving_grams(o) * sv), "kind": f"label x{sv:.2g}"})
        else:
            n = own
            shown.append({"name": it["food"], "grams": round(it["grams"]), "kind": "model"})
        parts.append(n)
    res = _sum(parts)
    res.update(needs_detail=False, items=shown,
               seconds=round(time.time() - t0, 1), tokens=tokens)
    return res


METHODS["ai_hybrid"] = ai_hybrid
METHODS["ai_hybrid_fast"] = lambda db, t: ai_hybrid(db, t, effort="none")
