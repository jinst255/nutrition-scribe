package com.nutritionscribe.shared.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Plasma donation: what it costs in protein, and when the next one is allowed.
 *
 * THE PROTEIN
 * -----------
 * FDA's collection nomogram sets how much plasma may be taken by body weight:
 * 625 mL under 150 lb, 750 mL up to 174 lb, 800 mL from 175 lb (690 / 825 /
 * 880 mL counting anticoagulant). Plasma carries about 7 g of protein per
 * decilitre, which is why centres test it before every donation, so a donation
 * removes 44–56 g. The body rebuilds it over the next day or two, so half goes on
 * the donation day's protein and half on the next day's.
 *
 * THE RULE
 * --------
 * 21 CFR 640.65(b)(8): "collection shall not occur less than 2 days apart or
 * more frequently than twice in a 7-day period." The button refuses what a centre would refuse, so
 * a mistaken tap can't double the adjustment.
 */
object PlasmaDonation {

    private const val PROTEIN_G_PER_ML = 0.07
    private const val LB = 0.45359237

    /** Plasma volume collected for this body weight, mL (FDA nomogram, anticoagulant excluded). */
    fun plasmaMl(weightKg: Double): Double = when {
        weightKg < 150 * LB -> 625.0
        weightKg < 175 * LB -> 750.0
        else -> 800.0
    }

    /** Protein that leaves with one donation, grams, rounded to a whole number. */
    fun proteinLost(weightKg: Double): Double = (plasmaMl(weightKg) * PROTEIN_G_PER_ML).roundToInt().toDouble()

    /** Extra protein owed on [day], from donations on it and the day before. */
    fun extraProtein(day: LocalDate, donations: Collection<LocalDate>, weightKg: Double): Double {
        val half = proteinLost(weightKg) / 2.0
        return donations.count { it == day || it == day.minusDays(1) } * half
    }

    sealed interface Check {
        data object Allowed : Check
        /** Already logged for [day]; tapping again takes it back. */
        data object AlreadyToday : Check
        data class TooSoon(val earliest: LocalDate, val reason: String) : Check
    }

    /** Whether a donation on [day] is one the FDA rule allows. */
    fun check(day: LocalDate, donations: Collection<LocalDate>): Check {
        if (day in donations) return Check.AlreadyToday
        // Only donations on or before [day] matter; a future date is a typo elsewhere.
        val before = donations.filter { it < day }.sortedDescending()
        val last = before.firstOrNull()
        if (last != null && ChronoUnit.DAYS.between(last, day) < 2) {
            return Check.TooSoon(last.plusDays(2), "Donations have to be at least 2 days apart.")
        }
        // Twice in any 7 days: the window ending today must hold at most one other.
        val inWindow = before.filter { ChronoUnit.DAYS.between(it, day) < 7 }
        if (inWindow.size >= 2) {
            val earliest = inWindow.sorted()[inWindow.size - 2].plusDays(7)
            return Check.TooSoon(earliest, "At most 2 donations in 7 days.")
        }
        return Check.Allowed
    }
}
