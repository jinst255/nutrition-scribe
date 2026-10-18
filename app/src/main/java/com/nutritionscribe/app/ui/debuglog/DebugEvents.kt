package com.nutritionscribe.app.ui.debuglog

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug mode: a detailed, machine-readable record of what the person did and what
 * the app did back, so a problem can be found from the file alone instead of from
 * someone's memory of it.
 *
 * One JSON object per line (JSON Lines): easy to append, easy to read partway if
 * the app dies mid-write, and easy to filter. Every line has `ts` (epoch ms), `t`
 * (local time, for reading), `seq` and `type`; the rest depends on the type.
 *
 * It records what was said and typed, which is personal, so it is OFF unless the
 * person turns it on, stays on this phone, and leaves only when they save or share
 * it themselves. It never records an API key: request headers are not logged, and
 * [redact] strips anything that looks like one from what is.
 *
 * Writes happen on one background thread, in order, so logging never slows the UI.
 *
 * Every line also carries the screen it happened on and a session id (one per
 * process start), so a line can be read in context without replaying the file.
 * The export is a zip: the events, plus what the events alone cannot show — the
 * log's recent entries as stored, the photos label scans were read from, why the
 * app last closed, and the app's own system log.
 */
class DebugEvents(
    private val context: Context?,
    dir: File = context!!.filesDir,
    private val cacheDir: File = context!!.cacheDir,
) {

    @Volatile var enabled: Boolean = false

    /** The route on screen now; stamped on every line. */
    @Volatile var screen: String? = null

    /** One per process start, so two runs in one file can be told apart. */
    val session: String = java.util.UUID.randomUUID().toString().take(8)

    private val file = File(dir, "debug_events.jsonl")
    private val previous = File(dir, "debug_events.1.jsonl")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "debug-events").apply { isDaemon = true } }
    private val seq = AtomicLong(0)
    private val clock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    // ---- Time spent -------------------------------------------------------
    //
    // Where the minutes go. Every "screen" line says how long the screen before
    // it was open, and each attempt at logging food is a flow: from the button
    // that began it (say, scan, type) to "logged", or to Home without logging.
    // Its "flow_end" line has the total, when each step first happened, and how
    // many of each there were — so a scan that took two minutes shows whether
    // that was the camera, the reading, or fixing the numbers afterwards.

    private class Flow(val method: String, val startedAt: Long) {
        val firstAt = LinkedHashMap<String, Long>()
        val counts = LinkedHashMap<String, Int>()
    }

    @Volatile private var flow: Flow? = null
    @Volatile private var screenSince: Long = System.currentTimeMillis()
    private var lastRoute: String? = null

    /** A new attempt at logging something. With [onlyIfIdle], one already running carries on. */
    fun startFlow(method: String, onlyIfIdle: Boolean = false) {
        synchronized(this) {
            val running = flow
            if (running != null && onlyIfIdle) return
            if (running != null) endFlowLocked("replaced")
            flow = Flow(method, System.currentTimeMillis())
        }
        event("flow_start", "method" to method)
    }

    fun endFlow(outcome: String, vararg fields: Pair<String, Any?>) = synchronized(this) { endFlowLocked(outcome, *fields) }

    private fun endFlowLocked(outcome: String, vararg fields: Pair<String, Any?>) {
        val f = flow ?: return
        flow = null
        val ms = System.currentTimeMillis() - f.startedAt
        // A mic press too short to record, then another: not worth a line.
        if (outcome == "replaced" && f.counts.isEmpty()) return
        event("flow_end", "method" to f.method, "outcome" to outcome, "ms" to ms,
            "at_ms" to LinkedHashMap(f.firstAt), "steps" to LinkedHashMap(f.counts), *fields)
    }

    /** Call when the screen changes; returns how long the last one was open. */
    fun screenChanged(route: String?): Pair<String?, Long> {
        val now = System.currentTimeMillis()
        val prev = lastRoute to (now - screenSince)
        screenSince = now
        lastRoute = route
        screen = route
        // Back on Home with a flow still open and nothing logged: given up on.
        if (route == "home" && prev.first != null && prev.first != "home") {
            synchronized(this) { if (flow != null) endFlowLocked("abandoned", "left" to prev.first) }
        }
        return prev
    }

    private fun track(type: String, now: Long) {
        if (type == "flow_start" || type == "flow_end" || type == "screen") return
        synchronized(this) {
            val f = flow ?: return
            f.firstAt.putIfAbsent(type, now - f.startedAt)
            f.counts[type] = (f.counts[type] ?: 0) + 1
        }
    }

    /** Recorded only while debug mode is on. */
    fun event(type: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        write(type, fields.toMap())
    }

    /** Recorded even with debug mode off: something the person chose to write down. */
    fun always(type: String, vararg fields: Pair<String, Any?>) = write(type, fields.toMap())

    fun event(type: String, fields: Map<String, Any?>) {
        if (!enabled) return
        write(type, fields)
    }

    private fun write(type: String, fields: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        track(type, now)
        val n = seq.incrementAndGet()
        val thread = Thread.currentThread().name
        val screenNow = screen
        writer.execute {
            val obj = LinkedHashMap<String, JsonElement>()
            obj["ts"] = JsonPrimitive(now)
            obj["t"] = JsonPrimitive(clock.format(Date(now)))
            obj["seq"] = JsonPrimitive(n)
            obj["type"] = JsonPrimitive(type)
            obj["thread"] = JsonPrimitive(thread)
            obj["session"] = JsonPrimitive(session)
            screenNow?.let { obj["screen"] = JsonPrimitive(it) }
            for ((k, v) in fields) obj[k] = toJson(v)
            runCatching {
                if (file.length() > MAX_BYTES) {
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(redact(JsonObject(obj).toString()) + "\n")
            }
        }
    }

    /** Blocks until everything queued is on disk — before an export, or a crash. */
    fun flush() {
        runCatching { writer.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS) }
    }

    fun sizeBytes(): Long = file.length() + previous.length()

    fun lineCount(): Int = listOf(previous, file).filter { it.exists() }.sumOf { f -> f.useLines { it.count() } }

    fun clear() {
        flush()
        file.delete(); previous.delete()
        scanDir.listFiles()?.forEach { it.delete() }
    }

    private val scanDir = File(dir, "debug_scans")

    /**
     * Keeps the photo a label was read from, so a misread can be checked against
     * what the camera actually saw. Only in debug mode; only the newest
     * [MAX_SCANS]. Returns the file name to put in the event.
     */
    fun saveScan(bitmap: android.graphics.Bitmap, prefix: String = "scan"): String? {
        if (!enabled) return null
        return runCatching {
            scanDir.mkdirs()
            val long = maxOf(bitmap.width, bitmap.height)
            val scale = if (long > 1600) 1600f / long else 1f
            val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val name = "$prefix-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".jpg"
            File(scanDir, name).outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            scanDir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_SCANS)?.forEach { it.delete() }
            name
        }.getOrNull()
    }

    fun scanCount(): Int = scanDir.listFiles()?.size ?: 0

    /** The newest [n] lines, for the on-screen preview. */
    fun tail(n: Int): List<String> {
        flush()
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        return lines.takeLast(n)
    }

    /**
     * One zip with everything:
     * - events.jsonl: a header describing the phone and app, the older rotated
     *   half, the current half, and the plain error log at the end;
     * - each of [extras] as its own file (entries as stored, exit reasons, …);
     * - logcat.txt: this app's own system log, which holds what the app never
     *   thought to record (framework warnings, ML Kit, the reason a screen froze);
     * - scans/: the photos label scans were read from.
     */
    fun buildExport(header: Map<String, Any?>, errorLog: String, extras: Map<String, String> = emptyMap()): File {
        flush()
        val out = File(cacheDir, "debug-export/${exportName()}")
        out.parentFile?.mkdirs()
        out.parentFile?.listFiles()?.forEach { if (it != out) it.delete() }
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun entry(name: String, write: (java.io.Writer) -> Unit) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                val w = zip.writer()
                write(w)
                w.flush()
                zip.closeEntry()
            }
            entry("events.jsonl") { w ->
                val h = LinkedHashMap<String, JsonElement>()
                h["type"] = JsonPrimitive("export_header")
                h["ts"] = JsonPrimitive(System.currentTimeMillis())
                h["session"] = JsonPrimitive(session)
                for ((k, v) in header) h[k] = toJson(v)
                w.write(redact(JsonObject(h).toString())); w.write("\n")
                for (f in listOf(previous, file)) if (f.exists()) f.bufferedReader().use { r -> r.copyTo(w) }
                val e = mapOf("type" to JsonPrimitive("error_log"), "text" to JsonPrimitive(errorLog))
                w.write(redact(JsonObject(e).toString())); w.write("\n")
            }
            for ((name, text) in extras) entry(name) { it.write(redact(text)) }
            entry("logcat.txt") { it.write(redact(ownLogcat())) }
            scanDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry("scans/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    /** The newest lines of this process's own system log. An app may read its own. */
    private fun ownLogcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "4000", "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        text
    }.getOrElse { "logcat unavailable: ${it.message}" }

    /** Into the phone's Downloads folder, where it can be pulled over USB or opened from Files. */
    fun saveToDownloads(export: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context?.contentResolver ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, export.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os -> export.inputStream().use { it.copyTo(os) } }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Whose phone this is, when they gave a name at setup; it goes in the file name. */
    @Volatile var testerName: String? = null

    private fun exportName(): String {
        val who = testerName?.let(::redact)?.substringBefore("sk-")?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.take(20)?.takeIf { it.isNotEmpty() }
        return "nutrition-scribe-debug-" + (who?.let { "$it-" } ?: "") +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
    }

    companion object {
        private const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_SCANS = 20

        private val KEY_PATTERN = Regex("""(sk-[A-Za-z0-9_\-]{8,}|sk-ant-[A-Za-z0-9_\-]{8,})""")

        /** Belt and braces: nothing that looks like an API key survives into the file. */
        fun redact(s: String): String = KEY_PATTERN.replace(s, "sk-…redacted")

        fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Enum<*> -> JsonPrimitive(v.name)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
            is Iterable<*> -> JsonArray(v.map { toJson(it) })
            is Array<*> -> JsonArray(v.map { toJson(it) })
            is Throwable -> JsonObject(mapOf("class" to JsonPrimitive(v.javaClass.name), "message" to JsonPrimitive(v.message ?: ""),
                "stack" to JsonPrimitive(v.stackTraceToString().take(6000))))
            else -> JsonPrimitive(v.toString())
        }
    }
}
