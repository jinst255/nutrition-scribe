#!/usr/bin/env python3
"""
Measure the offline food search: does it find the food, does it find the right
SENSE of the food, and is it fast enough for a phone?

Run it before and after any ranking or curation change:
    python3 tools/bench_food_db.py food.db

THREE THINGS ARE MEASURED, and they are not the same thing:

  1. MATCH  - is it the right food at all? ("banana" -> a banana)
  2. SENSE  - is it the form the person actually ate? ("white rice" -> COOKED)
  3. SPEED  - p50/p95 for one query on one core

Sense is the one that matters most and is easiest to fake. An earlier version of
this benchmark scored 97.7% by accepting a hit if the expected word appeared
ANYWHERE in the description, which passed "two eggs -> Eggnog" and
"avocado -> Oil, avocado". Both are nonsense. The match test below requires the
word in the HEAD of the description, which is what a person would judge.
"""
import json, os, sqlite3, statistics, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from foodsearch import search, parse_quantity, head_of

# (typed text, word the HEAD of the description must contain)
# __VAGUE__ = no lookup should be trusted; the app must ask instead.
MATCH_CASES = [
    ("chicken breast", "chicken"), ("grilled chicken breast", "chicken"),
    ("8oz chicken breast", "chicken"), ("greek yogurt", "yogurt"),
    ("plain greek yogurt", "yogurt"), ("two eggs", "egg"),
    ("3 scrambled eggs", "egg"), ("oatmeal", "oat"), ("banana", "banana"),
    ("apple", "apple"), ("white rice", "rice"), ("brown rice", "rice"),
    ("black beans", "bean"), ("salmon fillet", "salmon"), ("ground beef", "beef"),
    ("cheddar cheese", "cheese"), ("whole milk", "milk"), ("almonds", "almond"),
    ("peanut butter", "peanut"), ("whole wheat bread", "bread"),
    ("orange juice", "juice"), ("coffee", "coffee"), ("pepperoni pizza", "pizza"),
    ("cheeseburger", "cheeseburger"), ("chicken burrito", "burrito"),
    ("caesar salad", "salad"), ("spaghetti with meat sauce", "spaghetti"),
    ("cottage cheese", "cheese"), ("tuna sandwich", "tuna"),
    ("broccoli", "broccoli"), ("sweet potato", "potato"), ("avocado", "avocado"),
    ("olive oil", "oil"), ("pancakes", "pancake"), ("bacon", "bacon"),
    ("chocolate chip cookie", "cookie"), ("salmon", "salmon"), ("steak", "steak"),
    ("lentils", "lentil"), ("hummus", "hummus"), ("tofu", "tofu"),
    ("chobani vanilla", "yogurt"), ("diet coke", "__HARD__"),
    ("i ate a solid breakfast", "__VAGUE__"), ("leftovers", "__VAGUE__"),
    ("the usual", "__VAGUE__"),
]

# (typed text, description prefix the top hit MUST have)
# These are the cases where finding "the right food in the wrong form" produces
# numbers that are not slightly off but multiples off.
SENSE_CASES = [
    ("white rice", "Rice, white, cooked"),
    ("brown rice", "Rice, brown, cooked"),
    ("ground beef", "Beef, ground, unspecified fat content, cooked"),
    ("chicken breast", "Chicken breast, baked, broiled, or roasted"),
    ("chicken thigh", "Chicken thigh, baked, broiled, or roasted"),
    ("bacon", "Pork, cured, bacon, cooked"),
    ("cheddar cheese", "Cheese, Cheddar"),
    ("cottage cheese", "Cheese, cottage, NFS"),
    ("mozzarella", "Cheese, Mozzarella"),
    ("salmon", "Fish, salmon, NFS"),
    ("tuna", "Fish, tuna, NFS"),
    ("pork chop", "Pork, chop, NS as to fat eaten"),
    ("pasta", "Pasta, cooked"),
    ("spaghetti", "Pasta, cooked"),
    ("french fries", "Potato, french fries"),
    ("soda", "Soft drink"),
    ("coffee", "Coffee, brewed"),
    ("pizza", "Pizza, cheese"),
    ("donut", "Doughnut"),
    ("orange", "Orange, raw"),
    ("greek yogurt", "Yogurt, Greek, plain"),
    ("walnuts", "Walnuts, excluding honey roasted"),
    ("chips", "Potato chips"),
    ("cake", "Cake, yellow"),
    ("salad", "Lettuce, salad with assorted vegetables"),
    ("protein shake", "Nutritional powder mix, protein"),
    # Not curated — these must keep working on ranking alone, so that the
    # curation list is not quietly load-bearing for everything.
    ("banana", "Banana, raw"),
    ("oatmeal", "Oatmeal, NFS"),
    ("almonds", "Almonds"),
    ("black beans", "Black beans"),
    ("peanut butter", "Peanut butter"),
    ("whole milk", "Milk, whole"),
    ("avocado", "Avocado, raw"),
    ("hummus", "Hummus"),
    # Single-word queries are the dangerous case for candidate selection: every
    # row scores one word hit, so the shortlist truncates on a tie and the plain
    # food can be cut before ranking ever sees it. "egg" returning "Egg burrito"
    # is what that looks like, and the match test above waves it through because
    # "Egg burrito" does begin with "egg".
    ("egg", "Egg, whole"),
    ("eggs", "Egg, whole"),
    ("2 eggs", "Egg, whole"),
    ("milk", "Milk, NFS"),
    ("bread", "Bread, white"),
    ("cheese", "Cheese"),
    ("apple", "Apple, raw"),
    ("rice", "Rice, white, cooked"),
    ("chicken", "Chicken"),
    ("beef", "Beef"),
]


# The head-of-description rule mis-scores a narrow class of correct answers:
# USDA files some foods under a CATEGORY head, so the food itself sits in the
# second segment. "Pork, cured, bacon" really is the right row for "bacon".
#
# This is an explicit allowlist rather than a looser rule, because the obvious
# loosening -- "accept the first two segments" -- re-admits exactly the false
# positives that made an earlier version of this benchmark report a fake 97.7%:
# "Oil, avocado" would then pass for "avocado", and avocado oil is not avocado.
# The test is: is the queried food the SUBSTANCE of the row (bacon is pork), or
# merely derived from it (avocado oil is not avocado)? Only the former is listed.
MATCH_HEAD_EXCEPTIONS = {
    ("bacon", "pork, cured, bacon"),
    ("salmon", "fish, salmon"),
}


def match_ok(query, expect, desc):
    if expect in head_of(desc):
        return True
    low = desc.lower()
    return any(q == query and low.startswith(prefix) for q, prefix in MATCH_HEAD_EXCEPTIONS)


def run(db_path):
    db = sqlite3.connect(db_path)
    out = {}

    for label, use_alias in (("ranking only", False), ("ranking + curation", True)):
        print(f"\n{'=' * 62}\n  {label.upper()}\n{'=' * 62}")

        good = bad = 0
        misses = []
        for text, expect in MATCH_CASES:
            if expect.startswith("__"):
                continue
            food = parse_quantity(text)[2]
            res = search(db, food, limit=3, use_alias=use_alias)
            top = res[0][1] if res else "(no match)"
            if match_ok(food, expect, top):
                good += 1
            else:
                bad += 1
                misses.append((text, top))

        t3 = 0
        for text, expect in MATCH_CASES:
            if expect.startswith("__"):
                continue
            res = search(db, parse_quantity(text)[2], limit=3, use_alias=use_alias)
            if any(match_ok(parse_quantity(text)[2], expect, r[1]) for r in res):
                t3 += 1

        total = good + bad
        print(f"  MATCH  top-1 {good}/{total} = {100.0 * good / total:.1f}%"
              f"   top-3 {t3}/{total} = {100.0 * t3 / total:.1f}%")
        for text, top in misses:
            print(f"           miss  {text!r:28s} -> {top[:52]}")

        sgood, sbad = 0, []
        for text, expect in SENSE_CASES:
            res = search(db, parse_quantity(text)[2], limit=1, use_alias=use_alias)
            top = res[0][1] if res else "(no match)"
            if top.lower().startswith(expect.lower()):
                sgood += 1
            else:
                sbad.append((text, top, expect))
        st = len(SENSE_CASES)
        print(f"\n  SENSE  {sgood}/{st} = {100.0 * sgood / st:.1f}%")
        for text, top, expect in sbad:
            print(f"           wrong {text!r:28s} -> {top[:44]}")
            print(f"           {'':34s} want {expect[:44]}")

        out[label] = {"match_top1": round(100.0 * good / total, 1),
                      "match_top3": round(100.0 * t3 / total, 1),
                      "sense": round(100.0 * sgood / st, 1)}

    print(f"\n{'=' * 62}\n  SPEED (one core, cold cache excluded)\n{'=' * 62}")
    qs = [c[0] for c in MATCH_CASES]
    for _ in range(3):
        for q in qs:
            search(db, q)
    times = []
    for _ in range(15):
        for q in qs:
            t = time.perf_counter()
            search(db, q)
            times.append((time.perf_counter() - t) * 1000)
    times.sort()
    p50 = statistics.median(times)
    p95 = times[int(len(times) * 0.95)]
    print(f"  p50 {p50:.2f} ms   p95 {p95:.2f} ms   n={len(times)}")
    print(f"  db  {os.path.getsize(db_path) / 1e6:.2f} MB")
    out["speed"] = {"p50_ms": round(p50, 2), "p95_ms": round(p95, 2)}
    db.close()
    return out


if __name__ == "__main__":
    res = run(sys.argv[1] if len(sys.argv) > 1 else "food.db")
    print("\n" + json.dumps(res, indent=2))
