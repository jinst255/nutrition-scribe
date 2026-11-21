package com.nutritionscribe.app.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.nutritionscribe.app.ui.debuglog.DebugEvents
import com.nutritionscribe.shared.update.AppUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the phone app from its GitHub releases (owner, 11-20), so a new version no
 * longer needs a cable. Looks by itself when the app is opened, at most every 12 hours
 * ([AppUpdates.dueToCheck]); Home then shows a card, and Settings › Updates looks now.
 *
 * Safety, in order: the APK comes only from this repo's release downloads over https
 * ([AppUpdates.parse]); its size and GitHub's SHA-256 must match; it must be this app
 * ([PackageManager.getPackageArchiveInfo]), a higher versionCode, and signed with the same
 * certificate as the copy running now. Android then checks the signature again itself.
 * Nothing is installed without a tap on Update.
 *
 * Android shows its own "Update this app?" box the first time (a copy put on by cable has
 * no installer of record); after one update through here it can update without asking
 * ([PackageInstaller.SessionParams.setRequireUserAction], Android 12+).
 */
class AppUpdater(private val context: Context, private val events: DebugEvents) {

    sealed class Status {
        data object Idle : Status()
        data object Checking : Status()
        data class UpToDate(val checkedAt: Long) : Status()
        data class Available(val release: AppUpdates.Release) : Status()
        data class Downloading(val release: AppUpdates.Release, val bytes: Long) : Status()
        /** Android needs "Install unknown apps" turned on for this app once. */
        data class NeedsPermission(val release: AppUpdates.Release) : Status()
        data class Installing(val release: AppUpdates.Release) : Status()
        data class Failed(val message: String, val release: AppUpdates.Release?) : Status()
    }

    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val dir = File(context.cacheDir, "updates")

    val currentVersion: String = runCatching { info(context.packageName)?.versionName }.getOrNull().orEmpty()
    private val currentCode: Long = runCatching { info(context.packageName)?.let(::codeOf) }.getOrNull() ?: 0L
    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _status = MutableStateFlow(initial())
    val status: StateFlow<Status> = _status

    /** Android's "Update this app?" box, waiting for the screen to show it. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val auto: StateFlow<Boolean> = _auto

    private val _skipped = MutableStateFlow(prefs.getString(KEY_SKIPPED, null))
    /** The version "Later" was tapped on: Home stays quiet about it until a newer one. */
    val skipped: StateFlow<String?> = _skipped

    val lastCheckAt: Long? get() = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        _auto.value = on
        events.event("update_auto", "on" to on)
    }

    fun later(release: AppUpdates.Release) {
        prefs.edit().putString(KEY_SKIPPED, release.version).apply()
        _skipped.value = release.version
        events.event("update_later", "version" to release.version)
    }

    /** On opening the app: look if it's time, and leave debug builds alone (they can't take a release). */
    fun checkIfDue() {
        if (!_auto.value || debuggable) return
        if (!AppUpdates.dueToCheck(lastCheckAt, System.currentTimeMillis())) return
        check(byHand = false)
    }

    fun check(byHand: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (byHand) _status.value = Status.Checking
            val now = System.currentTimeMillis()
            try {
                val text = get(AppUpdates.RELEASES_API, maxBytes = 2_000_000, accept = "application/vnd.github+json") {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val releases = AppUpdates.parse(text)
                val newest = AppUpdates.newest(releases, currentVersion)
                prefs.edit().putLong(KEY_LAST, now).apply()
                saveFound(newest)
                _status.value = newest?.let { Status.Available(it) } ?: Status.UpToDate(now)
                events.event("update_check", "by_hand" to byHand, "found" to (newest?.version ?: "none"), "releases" to releases.size)
            } catch (e: Exception) {
                events.event("update_check_failed", "by_hand" to byHand, "why" to (e.message ?: e.javaClass.simpleName))
                // A quiet look that fails changes nothing on screen; a tap says what happened.
                if (byHand) _status.value = Status.Failed("Couldn't reach GitHub. Check your connection and try again.", null)
                else if (_status.value is Status.Checking) _status.value = initial()
            }
        }
    }

    /** Update: download, check, then hand to Android's installer. */
    fun update(release: AppUpdates.Release) {
        if (job?.isActive == true) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _status.value = Status.NeedsPermission(release)
            events.event("update_needs_permission", "version" to release.version)
            return
        }
        job = scope.launch {
            try {
                events.event("update_download", "version" to release.version, "bytes" to release.apkBytes)
                val file = download(release)
                verify(file)
                _status.value = Status.Installing(release)
                install(file)
                events.event("update_install", "version" to release.version)
            } catch (e: Exception) {
                events.always("update_failed", "version" to release.version, "why" to (e.message ?: e.javaClass.simpleName))
                _status.value = Status.Failed(e.message ?: "The update didn't download. Try again.", release)
            }
        }
    }

    /** Back from Android's settings: carry on if "Install unknown apps" is on now. */
    fun onResume() {
        val s = _status.value
        if (s is Status.NeedsPermission && Build.VERSION.SDK_INT >= 26 && context.packageManager.canRequestPackageInstalls()) update(s.release)
    }

    /** Android's "Install unknown apps" page for this app. */
    fun permissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun confirmShown() { _confirm.value = null }

    // ---------------------------------------------------------------- install result

    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        val release = (_status.value as? Status.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> _confirm.value = confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Usually never seen: replacing itself ends this process first.
            PackageInstaller.STATUS_SUCCESS -> _status.value = Status.UpToDate(System.currentTimeMillis())
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                events.event("update_cancelled")
                _status.value = release?.let { Status.Available(it) } ?: initial()
            }
            else -> {
                events.always("update_install_failed", "status" to status, "why" to message.orEmpty())
                _status.value = Status.Failed(installProblem(status), release)
            }
        }
    }

    private fun installProblem(status: Int) = when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space on this phone for the update."
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android wouldn't put it over this copy. Install it from the GitHub page instead."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "This phone blocks installs from apps."
        else -> "Android couldn't install the update. Try again."
    }

    // ---------------------------------------------------------------- steps

    private fun download(release: AppUpdates.Release): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // only ever one, and never one already installed
        val part = File(dir, "update.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        _status.value = Status.Downloading(release, 0)
        get(release.apkUrl, maxBytes = release.apkBytes) { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                var shown = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > release.apkBytes) throw IOException("The download was bigger than GitHub said. Try again.")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    if (total - shown > 256_000) { shown = total; _status.value = Status.Downloading(release, total) }
                }
                if (total != release.apkBytes) throw IOException("The download stopped part way. Try again.")
            }
        }
        release.sha256?.let { want ->
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != want) { part.delete(); throw IOException("The download was damaged. Try again.") }
        }
        val apk = File(dir, "update.apk")
        if (!part.renameTo(apk)) throw IOException("Couldn't save the update.")
        return apk
    }

    /** This app, newer, and signed by the same key as the copy that's running. */
    private fun verify(apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: fail(apk, "That file isn't an app.")
        if (theirs.packageName != context.packageName) fail(apk, "That file is a different app.")
        if (codeOf(theirs) <= currentCode) fail(apk, "That version isn't newer than this one.")
        val mine = info(context.packageName, flags) ?: fail(apk, "Couldn't read this app's own signature.")
        val a = certs(theirs)
        val b = certs(mine)
        if (a.isEmpty() || a != b) fail(apk, "That file isn't signed by Nutrition Scribe's key, so it wasn't installed.")
    }

    private fun fail(apk: File, why: String): Nothing { apk.delete(); throw IOException(why) }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateResultReceiver::class.java).setAction(ACTION_RESULT).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        apk.delete() // the installer has its own copy now
    }

    // ---------------------------------------------------------------- bits

    private fun initial(): Status = readFound()?.let { r ->
        if ((AppUpdates.compare(r.version, currentVersion) ?: 0) > 0) Status.Available(r) else null
    } ?: Status.Idle

    private fun saveFound(r: AppUpdates.Release?) {
        val e = prefs.edit()
        if (r == null) e.remove(KEY_FOUND_VERSION) else e.putString(KEY_FOUND_VERSION, r.version).putString(KEY_FOUND_TAG, r.tag)
            .putString(KEY_FOUND_NOTES, r.notes.take(4000)).putString(KEY_FOUND_URL, r.apkUrl).putLong(KEY_FOUND_BYTES, r.apkBytes)
            .putString(KEY_FOUND_SHA, r.sha256)
        e.apply()
    }

    private fun readFound(): AppUpdates.Release? {
        val v = prefs.getString(KEY_FOUND_VERSION, null) ?: return null
        val url = prefs.getString(KEY_FOUND_URL, null)?.takeIf { it.startsWith(AppUpdates.DOWNLOAD_PREFIX) } ?: return null
        return AppUpdates.Release(v, prefs.getString(KEY_FOUND_TAG, "").orEmpty(), prefs.getString(KEY_FOUND_NOTES, "").orEmpty(),
            url, prefs.getLong(KEY_FOUND_BYTES, 0L), prefs.getString(KEY_FOUND_SHA, null))
    }

    @Suppress("DEPRECATION")
    private fun info(pkg: String, flags: Int = 0): PackageInfo? = runCatching { context.packageManager.getPackageInfo(pkg, flags) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun codeOf(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = p.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else p.signatures
        val sha = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { sig -> sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun <T> get(url: String, maxBytes: Long, accept: String? = null, read: (java.io.InputStream) -> T): T {
        require(url.startsWith("https://")) { "only https" }
        var target = URL(url)
        // GitHub sends release downloads on to its file host: follow a few https redirects.
        repeat(6) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "NutritionScribe")
                accept?.let { setRequestProperty("Accept", it) }
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val next = conn.getHeaderField("Location") ?: throw IOException("redirect without a place")
                        target = URL(target, next)
                        if (target.protocol != "https") throw IOException("refused a non-https redirect")
                    }
                    in 200..299 -> {
                        if (conn.contentLengthLong > maxBytes) throw IOException("The file is bigger than expected.")
                        return conn.inputStream.use(read)
                    }
                    else -> throw IOException("GitHub said $code. Try again later.")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    companion object {
        const val ACTION_RESULT = "com.nutritionscribe.app.UPDATE_RESULT"
        private const val KEY_AUTO = "auto"
        private const val KEY_LAST = "last_check_at"
        private const val KEY_SKIPPED = "skipped_version"
        private const val KEY_FOUND_VERSION = "found_version"
        private const val KEY_FOUND_TAG = "found_tag"
        private const val KEY_FOUND_NOTES = "found_notes"
        private const val KEY_FOUND_URL = "found_url"
        private const val KEY_FOUND_BYTES = "found_bytes"
        private const val KEY_FOUND_SHA = "found_sha"
    }
}

/** Android's answer to an install: needs a tap, done, cancelled or failed. Not exported. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        runCatching { com.nutritionscribe.app.di.ServiceLocator.appUpdater }.getOrNull()?.onInstallResult(status, message, confirm)
    }
}
