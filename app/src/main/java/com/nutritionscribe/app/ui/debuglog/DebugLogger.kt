package com.nutritionscribe.app.ui.debuglog

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only plaintext log for failures (Section 7). Never writes the API key or
 * request bodies (Section 3) — only status codes, short human-readable messages, and
 * truncated provider error bodies for non-auth API failures.
 */
class DebugLogger(context: Context) {
    private val logFile = File(context.filesDir, "debug_log.txt")
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${formatter.format(Date())} [$tag] $message\n"
        logFile.appendText(line)
    }

    fun readAll(): String = if (logFile.exists()) logFile.readText() else ""

    fun clear() {
        if (logFile.exists()) logFile.delete()
    }

    fun file(): File = logFile
}
