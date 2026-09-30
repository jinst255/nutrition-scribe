package com.nutritionscribe.app.ui

import com.nutritionscribe.shared.model.Entry

/**
 * Holds the single entry currently on the Review screen. A full Entry doesn't fit
 * cleanly into a Compose Navigation string argument, and this app only ever has one
 * entry in flight through Add Entry -> Processing -> Review at a time.
 */
object PendingEntryHolder {
    var entry: Entry? = null
}
