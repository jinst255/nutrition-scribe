package com.nutritionscribe.app.ui.home

/**
 * The answers to "Did you have the …?" on Home, in order.
 *
 * Owner, 11-15: "there is no no option given". The card had Yes, Part of it and
 * Something else; saying no meant finding the small ×.
 */
object PlanAnswers {
    enum class Answer(val label: String) { YES("Yes, log it"), PART("Part of it"), NO("No"), ELSE("Something else") }

    val ALL: List<Answer> = listOf(Answer.YES, Answer.PART, Answer.NO, Answer.ELSE)
}
