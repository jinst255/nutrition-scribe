package com.nutritionscribe.shared.resolve

/**
 * A barcode read out loud: "barcode" (or "bar code") first, then 8, 12, 13 or 14
 * digits, as numbers, digit words or both. [Code.valid] is the GTIN check digit:
 * a misheard digit almost always breaks it, so the app can say "say it again"
 * instead of looking up the wrong product.
 */
object SpokenBarcode {
    data class Code(val digits: String, val valid: Boolean)

    private val LEAD = Regex("""^\s*bar\s*code\b[\s:,.-]*""", RegexOption.IGNORE_CASE)
    private val WORDS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "two" to "2", "to" to "2", "too" to "2", "three" to "3",
        "four" to "4", "for" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )
    private val LENGTHS = setOf(8, 12, 13, 14)

    fun parse(text: String): Code? {
        val m = LEAD.find(text) ?: return null
        val rest = text.substring(m.range.last + 1)
        val digits = StringBuilder()
        for (token in rest.lowercase().split(Regex("""[\s,.:;-]+""")).filter { it.isNotEmpty() }) {
            when {
                token.all { it.isDigit() } -> digits.append(token)
                token in WORDS -> digits.append(WORDS.getValue(token))
                else -> return null   // anything else: a sentence, not a number read out
            }
        }
        val d = digits.toString()
        if (d.length !in LENGTHS) return null
        return Code(d, checkDigitOk(d))
    }

    /** GTIN rule: from the right, weights 3,1,3,1… on all but the last digit. */
    fun checkDigitOk(d: String): Boolean {
        val body = d.dropLast(1).reversed()
        val sum = body.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == d.last() - '0'
    }
}
