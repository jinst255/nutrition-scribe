#!/usr/bin/env python3
"""
Populate the `alias` table: the curated default sense for common foods.

WHY THIS EXISTS
---------------
Ranking alone gets the right food but often the wrong *sense* of it. Measured on
the bundled USDA data, a bare query lands on:

    chicken breast -> "Chicken breast, fried, coated, skin/coating eaten"
    cheddar cheese -> "Cheddar cheese soup, home recipe"
    bacon          -> "Bacon, meatless"
    french fries   -> "French toast, NFS"
    soda           -> "Vodka and soda"

Some of those are merely wrong. Two of them are dangerous:

    white rice     -> "Rice, white, medium-grain, RAW"   360 kcal/100g
    ground beef    -> "Beef, ground, RAW"

USDA defaults to raw weights, but people log what they ate, which was cooked.
Cooked white rice is 129 kcal/100g. Defaulting to raw overstates a rice portion
by roughly 3x, and a food log that is silently 3x wrong is worse than no log.

Each entry below therefore names BOTH the id and the description it expects. If a
future USDA release renumbers or renames a row, this fails loudly at build time
rather than quietly aliasing "bacon" to something else entirely.

Usage:  python3 tools/curate_food_db.py food.db
"""
import sqlite3, sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from foodsearch import normalise

# term -> (fdc_id, expected description prefix)
#
# Only foods whose ranked top-1 is WRONG are listed. Foods that already resolve
# correctly (banana, oatmeal, almonds, hummus, ...) are deliberately absent —
# every entry here is a maintenance burden, so it has to earn its place.
CURATED = {
    # --- meat & fish: USDA's first hit is usually fried/coated or raw ---
    "chicken breast":   (2341362, "Chicken breast, baked, broiled, or roasted"),
    "chicken thigh":    (2341436, "Chicken thigh, baked, broiled, or roasted"),
    "ground beef":      (172161,  "Beef, ground, unspecified fat content, cooked"),
    "bacon":            (167914,  "Pork, cured, bacon, cooked"),
    "pork chop":        (2341271, "Pork, chop, NS as to fat eaten"),
    "salmon":           (2341700, "Fish, salmon, NFS"),
    "tuna":             (2341724, "Fish, tuna, NFS"),

    # --- staples where raw vs cooked is a 3x calorie error ---
    "white rice":       (2343887, "Rice, white, cooked"),
    "brown rice":       (2343893, "Rice, brown, cooked"),
    "rice":             (2343887, "Rice, white, cooked"),
    "pasta":            (2343841, "Pasta, cooked"),
    "spaghetti":        (2343841, "Pasta, cooked"),
    "bread":            (2343073, "Bread, white"),

    # --- dairy: "cheddar cheese" finding soup is the classic failure ---
    "cheddar cheese":   (2341112, "Cheese, Cheddar"),
    "mozzarella":       (2341125, "Cheese, Mozzarella, NFS"),
    "cottage cheese":   (2341150, "Cheese, cottage, NFS"),
    "greek yogurt":     (170903,  "Yogurt, Greek, plain, lowfat"),

    # --- produce & nuts ---
    "orange":           (2344665, "Orange, raw"),
    "walnuts":          (2343008, "Walnuts, excluding honey roasted"),

    # --- prepared foods ---
    "pizza":            (2344096, "Pizza, cheese"),
    "french fries":     (2344950, "Potato, french fries, NFS"),
    "salad":            (2345342, "Lettuce, salad with assorted vegetables"),
    "cake":             (172710,  "Cake, yellow, prepared from recipe"),
    "chocolate":        (2345888, "Chocolate candy"),
    "donut":            (2343545, "Doughnut, NFS"),
    "doughnut":         (2343545, "Doughnut, NFS"),
    "chips":            (2344915, "Potato chips, NFS"),

    # --- drinks ---
    "coffee":           (2345935, "Coffee, brewed"),
    "soda":             (2346106, "Soft drink, NFS"),
    "pop":              (2346106, "Soft drink, NFS"),
    "wine":             (2346263, "Wine, table, red"),
    # USDA has no plain hot black tea; every tea here is ~1 kcal, so the
    # iced-instant row is nutritionally interchangeable for logging purposes.
    "tea":              (2346063, "Tea, iced, instant, black, unsweetened"),
    "protein shake":    (2346323, "Nutritional powder mix, protein, NFS"),
    "protein powder":   (2346323, "Nutritional powder mix, protein, NFS"),

    # --- drinks, measured against the AI path and found badly wrong ---
    # "diet coke" resolved to "Roll, diet" and "black coffee" to "Coffee, Cuban"
    # at 126 kcal and 23 g of sugar, for something that is 2 kcal.
    "black coffee":     (2345935, "Coffee, brewed"),
    "iced coffee":      (2345935, "Coffee, brewed"),
    "diet coke":        (2346113, "Soft drink, cola, diet"),
    "diet soda":        (2346107, "Soft drink, NFS, diet"),
    "coke":             (2346106, "Soft drink, NFS"),
    "cola":             (2346106, "Soft drink, NFS"),
    "water":            (2346282, "Water, NFS"),
    "iced tea":         (2346087, "Tea, iced, brewed, black, unsweetened"),
    "green tea":        (2346093, "Tea, iced, brewed, green, unsweetened"),
    "beer":             (2346192, "Beer"),
    "orange juice":     (2344681, "Orange juice, 100%, NFS"),

    # --- abbreviations and everyday names USDA does not use ---
    # These came out of a real exported log. "PB&J" matched NOTHING at all (the
    # "&" split it into two one-letter fragments), and "pb&j black currant"
    # matched "Currants, european black, raw" — 71 calories for a 400 calorie
    # sandwich.
    "pbj":              (2343030, "Peanut butter and jelly sandwich, NFS"),
    "pb j":             (2343030, "Peanut butter and jelly sandwich, NFS"),
    "peanut butter jelly sandwich": (2343030, "Peanut butter and jelly sandwich, NFS"),
    "bell pepper":      (2345319, "Pepper, raw, NFS"),
    # "mac and cheese" split on "and" and matched "Big Mac (McDonalds)".
    "mac cheese":       (2344296, "Macaroni or noodles with cheese"),
    "macaroni cheese":  (2344296, "Macaroni or noodles with cheese"),
    "yoghurt":          (2340795, "Yogurt, NFS"),
    # "toast" ranked to "French toast, NFS" at more than double the calories,
    # because English puts the head noun last and USDA does not.
    "toast":            (2343074, "Bread, white, toasted"),
    # Instant ramen ranked to "Instant soup, noodle", which is 300 g of mostly
    # water at 75 calories. A packet is a dry block.
    "instant ramen":    (171177,  "Soup, ramen noodle, any flavor, dry"),
    "ramen":            (171177,  "Soup, ramen noodle, any flavor, dry"),
    # Not in USDA under any name a friend would use.
    "chicken tikka masala": (2341861, "Chicken curry"),
    "tikka masala":     (2341861, "Chicken curry"),
    "white bread":      (2343073, "Bread, white"),
    "wheat bread":      (2343189, "Bread, whole wheat"),
    "whole wheat bread": (2343189, "Bread, whole wheat"),
    # A person who says "two hot dogs" ate two hot dogs in buns. USDA's "Hot dog,
    # NFS" is the frankfurter alone, which logged 2 hot dogs at 177 calories.
    "hot dog":          (2342501, "Hot dog sandwich, NFS, on white bun"),
    "hotdog":           (2342501, "Hot dog sandwich, NFS, on white bun"),
    # No Built Bar in USDA, and "built bar" ranked to "Breakfast bar, NFS" at
    # 2 g of protein. The South Beach row is the closest real high-protein bar.
    "protein bar":      (2343607, "Nutrition bar (South Beach Living High Protein Bar)"),
    "built bar":        (2343607, "Nutrition bar (South Beach Living High Protein Bar)"),

    # --- compound dishes, where ranking matches one ingredient ---
    # "spaghetti with meat sauce" matched "Spaghetti sauce with meat" at 65 g,
    # logging the sauce and none of the pasta: 58 kcal for a 520 kcal plate.
    "spaghetti meat sauce": (172089, "Spaghetti with meat sauce"),
    "pasta meat sauce":     (172089, "Spaghetti with meat sauce"),
}


def curate(db_path):
    db = sqlite3.connect(db_path)
    db.execute("DELETE FROM alias")
    problems, written = [], 0

    for term, (fdc_id, expected) in CURATED.items():
        row = db.execute("SELECT description FROM food WHERE fdc_id = ?", (fdc_id,)).fetchone()
        if row is None:
            problems.append(f"{term!r}: fdc_id {fdc_id} is not in this database")
            continue
        if not row[0].lower().startswith(expected.lower()):
            problems.append(f"{term!r}: fdc_id {fdc_id} is {row[0]!r}, expected {expected!r}")
            continue
        db.execute("INSERT OR REPLACE INTO alias (term, fdc_id) VALUES (?, ?)",
                   (normalise(term), fdc_id))
        written += 1

    db.commit()
    db.close()

    print(f"curated aliases written: {written}/{len(CURATED)}")
    if problems:
        print("\nFAILED — the USDA data no longer matches these entries:")
        for p in problems:
            print(f"  - {p}")
        # Loud, not silent: a stale alias points a food at the wrong numbers.
        sys.exit(1)


if __name__ == "__main__":
    curate(sys.argv[1] if len(sys.argv) > 1 else "food.db")
