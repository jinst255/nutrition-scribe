package com.nutritionscribe.app.ui.scan

/**
 * What a front-of-pack match may do. Their own label, recognised by its front, is
 * asked about outright (right 9 times of 9 in the owner's log). A guess from the
 * label pack is only ever a choice beside the barcode and the label: as a question
 * it was "almost always wrong" (owner, 2026-10-27), with 27 give-ups in two weeks.
 */
enum class FrontOffer {
    ASK,
    CHOICES;

    companion object {
        fun of(yours: Boolean, newKind: Boolean): FrontOffer = if (yours || newKind) ASK else CHOICES
    }
}
