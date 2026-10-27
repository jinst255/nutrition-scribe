package com.nutritionscribe.app.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What's in your log: an unticked part keeps its events out of the export. */
class LogPartTest {
    private val profileLine = """{"ts":1,"t":"x","seq":3,"type":"profile_saved","age":29}"""
    private val plasmaLine = """{"ts":1,"t":"x","seq":4,"type":"plasma_donation","day":"2026-10-20"}"""
    private val entryLine = """{"ts":1,"t":"x","seq":5,"type":"entry_saved","name":"profile_saved salad"}"""

    @Test fun everythingGoesWhenNothingIsUnticked() {
        assertEquals(emptySet<String>(), LogPart.droppedEvents(emptySet()))
    }

    @Test fun untickingAboutYouDropsProfileEventsOnly() {
        val drop = LogPart.droppedEvents(setOf("about"))
        assertTrue(LogPart.isDropped(profileLine, drop))
        assertFalse(LogPart.isDropped(plasmaLine, drop))
        // A food named like an event type is still kept: only the type field counts.
        assertFalse(LogPart.isDropped(entryLine, drop))
    }

    @Test fun untickingPlasmaDropsDonationDays() {
        assertTrue(LogPart.isDropped(plasmaLine, LogPart.droppedEvents(setOf("plasma"))))
    }

    @Test fun photosLeaveNoEventsOut() {
        assertEquals(emptySet<String>(), LogPart.droppedEvents(setOf("photos")))
    }
}
