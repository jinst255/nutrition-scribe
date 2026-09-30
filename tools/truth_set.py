#!/usr/bin/env python3
"""
Cases with a KNOWN answer, for measuring accuracy rather than agreement.

WHY THIS EXISTS
---------------
The other benches compare the offline lookup against the AI's answers
(bench_real_log.py) or against a plausible range (bench_friends.py). Neither
can say whether the AI itself is right, and "accuracy first" needs exactly that.

Every case here has a truth that does not come from either path:

  usda    -- the portion is stated or is USDA's own standard one ("a medium
             banana"), so the truth is USDA's measurement of it. Each part is
             (fdc_id, grams) and the total is computed from food.db, so a typo
             in a number cannot creep in.
  label   -- a packaged or restaurant item with a published nutrition label.
             Values are the maker's own figures (US, 2024-2025). Products get
             reformulated, so these carry a wider tolerance.
  vague   -- nothing can be known; the right answer is to ask for detail.

The phrasings mix clean text with the dictated, rambling style of the real log
("tablespoon and a half of peanut butter", "milk 2 cups while milk"), because
that is what the app actually receives.

Tolerance is per case. A stated weight should land within 10%; "a banana"
leaves room for which banana; a label leaves room for reformulation.
"""

import os, sqlite3

# fdc_ids used below, for reading the cases:
EGG = 171287            # Egg, whole, raw, fresh (50 g large)
EGG_SCRAMBLED = 172187  # Egg, whole, cooked, scrambled
CHICKEN_BREAST = 171477 # Chicken breast, meat only, cooked, roasted
RICE_WHITE = 168878     # Rice, white, long-grain, enriched, cooked (158 g cup)
BANANA = 173944         # Bananas, raw (118 g medium)
MILK_WHOLE = 171265     # Milk, whole, 3.25% (244 g cup)
MILK_2 = 171267         # Milk, 2%
PB = 174266             # Peanut butter, smooth, with salt (16 g tbsp)
OLIVE_OIL = 171413      # Oil, olive (13.5 g tbsp)
BREAD_WHITE = 174924    # Bread, white, commercially prepared (~25-29 g slice)
BREAD_WHEAT = 172688    # Bread, whole-wheat, commercially prepared (32 g slice)
CHEDDAR = 2341112       # Cheese, Cheddar
COTTAGE_4 = 172179      # Cottage cheese, creamed (4%) (113 g half cup)
GREEK_NF = 170894       # Yogurt, Greek, plain, nonfat
OATS_DRY = 173904       # Oats, regular and quick, dry (40 g half cup)
APPLE = 171688          # Apples, raw, with skin (182 g medium)
BROCCOLI = 169967       # Broccoli, boiled, without salt
BEEF_85 = 174032        # Ground beef 85/15 patty, broiled
SALMON = 175168         # Salmon, Atlantic, farmed, cooked
POTATO_BAKED = 170093   # Potato, baked, flesh and skin
BUTTER = 173410         # Butter, salted (14.2 g tbsp)
PASTA = 169737          # Pasta, cooked, without salt (140 g cup)
AVOCADO = 171705        # Avocado, raw
ALMONDS = 2342962       # Almonds, NFS
BLACK_BEANS = 173735    # Black beans, boiled
TOFU = 172475           # Tofu, firm
SWEET_POTATO = 168483   # Sweet potato, baked in skin
JAM = 169641            # Jams and preserves (20 g tbsp)
CREAM_CHEESE = 173418   # Cheese, cream (14.5 g tbsp)
TORTILLA = 175037       # Flour tortilla, refrigerated
KETCHUP = 168556        # Catsup (17 g tbsp)
PORK_CHOP = 167827      # Pork loin chop, broiled
MOZZ = 170845           # Mozzarella, whole milk
MAPLE = 169661          # Maple syrup (20 g tbsp)
RAISINS = 168165        # Raisins, seedless
BABY_CARROT = 168568    # Baby carrots (10 g each)
CELERY = 169988         # Celery, raw (40 g medium stalk)
RED_PEPPER = 170108     # Red bell pepper (119 g medium)
SPINACH = 168462        # Spinach, raw
HONEY = 169640          # Honey (21 g tbsp)
STRAWBERRY = 167762     # Strawberries (12 g medium)
BLUEBERRY = 171711      # Blueberries (148 g cup)
TURKEY_GROUND = 171506  # Ground turkey, cooked
SHRIMP = 175180         # Shrimp, cooked
TUNA = 173709           # Tuna, light, canned in water, drained
QUINOA = 168917         # Quinoa, cooked (185 g cup)
LENTILS = 172421        # Lentils, boiled (198 g cup)
HUMMUS = 174289         # Hummus (15 g tbsp)
OJ = 169098             # Orange juice (248 g cup)
ORANGE = 169097         # Orange (131 g medium)
PASTA_SAUCE = 2345258   # Spaghetti sauce
BAGEL = 174899          # Bagel, plain (105 g medium)
PARMESAN = 171247       # Parmesan, grated (5 g tbsp)
HALF_HALF = 171255      # Half and half (15 g tbsp)
COFFEE = 171890         # Coffee, brewed (237 g cup)
BACON = 167914          # Bacon, baked (8 g slice cooked)
SIRLOIN = 168729        # Top sirloin, broiled
PANCAKE = 2343787       # Pancakes, plain (50 g medium)

CASES = [
    # ---------------- stated amounts: should be near-exact -----------------
    dict(text="150 g grilled chicken breast", cat="usda", tol=0.10,
         parts=[(CHICKEN_BREAST, 150)]),
    dict(text="6 oz chicken breast", cat="usda", tol=0.10,
         parts=[(CHICKEN_BREAST, 170)]),
    dict(text="1 cup of white rice", cat="usda", tol=0.12,
         parts=[(RICE_WHITE, 158)]),
    dict(text="2 large eggs", cat="usda", tol=0.10, parts=[(EGG, 100)]),
    dict(text="3 scrambled eggs", cat="usda", tol=0.20,
         parts=[(EGG_SCRAMBLED, 183)]),
    dict(text="1 tbsp olive oil", cat="usda", tol=0.10,
         parts=[(OLIVE_OIL, 13.5)]),
    dict(text="2 tablespoons of peanut butter", cat="usda", tol=0.10,
         parts=[(PB, 32)]),
    dict(text="tablespoon and a half of peanut butter", cat="usda", tol=0.12,
         parts=[(PB, 24)]),
    dict(text="1 cup whole milk", cat="usda", tol=0.10, parts=[(MILK_WHOLE, 244)]),
    dict(text="milk 2 cups while milk", cat="usda", tol=0.12,
         parts=[(MILK_WHOLE, 488)]),
    dict(text="a glass of 2% milk", cat="usda", tol=0.20, parts=[(MILK_2, 244)]),
    dict(text="half cup of 4% cottage cheese", cat="usda", tol=0.12,
         parts=[(COTTAGE_4, 113)]),
    dict(text="1 cup 4% cottage cheese", cat="usda", tol=0.12,
         parts=[(COTTAGE_4, 225)]),
    dict(text="1 cup nonfat plain greek yogurt", cat="usda", tol=0.15,
         parts=[(GREEK_NF, 227)]),
    dict(text="0% Fat Greek yogurt and I had about a cup and 1/4 of it",
         cat="usda", tol=0.15, parts=[(GREEK_NF, 284)]),
    dict(text="1/2 cup dry oats", cat="usda", tol=0.12, parts=[(OATS_DRY, 40)]),
    dict(text="1 oz cheddar cheese", cat="usda", tol=0.10, parts=[(CHEDDAR, 28.35)]),
    dict(text="4 oz ground beef patty 85/15", cat="usda", tol=0.15,
         parts=[(BEEF_85, 113)]),
    dict(text="6 oz salmon fillet cooked", cat="usda", tol=0.15,
         parts=[(SALMON, 170)]),
    dict(text="200g baked sweet potato", cat="usda", tol=0.10,
         parts=[(SWEET_POTATO, 200)]),
    dict(text="1 tbsp butter", cat="usda", tol=0.10, parts=[(BUTTER, 14.2)]),
    dict(text="2 cups cooked pasta", cat="usda", tol=0.15, parts=[(PASTA, 248)]),
    dict(text="1 cup black beans", cat="usda", tol=0.12, parts=[(BLACK_BEANS, 172)]),
    dict(text="half a block of firm tofu, about 200 g", cat="usda", tol=0.12,
         parts=[(TOFU, 200)]),
    dict(text="1 oz almonds", cat="usda", tol=0.10, parts=[(ALMONDS, 28.35)]),
    dict(text="5 oz can of tuna in water drained", cat="usda", tol=0.20,
         parts=[(TUNA, 113)]),
    dict(text="1 cup cooked quinoa", cat="usda", tol=0.12, parts=[(QUINOA, 185)]),
    dict(text="1 cup lentils cooked", cat="usda", tol=0.12, parts=[(LENTILS, 198)]),
    dict(text="4 oz shrimp", cat="usda", tol=0.15, parts=[(SHRIMP, 113)]),
    dict(text="8 oz sirloin steak", cat="usda", tol=0.15, parts=[(SIRLOIN, 227)]),
    dict(text="1 cup blueberries", cat="usda", tol=0.12, parts=[(BLUEBERRY, 148)]),
    dict(text="2 tbsp hummus", cat="usda", tol=0.15, parts=[(HUMMUS, 30)]),
    dict(text="a cup of orange juice", cat="usda", tol=0.12, parts=[(OJ, 248)]),
    dict(text="3 slices of bacon", cat="usda", tol=0.25, parts=[(BACON, 24)]),
    dict(text="4 slices white bread with raspberry jam", cat="usda", tol=0.25,
         parts=[(BREAD_WHITE, 100), (JAM, 40)]),

    # ---------------- standard portions: "a banana" means a medium one -----
    dict(text="a banana", cat="usda", tol=0.20, parts=[(BANANA, 118)]),
    dict(text="an apple", cat="usda", tol=0.25, parts=[(APPLE, 182)]),
    dict(text="an orange", cat="usda", tol=0.25, parts=[(ORANGE, 131)]),
    dict(text="a baked potato", cat="usda", tol=0.35, parts=[(POTATO_BAKED, 173)]),
    dict(text="a plain bagel", cat="usda", tol=0.25, parts=[(BAGEL, 105)]),
    dict(text="two pieces of white bread", cat="usda", tol=0.20,
         parts=[(BREAD_WHITE, 54)]),
    dict(text="a slice of whole wheat bread", cat="usda", tol=0.20,
         parts=[(BREAD_WHEAT, 32)]),
    dict(text="half an avocado", cat="usda", tol=0.30, parts=[(AVOCADO, 75)]),
    dict(text="a cup of black coffee", cat="usda", tol=1.0, parts=[(COFFEE, 237)]),
    dict(text="⅓ bell pepper, 10 small baby carrots, celery", cat="usda", tol=0.35,
         parts=[(RED_PEPPER, 40), (BABY_CARROT, 100), (CELERY, 40)]),

    # ---------------- meals with the parts spelled out ---------------------
    dict(text="2 eggs scrambled in 1 tbsp butter with 2 slices of whole wheat toast",
         cat="usda", tol=0.15,
         parts=[(EGG, 100), (BUTTER, 14.2), (BREAD_WHEAT, 64)]),
    dict(text="oatmeal made with 1/2 cup oats and 1 cup of 2% milk, a tablespoon of honey",
         cat="usda", tol=0.15, parts=[(OATS_DRY, 40), (MILK_2, 244), (HONEY, 21)]),
    dict(text="6 oz chicken breast, 1 cup rice and 1 cup broccoli", cat="usda",
         tol=0.12, parts=[(CHICKEN_BREAST, 170), (RICE_WHITE, 158), (BROCCOLI, 156)]),
    dict(text="a peanut butter sandwich with 2 tbsp peanut butter on 2 slices of white bread",
         cat="usda", tol=0.15, parts=[(PB, 32), (BREAD_WHITE, 54)]),
    dict(text="peanut butter and jelly sandwich which contained approximately 2 tablespoons of peanut butter",
         cat="usda", tol=0.20, parts=[(PB, 32), (BREAD_WHITE, 54), (JAM, 20)]),
    dict(text="one piece of bread with a serving of cream cheese on top and some Jam",
         cat="usda", tol=0.30, parts=[(BREAD_WHITE, 27), (CREAM_CHEESE, 29), (JAM, 20)]),
    dict(text="two pancakes with a little bit of maple syrup", cat="usda", tol=0.35,
         parts=[(PANCAKE, 100), (MAPLE, 20)]),
    dict(text="a bowl of spaghetti, about 2 cups pasta with a cup of marinara and 2 tbsp parmesan",
         cat="usda", tol=0.15,
         parts=[(PASTA, 248), (PASTA_SAUCE, 260), (PARMESAN, 10)]),
    dict(text="salad with 2 cups spinach, half an avocado and 4 oz grilled chicken",
         cat="usda", tol=0.20,
         parts=[(SPINACH, 60), (AVOCADO, 75), (CHICKEN_BREAST, 113)]),
    dict(text="coffee with 2 tablespoons of half and half", cat="usda", tol=0.25,
         parts=[(COFFEE, 237), (HALF_HALF, 30)]),
    dict(text="a pork chop with a cup of rice and half a cup of black beans",
         cat="usda", tol=0.30,
         parts=[(PORK_CHOP, 150), (RICE_WHITE, 158), (BLACK_BEANS, 86)]),
    dict(text="greek yogurt, 1 cup, with a cup of strawberries and a tablespoon of honey",
         cat="usda", tol=0.15,
         parts=[(GREEK_NF, 227), (STRAWBERRY, 152), (HONEY, 21)]),
    dict(text="2 flour tortillas with 4 oz ground turkey and 1 oz cheddar", cat="usda",
         tol=0.25, parts=[(TORTILLA, 90), (TURKEY_GROUND, 113), (CHEDDAR, 28.35)]),
    dict(text="this morning I had two eggs and a banana and then a cup of whole milk",
         cat="usda", tol=0.15, parts=[(EGG, 100), (BANANA, 118), (MILK_WHOLE, 244)]),

    # ---------------- packaged foods, from the label -----------------------
    dict(text="a can of coke", cat="label", tol=0.08,
         truth=dict(kcal=140, protein_g=0, carbs_g=39, sugar_g=39, sodium_mg=45, caffeine_mg=34)),
    dict(text="red bull 8.4 oz", cat="label", tol=0.10,
         truth=dict(kcal=110, protein_g=0.5, carbs_g=28, sugar_g=27, caffeine_mg=80)),
    dict(text="a 16 oz monster energy", cat="label", tol=0.15,
         truth=dict(kcal=210, protein_g=0, carbs_g=54, sugar_g=54, caffeine_mg=160)),
    dict(text="Premier Protein shake", cat="label", tol=0.12,
         truth=dict(kcal=160, protein_g=30, carbs_g=5, fat_g=3)),
    dict(text="fairlife core power 26g", cat="label", tol=0.12,
         truth=dict(kcal=170, protein_g=26, carbs_g=8, fat_g=4.5)),
    dict(text="a quest bar", cat="label", tol=0.15,
         truth=dict(kcal=190, protein_g=21, carbs_g=22, fat_g=8)),
    dict(text="built bar", cat="label", tol=0.15,
         truth=dict(kcal=140, protein_g=17, carbs_g=18, fat_g=4.5)),
    dict(text="one scoop of optimum nutrition gold standard whey", cat="label", tol=0.12,
         truth=dict(kcal=120, protein_g=24, carbs_g=3, fat_g=1.5)),
    dict(text="3 oreos", cat="label", tol=0.10,
         truth=dict(kcal=160, protein_g=1, carbs_g=25, fat_g=7, sugar_g=14)),
    dict(text="a 1 oz bag of lays classic chips", cat="label", tol=0.10,
         truth=dict(kcal=160, protein_g=2, carbs_g=15, fat_g=10, sodium_mg=170)),
    dict(text="2 tbsp jif creamy peanut butter", cat="label", tol=0.10,
         truth=dict(kcal=190, protein_g=7, carbs_g=8, fat_g=16)),
    dict(text="a slice of dave's killer bread 21 whole grains", cat="label", tol=0.12,
         truth=dict(kcal=110, protein_g=5, carbs_g=22, fat_g=1.5)),
    dict(text="1 cup fairlife 2% milk", cat="label", tol=0.10,
         truth=dict(kcal=120, protein_g=13, carbs_g=6, fat_g=4.5)),
    dict(text="chobani plain nonfat greek yogurt cup", cat="label", tol=0.15,
         truth=dict(kcal=80, protein_g=14, carbs_g=6, fat_g=0)),
    dict(text="one serving of protein pancakes from Kodiak the one like with the bear",
         cat="label", tol=0.15, truth=dict(kcal=190, protein_g=14, carbs_g=30, fat_g=2)),
    dict(text="kirkland protein bar", cat="label", tol=0.15,
         truth=dict(kcal=190, protein_g=21, carbs_g=22, fat_g=7)),

    # ---------------- restaurant items, from the chain's own figures -------
    dict(text="a big mac", cat="label", tol=0.12,
         truth=dict(kcal=590, protein_g=25, carbs_g=46, fat_g=34, sodium_mg=1050)),
    dict(text="egg mcmuffin", cat="label", tol=0.12,
         truth=dict(kcal=310, protein_g=17, carbs_g=30, fat_g=13)),
    dict(text="chick-fil-a 8 count nuggets", cat="label", tol=0.12,
         truth=dict(kcal=250, protein_g=27, carbs_g=11, fat_g=11)),
    dict(text="wendy's baconator", cat="label", tol=0.12,
         truth=dict(kcal=960, protein_g=58, carbs_g=38, fat_g=66)),
    dict(text="2 taco bell crunchy tacos", cat="label", tol=0.12,
         truth=dict(kcal=340, protein_g=16, carbs_g=26, fat_g=18)),
    dict(text="grande latte from starbucks", cat="label", tol=0.12,
         truth=dict(kcal=190, protein_g=13, carbs_g=19, fat_g=7, caffeine_mg=150)),
    dict(text="chipotle bowl with chicken, white rice, black beans, mild salsa and cheese",
         cat="label", tol=0.15,
         truth=dict(kcal=655, protein_g=50, carbs_g=67, fat_g=22)),

    # ---------------- nothing can be known: must ask -----------------------
    dict(text="a solid breakfast", cat="vague"),
    dict(text="leftovers", cat="vague"),
    dict(text="the usual", cat="vague"),
    dict(text="some food at the party", cat="vague"),
]


COLUMNS = ["kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g",
           "sat_fat_g", "sodium_mg", "caffeine_mg"]


BRANDED_DB = os.path.expanduser(
    os.environ.get("NS_BRANDED_DB", "~/.cache/fdc-bench/branded_full.db"))
_branded = None


def truth_of(db, case):
    """-> dict of nutrient -> value, or None for a vague case."""
    global _branded
    if case["cat"] == "vague":
        return None
    if "truth" in case:
        return case["truth"]
    if "label_parts" in case:
        if _branded is None:
            _branded = sqlite3.connect(BRANDED_DB)
        total = {k: 0.0 for k in COLUMNS}
        for fdc, servings in case["label_parts"]:
            row = _branded.execute(f"SELECT serving_size, {', '.join(COLUMNS)} FROM product "
                                   f"WHERE fdc_id=?", (fdc,)).fetchone()
            if row is None or row[0] is None:
                raise SystemExit(f"truth_set: branded {fdc} missing ({case['text']!r})")
            for k, v in zip(COLUMNS, row[1:]):
                if v is None:
                    total[k] = None
                elif total[k] is not None:
                    total[k] += v * row[0] / 100.0 * servings
        return total
    total = {k: 0.0 for k in COLUMNS}
    for fdc, grams in case["parts"]:
        row = db.execute(f"SELECT {', '.join(COLUMNS)} FROM food WHERE fdc_id=?",
                         (fdc,)).fetchone()
        if row is None:
            raise SystemExit(f"truth_set: fdc_id {fdc} is not in food.db ({case['text']!r})")
        for k, v in zip(COLUMNS, row):
            total[k] += (v or 0.0) * grams / 100.0
    return total
