package com.nutritionscribe.app.ui.debuglog

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the debug export and opens the share sheet, from wherever it's asked
 * for: the week-later reminder on Home, Settings, or the Debug log screen.
 */
object LogShare {

    suspend fun share(context: Context, where: String) {
        val events = ServiceLocator.debugEvents
        val f = withContext(Dispatchers.IO) {
            events.buildExport(ServiceLocator.appSnapshot(), ServiceLocator.debugLogger.readAll(), ServiceLocator.debugExtras())
        }
        events.always("log_shared", "where" to where, "bytes" to f.length(), "file" to f.name)
        ServiceLocator.settingsRepository.setLogSent(System.currentTimeMillis())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Nutrition Scribe debug log" + (events.testerName?.let { " from $it" } ?: ""))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Send the debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Due a week after install, or a week after the last one was sent, unless snoozed. */
    fun due(installedAt: Long?, sentAt: Long?, snoozeUntil: Long, now: Long): Boolean {
        val from = maxOf(installedAt ?: return false, sentAt ?: 0L)
        return now - from >= WEEK_MS && now >= snoozeUntil
    }

    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
}
