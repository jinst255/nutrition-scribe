"""
Cases whose truth is a real PACKAGE LABEL, read from USDA's Branded Foods data
(the manufacturers' own label submissions), never typed in from memory.

This is the set that measures what the user measures: they check the app
against the Nutrition Facts on what they ate. Each part is (branded fdc_id,
servings), servings counted in the LABEL's serving, and the truth is computed
from ~/.cache/fdc-bench/branded_full.db (tools/build_branded_db.py).

Run with:  python3 tools/bench_truth.py food.db METHOD --labels

Tolerance is 10% for a single product: a label is exact, so the right product
should land on it, and flavours of one product line differ by about that much.
Cases where the words cannot pin the product ("mission tortillas, medium") get
more room, and are marked ambiguous: they are the ones a follow-up question or a
pick list exists for.
"""

L = "label_db"

CASES = [
    # --- from the user's own log ------------------------------------------
    dict(text="built bar brownie batter", cat=L, tol=0.10, label_parts=[(2624430, 1)]),
    dict(text="Quaker Chewy Bars, 2 bars", cat=L, tol=0.15, ambiguous=True,
         label_parts=[(2588454, 2)]),
    dict(text="Ritz Peanut Butter Crackers, 1 sleeve", cat=L, tol=0.15, ambiguous=True,
         label_parts=[(2679063, 1)]),
    dict(text="2 tortillas. mission brand, medium sized.", cat=L, tol=0.20, ambiguous=True,
         label_parts=[(2657258, 2)]),
    dict(text="one serving of protein pancakes from Kodiak the one like with the bear",
         cat=L, tol=0.15, ambiguous=True, label_parts=[(2626753, 1)]),
    dict(text="17g built bar", cat=L, tol=0.12, label_parts=[(2624430, 1)]),

    # --- protein products, where a friend's log will live -----------------
    dict(text="premier protein chocolate shake", cat=L, tol=0.10, label_parts=[(2602071, 1)]),
    dict(text="core power vanilla 26g", cat=L, tol=0.10, label_parts=[(2420507, 1)]),
    dict(text="fairlife elite 42g chocolate shake", cat=L, tol=0.10, label_parts=[(2668980, 1)]),
    dict(text="muscle milk chocolate peanut butter 40g protein shake", cat=L, tol=0.10,
         label_parts=[(2577741, 1)]),
    dict(text="quest chocolate chip cookie dough bar", cat=L, tol=0.10, label_parts=[(2629232, 1)]),
    dict(text="rxbar chocolate sea salt", cat=L, tol=0.10, label_parts=[(2653556, 1)]),
    dict(text="oikos pro mixed berry cup", cat=L, tol=0.10, label_parts=[(2755242, 1)]),
    dict(text="siggi's plain skyr", cat=L, tol=0.10, label_parts=[(2744861, 1)]),
    dict(text="chobani plain nonfat greek yogurt, one container", cat=L, tol=0.10,
         label_parts=[(2604347, 1)]),
    dict(text="a fage 0% cherry pomegranate cup", cat=L, tol=0.10, label_parts=[(2286695, 1)]),
    dict(text="1 cup fairlife 2% milk", cat=L, tol=0.10, label_parts=[(2624248, 1)]),

    # --- snacks and drinks --------------------------------------------------
    dict(text="2 tbsp jif creamy peanut butter", cat=L, tol=0.10, label_parts=[(2501928, 1)]),
    dict(text="a tablespoon of skippy creamy", cat=L, tol=0.12, label_parts=[(2674433, 0.5)]),
    dict(text="a can of cherry coke", cat=L, tol=0.10, label_parts=[(2743963, 1)]),
    dict(text="red bull 8.4 oz can", cat=L, tol=0.10, label_parts=[(2578053, 1)]),
    dict(text="a monster lo carb", cat=L, tol=0.25, label_parts=[(2568243, 1)]),
    dict(text="a can of celsius", cat=L, tol=0.10, label_parts=[(2655535, 1)]),
    dict(text="20 oz bottle of gatorade fruit punch", cat=L, tol=0.12, label_parts=[(2657510, 1)]),
    dict(text="3 oreos", cat=L, tol=0.10, label_parts=[(2655815, 1)]),
    dict(text="small bag of nacho cheese doritos, 1 oz", cat=L, tol=0.10,
         label_parts=[(2672168, 1)]),
    dict(text="goldfish crackers snack pack", cat=L, tol=0.15, ambiguous=True,
         label_parts=[(2758213, 1)]),
    dict(text="clif bar chocolate chip", cat=L, tol=0.10, label_parts=[(2666988, 1)]),
    dict(text="nature valley oats n honey, one pouch (2 bars)", cat=L, tol=0.12,
         label_parts=[(2754890, 2)]),
    dict(text="belvita blueberry breakfast biscuits, one pack", cat=L, tol=0.10,
         label_parts=[(2655810, 1)]),
    dict(text="handful of blue diamond almonds, about 1 oz", cat=L, tol=0.10,
         label_parts=[(2672781, 1)]),
    dict(text="1 oz planters dry roasted peanuts", cat=L, tol=0.10, label_parts=[(2672499, 1)]),
    dict(text="2 tbsp sabra classic hummus", cat=L, tol=0.10, label_parts=[(2596142, 1)]),
    dict(text="2 sargento string cheese", cat=L, tol=0.12, label_parts=[(2657544, 2)]),
    dict(text="1/4 cup tillamook shredded medium cheddar", cat=L, tol=0.10,
         label_parts=[(2669215, 1)]),
    dict(text="halo top vanilla bean, 2/3 cup", cat=L, tol=0.20, ambiguous=True,
         label_parts=[(2486243, 1)]),

    # --- breakfast and meals out of a package -----------------------------
    dict(text="2 eggo homestyle waffles", cat=L, tol=0.10, label_parts=[(2758788, 1)]),
    dict(text="a thomas english muffin", cat=L, tol=0.10, label_parts=[(2656161, 1)]),
    dict(text="honey nut cheerios cup", cat=L, tol=0.10, label_parts=[(2758668, 1)]),
    dict(text="pop tarts frosted strawberry, both pastries", cat=L, tol=0.10,
         label_parts=[(2753826, 1)]),
    dict(text="2 slices nature's own honey wheat", cat=L, tol=0.12, label_parts=[(2569022, 2)]),
    dict(text="2 slices of dave's killer bread 21 whole grains", cat=L, tol=0.12,
         label_parts=[(2674263, 2)]),
    dict(text="4 oz barilla spaghetti, dry", cat=L, tol=0.10, label_parts=[(2657542, 2)]),
    dict(text="half a cup of prego traditional sauce", cat=L, tol=0.15,
         label_parts=[(2731569, 1)]),
    dict(text="pepperoni hot pocket", cat=L, tol=0.15, ambiguous=True, label_parts=[(2758160, 1)]),
    dict(text="digiorno stuffed crust pepperoni personal pizza", cat=L, tol=0.12,
         label_parts=[(2757994, 1)]),
    dict(text="turkey and cheddar lunchables cracker stackers", cat=L, tol=0.12,
         label_parts=[(2674445, 1)]),
    dict(text="maruchan chicken ramen cup", cat=L, tol=0.10, label_parts=[(2615656, 1)]),
    dict(text="kraft mac and cheese, one serving of the dry mix", cat=L, tol=0.12,
         label_parts=[(2676052, 1)]),
    dict(text="annie's shells and aged cheddar, one serving", cat=L, tol=0.12,
         label_parts=[(2755044, 1)]),
    dict(text="4 oz oscar mayer oven roasted turkey", cat=L, tol=0.12, label_parts=[(2615760, 2)]),

    # --- a whole meal of labelled things, dictated -------------------------
    dict(text="for breakfast I had 2 eggo waffles with 2 tbsp of jif and a premier protein shake",
         cat=L, tol=0.10,
         label_parts=[(2758788, 1), (2501928, 1), (2602071, 1)]),
    dict(text="turkey sandwich, 4 oz oscar mayer turkey on 2 slices of dave's killer bread, and a string cheese",
         cat=L, tol=0.12,
         label_parts=[(2615760, 2), (2674263, 2), (2657544, 1)]),
    dict(text="a chobani nonfat plain with a clif bar after the gym", cat=L, tol=0.10,
         label_parts=[(2604347, 1), (2666988, 1)]),
]
