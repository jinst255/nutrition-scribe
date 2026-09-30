#!/usr/bin/env python3
"""
A second held-out answer key, written 2026-09-24 AFTER tools/truth_holdout.py had
been looked at while fixing the resolver (so that one is no longer blind).

Rules for this file: the cases and their truths were written from USDA rows and
real package labels BEFORE the resolver was run on any of them, and nothing in
the resolver may be changed because of a miss here. It is the honest number.

Same format as tools/truth_set.py:
  parts        [(fdc_id in food.db, grams)]
  label_parts  [(branded fdc_id in branded_full.db, servings)]
"""

CASES = [
    # --- generic food, amount said -----------------------------------------
    dict(text="a cup of cooked brown rice", cat="usda", tol=0.15, parts=[(169704, 202)]),
    dict(text="3 oz grilled salmon", cat="usda", tol=0.15, parts=[(175168, 85)]),
    dict(text="a medium orange", cat="usda", tol=0.20, parts=[(169097, 131)]),
    dict(text="2 tablespoons of hummus", cat="usda", tol=0.15, parts=[(174289, 30)]),
    dict(text="an ounce of cashews", cat="usda", tol=0.12, parts=[(170571, 28.35)]),
    dict(text="one cup of cooked quinoa", cat="usda", tol=0.12, parts=[(168917, 185)]),
    dict(text="half a cup of blueberries", cat="usda", tol=0.15, parts=[(171711, 74)]),
    dict(text="two strips of bacon", cat="usda", tol=0.20, parts=[(167914, 16.2)]),
    dict(text="8 oz of 2% milk", cat="usda", tol=0.12, parts=[(171267, 244)]),
    dict(text="a cup of raw spinach", cat="usda", tol=0.25, parts=[(168462, 30)]),
    dict(text="a roasted chicken thigh, no skin", cat="usda", tol=0.25, parts=[(172388, 116)]),
    dict(text="3/4 cup of oatmeal cooked with water", cat="usda", tol=0.15, parts=[(171675, 175)]),
    dict(text="a slice of whole wheat bread", cat="usda", tol=0.15, parts=[(172688, 32)]),
    dict(text="a tablespoon of butter", cat="usda", tol=0.10, parts=[(173410, 14.2)]),
    dict(text="half a cup of steamed broccoli", cat="usda", tol=0.20, parts=[(169967, 78)]),
    dict(text="a small banana", cat="usda", tol=0.15, parts=[(173944, 101)]),
    dict(text="a tablespoon of olive oil", cat="usda", tol=0.10, parts=[(171413, 13.5)]),
    dict(text="a whole avocado", cat="usda", tol=0.20, parts=[(171705, 201)]),
    dict(text="10 almonds", cat="usda", tol=0.20, parts=[(170567, 12)]),
    dict(text="a cup of strawberries", cat="usda", tol=0.20, parts=[(167762, 152)]),

    # --- several foods, the way people say them ------------------------------
    dict(text="a cup of whole milk and a medium banana", cat="usda", tol=0.15,
         parts=[(171265, 244), (173944, 118)]),
    dict(text="5 ounces of grilled chicken breast with a cup of brown rice", cat="usda", tol=0.15,
         parts=[(171477, 141.75), (169704, 202)]),
    dict(text="two slices of whole wheat toast with a tablespoon of butter", cat="usda", tol=0.15,
         parts=[(172688, 64), (173410, 14.2)]),
    dict(text="so I had like a peanut butter toast, one slice of whole wheat and 2 tablespoons of peanut butter",
         cat="usda", tol=0.15, parts=[(172688, 32), (174266, 32)]),
    dict(text="greek yogurt, plain nonfat, 6 ounces, with half a cup of blueberries", cat="usda", tol=0.15,
         parts=[(170894, 170), (171711, 74)]),

    # --- packaged food, real labels ------------------------------------------
    dict(text="a chocolate sea salt rxbar", cat="label_db", tol=0.10, label_parts=[(2653556, 1)]),
    dict(text="a bag of lays classic chips", cat="label_db", tol=0.10, label_parts=[(2618318, 1)]),
    dict(text="a can of sprite", cat="label_db", tol=0.10, label_parts=[(2200488, 1)]),
    dict(text="2 eggo homestyle waffles", cat="label_db", tol=0.10, label_parts=[(2615456, 1)]),
    dict(text="a glass of tropicana orange juice", cat="label_db", tol=0.15, label_parts=[(2501498, 1)]),
    dict(text="a premier protein chocolate shake", cat="label_db", tol=0.10, label_parts=[(2602071, 1)]),
    dict(text="a snickers bar", cat="label_db", tol=0.10, label_parts=[(2596124, 1)]),
    dict(text="an oikos triple zero vanilla cup", cat="label_db", tol=0.15, label_parts=[(2754214, 1)]),
    dict(text="half a cup of quaker old fashioned oats", cat="label_db", tol=0.10, label_parts=[(2270012, 1)]),
    dict(text="a siggi's vanilla yogurt", cat="label_db", tol=0.20, label_parts=[(2671219, 1)]),
    dict(text="27 cheez-its", cat="label_db", tol=0.15, label_parts=[(2658679, 1)]),
    dict(text="a kind dark chocolate nuts and sea salt bar", cat="label_db", tol=0.10, label_parts=[(2601851, 1)]),
    dict(text="chobani vanilla nonfat greek yogurt", cat="label_db", tol=0.10, label_parts=[(2652627, 1)]),

    # --- nothing to know -------------------------------------------------------
    dict(text="just some snacks", cat="vague", tol=0.0),
    dict(text="the usual", cat="vague", tol=0.0),
]
