package com.nutritionscribe.app.ui.exportimport

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupAgeTest {
    private val day = 86_400_000L

    /** Review S2: no last-backup date and no reminder. A month old, it asks for a new one. */
    @Test
    fun `the backup line says when, and nudges after a month`() {
        assertEquals("Never backed up. Your log is only on this phone.", BackupAge.line(null, 100 * day))
        assertEquals("Last backup today.", BackupAge.line(100 * day, 100 * day + 3_600_000))
        assertEquals("Last backup 29 days ago.", BackupAge.line(71 * day, 100 * day))
        assertEquals("Last backup 30 days ago. Time for a new one.", BackupAge.line(70 * day, 100 * day))
    }
}
