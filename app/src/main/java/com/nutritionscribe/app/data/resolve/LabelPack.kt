package com.nutritionscribe.app.data.resolve

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.nutritionscribe.shared.resolve.LabelPackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The optional label pack: ~230,000 real package labels (tools/build_label_pack.py),
 * searched offline and used for barcode scans.
 *
 * About 70 MB on the phone, and the single biggest accuracy gain for packaged
 * food. A test build bundles it in the APK ([installBundled]); otherwise it
 * installs from a file, or from [DOWNLOAD_URL] once one exists. Either way it is validated before it replaces anything, so a bad
 * download can never take away a pack that worked.
 */
class LabelPack(private val context: Context) {

    sealed class Status {
        data object NotInstalled : Status()
        data class Working(val what: String, val bytes: Long = 0) : Status()
        /** [hasMicros]: a format 4 pack, which also carries the label micronutrients. */
        data class Installed(val products: Int, val since: String, val bytes: Long, val hasMicros: Boolean = false) : Status()
        data class Failed(val message: String) : Status()
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val _status = MutableStateFlow<Status>(Status.NotInstalled)
    val status: StateFlow<Status> = _status

    /** Called after a pack installs: a newer format can fill in foods already logged. */
    @Volatile var onInstalled: (() -> Unit)? = null

    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var source: LabelPackSource? = null

    init {
        _status.value = describe(file) ?: Status.NotInstalled
    }

    val isInstalled: Boolean get() = file.exists() && _status.value is Status.Installed

    /** The search, or null when no pack is installed. */
    fun source(): LabelPackSource? {
        source?.let { return it }
        if (!file.exists()) return null
        return synchronized(this) {
            source ?: runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
            }.getOrNull()?.let { d ->
                val micros = (_status.value as? Status.Installed)?.hasMicros ?: false
                LabelPackSource(AndroidSql(d), hasMicros = micros).also { s -> source = s }
            }
        }
    }

    @Volatile private var front: com.nutritionscribe.shared.resolve.FrontMatcher? = null

    /** What a product is from the front of its box, or null with no pack. */
    fun frontMatcher(): com.nutritionscribe.shared.resolve.FrontMatcher? {
        front?.let { return it }
        val s = source() ?: return null
        val d = db ?: return null
        return com.nutritionscribe.shared.resolve.FrontMatcher(AndroidSql(d), s).also { front = it }
    }

    suspend fun installFrom(uri: Uri): Status = withContext(Dispatchers.IO) {
        _status.value = Status.Working("Copying")
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext fail("Couldn't open that file.")
        install(input)
    }

    suspend fun download(url: String = DOWNLOAD_URL ?: ""): Status = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext fail("No download address yet.")
        _status.value = Status.Working("Downloading")
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }
        } catch (e: Exception) {
            return@withContext fail("Couldn't reach the download.")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext fail("Download failed (${conn.responseCode}).")
            install(conn.inputStream)
        } catch (e: Exception) {
            fail("Download stopped: ${e.message ?: "no connection"}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Installs the pack shipped inside the APK, when there is one and this phone
     * has none or a smaller one. Someone handed the app then has real labels from
     * the first launch without being told to find a file; a newer pack they
     * installed themselves is left alone.
     */
    suspend fun installBundled(): Status? = withContext(Dispatchers.IO) {
        val id = runCatching { context.assets.open(BUNDLED_ID).bufferedReader().use { it.readText().trim() } }.getOrNull()
            ?: return@withContext null
        val products = id.split('|').getOrNull(2)?.toIntOrNull() ?: return@withContext null
        val have = describe(file)
        if (have != null && have.products >= products) return@withContext null
        _status.value = Status.Working("Installing")
        // The build unpacks a .gz asset and stores it compressed inside the APK, so
        // it arrives as labels.db; the gzipped name is kept in case a build doesn't.
        val input = BUNDLED_FILES.firstNotNullOfOrNull { name -> runCatching { context.assets.open(name) }.getOrNull() }
            ?: return@withContext fail("The bundled label pack is missing.")
        install(input, notify = false)
    }

    fun remove() {
        synchronized(this) {
            db?.close()
            db = null
            source = null
            front = null
            file.delete()
        }
        _status.value = Status.NotInstalled
    }

    /** [notify] false when the caller runs the backfill itself, so two passes can't race. */
    private fun install(raw: InputStream, notify: Boolean = true): Status {
        val tmp = File(context.filesDir, "$FILE_NAME.part")
        try {
            // Accept the pack gzipped (the 12 MB download) or as-is.
            val buffered = BufferedInputStream(raw)
            buffered.mark(2)
            val magic = ByteArray(2).also { buffered.read(it) }
            buffered.reset()
            val stream = if (magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) GZIPInputStream(buffered) else buffered
            var copied = 0L
            stream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied % (4 shl 20) < n) _status.value = Status.Working("Installing", copied)
                    }
                }
            }
            val described = describe(tmp) ?: run {
                tmp.delete()
                return fail("That isn't a Nutrition Scribe label pack.")
            }
            synchronized(this) {
                db?.close()
                db = null
                source = null
                front = null
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
            _status.value = described.copy(bytes = file.length())
            if (notify) onInstalled?.invoke()
            return _status.value
        } catch (e: Exception) {
            tmp.delete()
            return fail("Install failed: ${e.message ?: "unknown error"}")
        }
    }

    /** Reads a pack's own description, or null when it is not a pack this app understands. */
    private fun describe(f: File): Status.Installed? {
        if (!f.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val meta = d.rawQuery("SELECT key, value FROM meta", null).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                }
                val format = meta["format"]
                if (format !in SUPPORTED_FORMATS) return@use null
                val products = d.rawQuery("SELECT COUNT(*) FROM product", null).use { c -> c.moveToFirst(); c.getInt(0) }
                Status.Installed(products, meta["since"].orEmpty(), f.length(), hasMicros = format == "4")
            }
        }.getOrNull()
    }

    private fun fail(message: String): Status {
        val s = Status.Failed(message)
        // A failed install leaves a working pack exactly as it was.
        _status.value = describe(file) ?: s
        return s
    }

    companion object {
        const val FILE_NAME = "labels.db"
        /** In the APK's assets when a pack is bundled; see app/build.gradle.kts. */
        private val BUNDLED_FILES = listOf("labels.db", "labels.db.gz")
        /** "format|since|products" of the bundled pack, so it can be compared without unpacking. */
        private const val BUNDLED_ID = "labels.id"
        /** 3: macros only. 4: plus calcium, iron, potassium, vitamin D, cholesterol (tools/label_pack_micros.py). */
        val SUPPORTED_FORMATS = setOf("3", "4")

        /**
         * Where the app downloads the pack from. Null until there is a public place
         * to host it (the plan is a GitHub release); until then, install from a file.
         */
        val DOWNLOAD_URL: String? = null
    }
}
