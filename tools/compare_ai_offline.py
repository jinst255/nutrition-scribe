#!/usr/bin/env python3
"""
Head-to-head: the AI lookup against the bundled offline database.

The point of this is one question — how much quality would be lost by making the
app work with no API key at all? Guessing at that is worthless, so this runs the
same phrases through both paths and puts the answers side by side.

It uses the SAME prompt and model as OpenAiBackend.kt, so the numbers describe the
app, not a hypothetical. Keep the two in step.

Usage:
    OPENAI_API_KEY=sk-... python3 tools/compare_ai_offline.py food.db [-n 20]

The key is read from the environment and never written anywhere.
"""
import argparse, json, os, sqlite3, sys, time, urllib.error, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from foodsearch import search, parse_quantity

MODEL = "gpt-6-luna"

# Extended beyond the four the app asks for today. Someone tracking sodium gets
# nothing at all from the AI path as it stands, while the offline database has
# all nine — so the comparison is only fair if both are asked for the same thing.
SCHEMA = """{
  "name": "string",
  "protein_grams": "number",
  "calories": "number | null",
  "carbs_grams": "number | null",
  "fat_grams": "number | null",
  "fiber_grams": "number | null",
  "sugar_grams": "number | null",
  "saturated_fat_grams": "number | null",
  "sodium_mg": "number | null",
  "caffeine_mg": "number | null",
  "is_fixed_food": "boolean",
  "source": "string",
  "confidence": "estimated | stated",
  "needs_detail": "boolean"
}"""

STATED_VS_ESTIMATED = (
    "If the message gives a protein amount that clearly covers the ENTIRE food or meal as a single, "
    "wholly-described item (e.g. \"Built bar, 16g protein\"), use that number directly for protein_grams "
    "and set confidence to \"stated\". If a protein amount is only given for ONE ingredient or component "
    "of a larger, multi-part meal, do NOT use that number as the total — incorporate it into your own "
    "estimate of the full meal's protein and set confidence to \"estimated\". When nothing is stated, "
    "estimate the fields from your nutrition knowledge and set confidence to \"estimated\". "
    "If the description is too vague to estimate at all (e.g. \"a solid breakfast\", \"leftovers\"), "
    "set needs_detail to true and leave the nutrient fields null rather than inventing numbers."
)

SYSTEM = (
    "You are a nutrition lookup assistant for a food logging app. "
    "Given a plain-language description of what someone ate, respond with ONLY a JSON object "
    f"matching this shape: {SCHEMA}. " + STATED_VS_ESTIMATED
)

# Chosen to cover what actually breaks: brands and restaurant food (the offline
# database has neither), stated amounts, multi-item meals, and vague input.
CASES = [
    ("banana", "plain"),
    ("2 eggs", "plain"),
    ("8oz chicken breast", "quantity"),
    ("2 cups of white rice", "quantity"),
    ("greek yogurt", "plain"),
    ("cheddar cheese", "plain"),
    ("black coffee", "plain"),
    ("Big Mac", "brand"),
    ("Chipotle chicken burrito bowl", "brand"),
    ("Chobani vanilla greek yogurt", "brand"),
    ("Built bar, 16g protein", "stated"),
    ("Diet Coke", "brand"),
    ("Starbucks grande latte", "brand"),
    ("spaghetti with meat sauce", "compound"),
    ("tuna sandwich on wheat", "compound"),
    ("chicken, rice and broccoli", "multi"),
    ("two scrambled eggs with toast and butter", "multi"),
    ("a solid breakfast", "vague"),
    ("leftovers", "vague"),
    ("protein shake with milk and a banana", "multi"),
]


def ask_ai(key, text, timeout=60):
    body = json.dumps({
        "model": MODEL,
        "max_completion_tokens": 2000,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": text},
        ],
    }).encode()
    req = urllib.request.Request(
        "https://api.openai.com/v1/chat/completions",
        data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = json.load(r)
    except urllib.error.HTTPError as e:
        return {"error": f"HTTP {e.code}: {e.read()[:200].decode('utf-8', 'replace')}"}, time.time() - start
    except Exception as e:
        return {"error": f"{type(e).__name__}: {e}"}, time.time() - start

    elapsed = time.time() - start
    try:
        content = payload["choices"][0]["message"]["content"]
        out = json.loads(content)
        out["_usage"] = payload.get("usage", {})
        return out, elapsed
    except Exception as e:
        return {"error": f"unparseable: {e}", "raw": str(payload)[:300]}, elapsed


def offline(db, text):
    parsed = parse_quantity(text)
    hits = search(db, parsed[2], limit=1)
    if not hits:
        return None
    r = hits[0]
    grams = db.execute("SELECT default_grams FROM food WHERE fdc_id=?", (r[0],)).fetchone()[0] or 100.0
    count = parsed[0] or 1.0
    unit = parsed[1]
    if unit in ("g", "gram", "grams"):
        grams_total = count
    elif unit in ("oz", "ounce", "ounces"):
        grams_total = count * 28.3495
    else:
        grams_total = count * grams
    scale = grams_total / 100.0
    keys = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g",
            "sat_fat_g", "sodium_mg", "caffeine_mg"]
    vals = {k: (r[3 + i] * scale if r[3 + i] is not None else None) for i, k in enumerate(keys)}
    return {"name": r[1], "grams": grams_total, **vals}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("db")
    p.add_argument("-n", type=int, default=len(CASES))
    p.add_argument("-o", "--out")
    a = p.parse_args()

    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not key:
        sys.exit("set OPENAI_API_KEY")

    db = sqlite3.connect(a.db)
    results = []
    total_tokens = 0

    for text, kind in CASES[:a.n]:
        ai, elapsed = ask_ai(key, text)
        off = offline(db, text)
        usage = ai.pop("_usage", {}) if isinstance(ai, dict) else {}
        total_tokens += usage.get("total_tokens", 0)

        print(f"\n=== {text!r}  [{kind}]")
        if "error" in ai:
            print(f"  AI      ERROR {ai['error']}")
        else:
            print(f"  AI      {ai.get('name')!r:38s} "
                  f"P{fmt(ai.get('protein_grams'))} C{fmt(ai.get('calories'))} "
                  f"Na{fmt(ai.get('sodium_mg'))} sug{fmt(ai.get('sugar_grams'))} "
                  f"conf={ai.get('confidence')} vague={ai.get('needs_detail')} [{elapsed:.1f}s]")
        if off:
            print(f"  OFFLINE {off['name'][:38]!r:38s} "
                  f"P{fmt(off['protein_g'])} C{fmt(off['kcal'])} "
                  f"Na{fmt(off['sodium_mg'])} sug{fmt(off['sugar_g'])} "
                  f"@{off['grams']:.0f}g")
        else:
            print(f"  OFFLINE {'(no match)':38s}")
        results.append({"text": text, "kind": kind, "ai": ai, "offline": off, "ai_seconds": elapsed})

    print(f"\ntotal tokens: {total_tokens}")
    if a.out:
        with open(a.out, "w") as f:
            json.dump(results, f, indent=2)
        print(f"wrote {a.out}")


def fmt(v):
    return f"{v:>7.1f}" if isinstance(v, (int, float)) else f"{'-':>7}"


if __name__ == "__main__":
    main()
