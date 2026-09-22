#!/usr/bin/env python3
"""
The harder half: what someone OTHER than the app's author would say to it.

WHY A SECOND SET
----------------
tools/bench_real_log.py scores against one real log, which is honest but narrow:
one person, one diet, mostly protein. The app is meant to be given away, and
friends will eat differently and care about different numbers — sodium, sugar,
fibre, or just "what did I eat today".

These cases need no API key. Two things can be asserted without one:

  want  — the food that should be found, as a substring of the description.
          This is the "did it understand me" test.

  kcal  — a plausible range for that portion, from ordinary nutrition
          knowledge. This is the "would this ruin someone's day" test, and it
          is the one that matters: a wrong-but-close match is a rounding error,
          while a 5x match quietly destroys a week of logging.

  honest — the app CANNOT know this one (a brand, a restaurant portion), so the
          right behaviour is to flag it for detail, not to invent a number.
          Scoring this separately keeps "confidently wrong" from looking the
          same as "correctly unsure".

Usage:  python3 tools/bench_friends.py app/src/main/assets/food.db [-v]
"""
import argparse, os, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import foodsearch as F

# (query, want_substring_or_None, (kcal_low, kcal_high) or None, honest?)
CASES = [
    # --- everyday foods someone else's diet is built on -------------------
    ("a bowl of oatmeal",            "oatmeal",      (110, 400),  False),
    ("two slices of toast",          "toast",        (100, 240),  False),
    ("a handful of almonds",         "almond",       (100, 300),  False),
    ("scrambled eggs",               "egg",          (90, 300),   False),
    ("a chicken caesar salad",       "salad",        (200, 900),  False),
    ("turkey sandwich",              "sandwich",     (200, 600),  False),
    ("a bagel with cream cheese",    "bagel",        (250, 700),  False),
    ("grilled cheese sandwich",      "cheese",       (250, 700),  False),
    ("mac and cheese",               "macaroni",     (200, 700),  False),
    ("a baked potato",               "potato",       (100, 350),  False),
    ("steamed broccoli",             "broccoli",     (20, 120),   False),
    ("a bowl of cereal with milk",   None,           (150, 500),  False),
    ("peanut butter on toast",       None,           (150, 500),  False),
    ("chicken noodle soup",          "soup",         (60, 350),   False),
    ("a ham and cheese sandwich",    None,           (200, 700),  False),

    # --- food that is not American-diner food ------------------------------
    ("chicken tikka masala",         "chicken",      (250, 800),  False),
    ("pad thai",                     None,           (300, 1100), False),
    ("beef tacos",                   "taco",         (150, 700),  False),
    ("chicken fried rice",           "rice",         (200, 800),  False),
    ("miso soup",                    "soup",         (20, 150),   False),
    ("hummus and pita",              None,           (150, 600),  False),
    ("a beef burrito",               "burrito",      (250, 900),  False),
    ("sushi roll",                   None,           (150, 600),  False),
    ("falafel wrap",                 None,           (250, 800),  False),
    ("chicken curry with rice",      None,           (300, 1000), False),

    # --- vegetarian and vegan, which the protein-first framing ignored ------
    ("tofu stir fry",                None,           (150, 600),  False),
    ("black bean soup",              "soup",         (100, 400),  False),
    ("a veggie burger",              None,           (150, 600),  False),
    ("lentil soup",                  "lentil",       (100, 400),  False),
    ("oat milk",                     "milk",         (50, 200),   False),
    ("almond milk",                  "milk",         (20, 150),   False),

    # --- the metrics other people actually track ---------------------------
    ("a bag of potato chips",        "potato chip",  (130, 400),  False),
    ("instant ramen",                None,           (250, 700),  False),
    ("soy sauce",                    "soy sauce",    (0, 40),     False),
    ("pickles",                      "pickle",       (0, 80),     False),
    ("a can of coke",                None,           (90, 200),   False),
    ("an energy drink",              None,           (5, 300),    False),
    ("a glass of orange juice",      "orange juice", (80, 200),   False),
    ("a beer",                       "beer",         (90, 250),   False),
    ("a glass of red wine",          "wine",         (90, 220),   False),
    ("sparkling water",              "water",        (0, 20),     False),

    # --- snacks and sweets --------------------------------------------------
    ("a chocolate chip cookie",      "cookie",       (40, 300),   False),
    ("a bowl of ice cream",          "ice cream",    (120, 500),  False),
    ("a slice of pizza",             "pizza",        (200, 600),  False),
    ("popcorn",                      "popcorn",      (30, 400),   False),
    ("a granola bar",                "bar",          (90, 300),   False),
    ("greek yogurt with honey",      None,           (90, 400),   False),

    # --- how people actually phrase amounts ---------------------------------
    ("a couple of eggs",             "egg",          (100, 250),  False),
    ("a few strawberries",           "strawberr",    (5, 100),    False),
    ("half a chicken breast",        "chicken",      (60, 300),   False),
    ("a big bowl of pasta",          "pasta",        (150, 900),  False),
    ("three tacos",                  "taco",         (300, 1100), False),
    ("a plate of spaghetti",         None,           (150, 900),  False),
    ("1/2 cup of rice",              "rice",         (80, 200),   False),
    ("250g chicken breast",          "chicken",      (250, 500),  False),

    # --- typos and dictation slips, which are the norm, not the exception ---
    ("chiken breast",                None,           None,        False),
    ("brocoli",                      None,           None,        False),
    ("yoghurt",                      "yogurt",       None,        False),
    ("a banna",                      None,           None,        False),

    # --- things the offline database genuinely cannot know ------------------
    # The right answer is to say so. Inventing a number here is the failure.
    ("a Chipotle burrito bowl",      None,           None,        True),
    ("Starbucks grande latte",       None,           None,        True),
    ("whatever was in the fridge",   None,           None,        True),
    ("my usual lunch",               None,           None,        True),
    ("leftovers from last night",    None,           None,        True),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()
    db = sqlite3.connect(a.db)

    want_n = want_ok = kcal_n = kcal_ok = honest_n = honest_ok = 0
    failures = []

    for text, want, kcal_range, honest in CASES:
        r = F.offline_lookup(db, text)
        desc = "" if r is None else " + ".join(m[0] for m in r["matches"])
        kcal = None if r is None else r["kcal"]
        flagged = r is None or r["needs_detail"] or r["vague"]

        if honest:
            honest_n += 1
            if flagged:
                honest_ok += 1
            else:
                failures.append(f"[confident] {text!r} -> {desc!r} at {kcal:.0f} kcal"
                                if kcal else f"[confident] {text!r} -> {desc!r}")
            continue

        if want is not None:
            want_n += 1
            if want.lower() in desc.lower():
                want_ok += 1
            else:
                failures.append(f"[wrong food] {text!r} -> {desc!r} (wanted {want!r})")

        if kcal_range is not None:
            kcal_n += 1
            lo, hi = kcal_range
            if kcal is not None and lo <= kcal <= hi:
                kcal_ok += 1
            else:
                shown = f"{kcal:.0f}" if kcal is not None else "none"
                failures.append(f"[implausible] {text!r} -> {desc!r} at {shown} kcal "
                                f"(expected {lo}-{hi})")
        if a.verbose:
            print(f"  {text!r:34s} {desc[:44]!r:46s} "
                  f"{('%.0f' % kcal) if kcal else '-':>6} kcal"
                  f"{'  [needs detail]' if flagged else ''}")

    print(f"\n  right food     {want_ok}/{want_n} = {pct(want_ok, want_n)}")
    print(f"  plausible kcal {kcal_ok}/{kcal_n} = {pct(kcal_ok, kcal_n)}")
    print(f"  honest when it cannot know {honest_ok}/{honest_n} = {pct(honest_ok, honest_n)}")

    if failures:
        print(f"\n{len(failures)} failing:")
        for f in failures:
            print("   ", f)


def pct(a, b):
    return f"{a / b:.1%}" if b else "n/a"


if __name__ == "__main__":
    main()
