package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Metric
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What makes a saved meal a recipe: it is cooked as one pot and eaten a portion
 * at a time. [servings] or [potGrams] says how big the pot is (whichever the
 * person knew). A [batch] is this week's pot when it was made a little
 * differently ("made chili, added half a zucchini"): portions use it for
 * [Recipes.BATCH_DAYS] days, then the recipe again, unless they keep the change.
 */
@Serializable
data class Recipe(
    val servings: Double? = null,
    val potGrams: Double? = null,
    val batch: Batch? = null,
)

@Serializable
data class Batch(
    val madeAt: Long,
    val parts: List<MealPart>,
    /** "added zucchini", "more onion": what is different from the recipe. */
    val changes: List<String> = emptyList(),
)

/**
 * Recipes: a pot of food, saved once, logged a portion at a time. Kept in
 * [PersonalFood] (its parts are the ingredients for the whole pot; its own
 * nutrients and grams are one serving), so no database change was needed.
 */
object Recipes {

    /** How long a batch's leftovers last before a portion means the recipe again. */
    const val BATCH_DAYS = 5
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A pot with only its weight known: a serving is about this much. */
    private const val SERVING_G = 300.0

    fun keyFor(name: String): String = PersonalKeys.of(name)

    /** A recipe from its ingredients for the whole pot, and how big the pot is. */
    fun create(name: String, parts: List<MealPart>, servings: Double?, potGrams: Double?, now: Long, phrase: String = name): PersonalFood {
        val recipe = Recipe(servings = servings?.takeIf { it > 0 }, potGrams = potGrams?.takeIf { it > 0 })
        return withServing(
            PersonalFood(key = keyFor(name), phrase = phrase, name = name.trim(), nutrients = emptyMap(), parts = parts,
                updatedAt = now, unit = "serving", amount = 1.0, recipe = recipe),
            now,
        )
    }

    /** The ingredients a portion comes from now: this week's batch, or the recipe. */
    fun activeParts(food: PersonalFood, now: Long): List<MealPart> =
        activeBatch(food, now)?.parts ?: food.parts

    fun activeBatch(food: PersonalFood, now: Long): Batch? =
        food.recipe?.batch?.takeIf { now - it.madeAt <= BATCH_DAYS * DAY_MS }

    /** The whole pot's weight: the weighed pot, else its ingredients' weights when every one is known. */
    fun potGrams(food: PersonalFood, now: Long): Double? {
        food.recipe?.potGrams?.let { return it }
        val parts = activeParts(food, now)
        return parts.mapNotNull { it.grams }.takeIf { it.size == parts.size && it.isNotEmpty() }?.sum()
    }

    fun servings(food: PersonalFood, now: Long): Double =
        food.recipe?.servings ?: potGrams(food, now)?.let { max(1.0, (it / SERVING_G).roundToInt().toDouble()) } ?: 4.0

    /** Its own numbers set to one serving of what a portion comes from now. */
    fun withServing(food: PersonalFood, now: Long): PersonalFood {
        val total = sumNutrients(activeParts(food, now).map { it.nutrients })
        val n = servings(food, now)
        return food.copy(
            nutrients = total.mapValues { it.value / n },
            grams = potGrams(food, now)?.let { it / n },
        )
    }

    data class Portion(val grams: Double?, val nutrients: Map<String, Double>, val note: String, val amount: Double, val unit: String)

    /**
     * How much of the pot [item] says: "a bowl of chili" is one serving, "half a
     * bowl" a half, "2 servings" two, "300 g" that share of the pot's weight.
     */
    fun portion(food: PersonalFood, item: ItemParser.Item, now: Long): Portion {
        val parts = activeParts(food, now)
        val total = sumNutrients(parts.map { it.nutrients })
        val pot = potGrams(food, now)
        val n = servings(food, now)
        val batch = if (activeBatch(food, now) != null) " · this week's batch" else ""
        val grams = when (item.unit) {
            "g" -> item.amount
            "oz" -> item.amount?.times(28.3495)
            "lb" -> item.amount?.times(453.592)
            "cup" -> if (pot != null) (item.amount ?: 1.0) * 240.0 else null
            else -> null
        }
        if (grams != null && pot != null && pot > 0) {
            val f = grams / pot
            return Portion(grams, total.mapValues { it.value * f }, "${grams.roundToInt()} g of a ${pot.roundToInt()} g pot$batch", grams, "g")
        }
        val count = item.amount ?: 1.0
        val f = count / n
        val servingWord = if (count == 1.0) "serving" else "servings"
        return Portion(pot?.let { it * f }, total.mapValues { it.value * f },
            "${Units.format(count)} $servingWord of ${Units.format(n)}$batch", count, "serving")
    }

    /** What is different about [next] from [base], in words: "added zucchini", "no beans", "more onion". */
    fun changes(base: List<MealPart>, next: List<MealPart>): List<String> {
        fun id(p: MealPart) = PersonalKeys.of(p.name)
        val before = base.associateBy(::id)
        val after = next.associateBy(::id)
        val out = mutableListOf<String>()
        for ((k, p) in after) {
            val old = before[k]
            when {
                old == null -> out += "added ${ShortName.of(p.name).lowercase()}"
                old.grams != null && p.grams != null && old.grams > 0 && abs(p.grams - old.grams) / old.grams > 0.1 ->
                    out += "${if (p.grams > old.grams) "more" else "less"} ${ShortName.of(p.name).lowercase()}"
            }
        }
        for ((k, p) in before) if (k !in after) out += "no ${ShortName.of(p.name).lowercase()}"
        return out
    }

    /** This week's pot, made with [parts]. */
    fun startBatch(food: PersonalFood, parts: List<MealPart>, now: Long): PersonalFood {
        val r = food.recipe ?: Recipe()
        return withServing(food.copy(recipe = r.copy(batch = Batch(now, parts, changes(food.parts, parts))), updatedAt = now), now)
    }

    /** "Keep this change": the batch becomes the recipe. */
    fun keepBatch(food: PersonalFood, now: Long): PersonalFood {
        val b = food.recipe?.batch ?: return food
        return withServing(food.copy(parts = b.parts, recipe = food.recipe.copy(batch = null), updatedAt = now), now)
    }

    /** Back to the recipe as saved, this week's changes dropped. */
    fun dropBatch(food: PersonalFood, now: Long): PersonalFood =
        withServing(food.copy(recipe = food.recipe?.copy(batch = null), updatedAt = now), now)

    /**
     * "Made chili, added half a zucchini", "I made a batch of mom's chili with
     * extra beans and no onion": the recipe's name and what was different. Null
     * when the sentence is not about making something.
     */
    data class BatchSaid(val name: String, val added: String?, val without: List<String>)

    private val LEFT_OUT = Regex("\\s*,?\\s*(?:and\\s+)?(?:but\\s+)?(?:without|no|minus|skipped|left out)\\s+(?:the\\s+|any\\s+)?([a-z' ]+?)(?=\\s*(?:,|\\band\\b|$))")

    fun batchSaid(text: String): BatchSaid? {
        val low = text.lowercase().trim().trimEnd('.', '!')
        val m = Regex("^(?:i\\s+|we\\s+|just\\s+)?(?:made|cooked|baked|make|making|whipped up|put together)\\s+" +
            "(?:a\\s+(?:new\\s+|fresh\\s+|big\\s+)?(?:batch|pot|pan|tray)\\s+of\\s+|some\\s+|the\\s+|my\\s+|our\\s+)?(.+)$").find(low) ?: return null
        var rest = m.groupValues[1]
        val without = mutableListOf<String>()
        LEFT_OUT.findAll(rest).toList().forEach {
            without += it.groupValues[1].trim()
        }
        rest = LEFT_OUT.replace(rest, "")
        val split = Regex("\\s*,?\\s*(?:and\\s+)?(?:but\\s+)?(?:i\\s+)?(?:added|adding|plus|this time with|with extra|with)\\s+").find(rest)
        val name = (if (split != null) rest.substring(0, split.range.first) else rest).trim().trimEnd(',').trim()
        val added = split?.let { rest.substring(it.range.last + 1).trim().trimEnd(',').ifBlank { null } }
        if (name.isBlank()) return null
        return BatchSaid(name, added, without)
    }

    /** Their recipe called [words], if they have one. */
    fun find(all: List<PersonalFood>, words: String): PersonalFood? {
        val key = keyFor(words)
        if (key.isBlank()) return null
        val recipes = all.filter { it.recipe != null }
        recipes.firstOrNull { it.key == key }?.let { return it }
        recipes.firstOrNull { PersonalKeys.sameFood(it.key, key) }?.let { return it }
        // "a bowl of chili" for "Mom's chili": every word they said is in its name, the
        // name's own words (not "mom's", "homemade") were all said, and only one fits.
        // "chicken" is not "Chicken soup".
        val said = key.split(' ').toSet()
        return recipes.filter { r ->
            val name = r.key.split(' ').toSet()
            val core = name.filter { !it.endsWith("'s") && it !in WHOSE }
            said.all { it in name } && core.all { it in said }
        }.singleOrNull()
    }

    /** Words in a recipe's name that say whose or what sort, not what it is. */
    private val WHOSE = setOf("mom", "moms", "mum", "grandma", "grandmas", "homemade", "home", "made", "famous", "special", "best",
        "easy", "classic", "my", "our", "favorite", "favourite")

    /** Lines of a recipe card that are ingredients: "1 lb ground beef", "½ zucchini, diced", "- salt to taste". */
    fun cardIngredients(ocr: String): List<String> {
        val lines = ocr.lines().map { it.trim().trimStart('-', '•', '*', '·', '◦').trim() }.filter { it.isNotBlank() }
        val start = lines.indexOfFirst { Regex("^ingredients?:?$", RegexOption.IGNORE_CASE).matches(it) }
        val stop = lines.indexOfFirst { Regex("^(directions|instructions|method|steps|preparation)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val body = lines.subList(if (start >= 0) start + 1 else 0, if (stop > start) stop else lines.size)
        val amount = Regex("^(?:\\d|[¼½¾⅓⅔⅛]|a\\s|an\\s|one\\s|two\\s|three\\s|half\\s|pinch|dash|salt|pepper)", RegexOption.IGNORE_CASE)
        return body.filter { amount.containsMatchIn(it) && it.length <= 80 && !it.endsWith(":") }
    }

    /**
     * What "Makes" was typed as: "8" or "8 servings" is servings; "1800 g",
     * "1.8 kg", "4 lb" is the pot's weight. Both nulls when it says neither.
     */
    fun yieldOf(text: String): Pair<Double?, Double?> {
        val t = FoodQuery.normaliseNumbers(text.lowercase().trim())
        val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*([a-z]*)").find(t) ?: return null to null
        val n = m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 } ?: return null to null
        return when (m.groupValues[2]) {
            "g", "gram", "grams" -> null to n
            "kg", "kilo", "kilos", "kilogram", "kilograms" -> null to n * 1000
            "lb", "lbs", "pound", "pounds" -> null to n * 453.592
            "oz", "ounce", "ounces" -> null to n * 28.3495
            "", "serving", "servings", "bowl", "bowls", "portion", "portions", "people", "person", "plates" -> n to null
            else -> n to null
        }
    }

    /** Calories in one serving, for lists. */
    fun perServingCalories(food: PersonalFood): Double? = food.nutrients[Metric.CALORIES.key]

    internal fun wordsOf(s: String) = FoodQuery.contentWords(s).toSet()
}
