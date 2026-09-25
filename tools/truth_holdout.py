"""
Held-out cases: written BEFORE any tuning against truth_set.py, and not looked
at while tuning. Tuning a heuristic parser against its own test set measures how
well the test set was memorised; this set is the check on that.

Run with:  python3 tools/bench_truth.py food.db offline --holdout

Same format and same kinds of truth as truth_set.py. Do not tune against these;
if one of them drives a change, move it into truth_set.py and write a new one.
"""
from truth_set import *  # noqa: F401,F403 -- the fdc_id constants

SWISS = 171251
TURKEY_BREAST = 171496
DELI_TURKEY = 172941
GRAPES = 174683
PINEAPPLE = 169124
WATERMELON = 167765
CUCUMBER = 168409
CARROT = 170393
BROWN_RICE = 169704
CHICKPEAS = 173757
TENDERLOIN = 168250
ALMOND_MILK = 174832
CORN = 169999
PEAS = 170017
COD = 171956
FETA = 173420
SOUR_CREAM = 2341017
PEAR = 169118
MANGO = 169910
KIWI = 168153
SALSA = 2345251
BEEF_90 = 174031
THIGH = 172388
SUGAR = 169655
CHEERIOS = 173884
POPCORN = 2343702

CASES = [
    dict(text="5 oz grilled chicken thighs", cat="usda", tol=0.12, parts=[(THIGH, 142)]),
    dict(text="a cup of brown rice", cat="usda", tol=0.15, parts=[(BROWN_RICE, 195)]),
    dict(text="3 oz deli turkey", cat="usda", tol=0.15, parts=[(DELI_TURKEY, 85)]),
    dict(text="2 slices swiss cheese", cat="usda", tol=0.20, parts=[(SWISS, 56)]),
    dict(text="a cup of grapes", cat="usda", tol=0.15, parts=[(GRAPES, 151)]),
    dict(text="1 cup pineapple chunks", cat="usda", tol=0.15, parts=[(PINEAPPLE, 165)]),
    dict(text="2 cups watermelon", cat="usda", tol=0.15, parts=[(WATERMELON, 304)]),
    dict(text="half a cucumber", cat="usda", tol=0.35, parts=[(CUCUMBER, 150)]),
    dict(text="a medium carrot", cat="usda", tol=0.20, parts=[(CARROT, 61)]),
    dict(text="half cup of chickpeas", cat="usda", tol=0.15, parts=[(CHICKPEAS, 82)]),
    dict(text="4 oz pork tenderloin", cat="usda", tol=0.15, parts=[(TENDERLOIN, 113)]),
    dict(text="a cup of unsweetened almond milk", cat="usda", tol=0.30,
         parts=[(ALMOND_MILK, 262)]),
    dict(text="an ear of corn", cat="usda", tol=0.30, parts=[(CORN, 103)]),
    dict(text="1 cup of peas", cat="usda", tol=0.20, parts=[(PEAS, 160)]),
    dict(text="6 oz baked cod", cat="usda", tol=0.15, parts=[(COD, 170)]),
    dict(text="1 oz feta", cat="usda", tol=0.12, parts=[(FETA, 28.35)]),
    dict(text="a pear", cat="usda", tol=0.25, parts=[(PEAR, 178)]),
    dict(text="a mango", cat="usda", tol=0.30, parts=[(MANGO, 207)]),
    dict(text="2 kiwis", cat="usda", tol=0.25, parts=[(KIWI, 138)]),
    dict(text="3 cups air popped popcorn", cat="usda", tol=0.20, parts=[(POPCORN, 24)]),
    dict(text="1 cup cheerios with half a cup of 2% milk", cat="usda", tol=0.15,
         parts=[(CHEERIOS, 28), (MILK_2, 122)]),
    dict(text="a 90/10 burger patty, 4 oz, on a bun with a slice of cheddar",
         cat="usda", tol=0.30,
         parts=[(BEEF_90, 113), (BREAD_WHITE, 43), (CHEDDAR, 21)]),
    dict(text="two eggs fried in a teaspoon of olive oil", cat="usda", tol=0.15,
         parts=[(EGG, 100), (OLIVE_OIL, 4.5)]),
    dict(text="coffee with a teaspoon of sugar and a splash of milk", cat="usda",
         tol=0.60, parts=[(COFFEE, 237), (SUGAR, 4.2), (MILK_2, 30)]),
    dict(text="I had a turkey sandwich, like 3 oz of turkey on two slices of wheat bread with a slice of swiss",
         cat="usda", tol=0.20,
         parts=[(DELI_TURKEY, 85), (BREAD_WHEAT, 64), (SWISS, 28)]),
    dict(text="rice bowl with 1 cup brown rice, 4 oz chicken breast and a quarter cup of salsa",
         cat="usda", tol=0.15,
         parts=[(BROWN_RICE, 195), (CHICKEN_BREAST, 113), (SALSA, 65)]),
    dict(text="baked potato with a tablespoon of sour cream", cat="usda", tol=0.30,
         parts=[(POTATO_BAKED, 173), (SOUR_CREAM, 12)]),
    dict(text="about a cup and a half of cottage cheese, the 4% kind", cat="usda",
         tol=0.15, parts=[(COTTAGE_4, 338)]),
    dict(text="snack was an apple with 2 tablespoons of peanut butter", cat="usda",
         tol=0.20, parts=[(APPLE, 182), (PB, 32)]),
    dict(text="one and a half cups of whole milk", cat="usda", tol=0.12,
         parts=[(MILK_WHOLE, 366)]),

    dict(text="a can of diet coke", cat="label", tol=0.10,
         truth=dict(kcal=0, protein_g=0, carbs_g=0, sodium_mg=40, caffeine_mg=46)),
    dict(text="a clif bar", cat="label", tol=0.15,
         truth=dict(kcal=250, protein_g=10, carbs_g=44, fat_g=5)),
    dict(text="mcdonald's medium fries", cat="label", tol=0.15,
         truth=dict(kcal=320, protein_g=5, carbs_g=43, fat_g=15)),
    dict(text="chick fil a chicken sandwich", cat="label", tol=0.15,
         truth=dict(kcal=420, protein_g=29, carbs_g=41, fat_g=18)),
    dict(text="a kind bar", cat="label", tol=0.20,
         truth=dict(kcal=200, protein_g=6, carbs_g=16, fat_g=15)),
    dict(text="a 12 oz can of sprite", cat="label", tol=0.10,
         truth=dict(kcal=140, protein_g=0, carbs_g=38, sugar_g=38)),

    dict(text="dinner", cat="vague"),
    dict(text="a snack", cat="vague"),
]
