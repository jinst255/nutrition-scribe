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
 * The optional label pack: ~137,000 real package labels (tools/build_label_pack.py),
 * searched offline and used for barcode scans.
 *
 * Optional because it is ~40 MB on the phone; recommended because it is the
 * single biggest accuracy gain for packaged food. It installs from a file today
 * (sideloaded, or shared by whoever gave you the app) and from [DOWNLOAD_URL] once
 * one exists. Either way it is validated before it replaces anything, so a bad
 * download can never take away a pack that worked.
 */
class LabelPack(private val context: Context) {

    sealed class Status {
        data object NotInstalled : Status()
        data class Working(val what: String, val bytes: Long = 0) : Status()
        data class Installed(val products: Int, val since: String, val bytes: Long) : Status()
        data class Failed(val message: String) : Status()
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val _status = MutableStateFlow<Status>(Status.NotInstalled)
    val status: StateFlow<Status> = _status

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
            }.getOrNull()?.let { LabelPackSource(AndroidSql(it)).also { s -> source = s } }
        }
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

    fun remove() {
        synchronized(this) {
            db?.close()
            db = null
            source = null
            file.delete()
        }
        _status.value = Status.NotInstalled
    }

    private fun install(raw: InputStream): Status {
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
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
            _status.value = described.copy(bytes = file.length())
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
                if (meta["format"] != SUPPORTED_FORMAT) return@use null
                val products = d.rawQuery("SELECT COUNT(*) FROM product", null).use { c -> c.moveToFirst(); c.getInt(0) }
                Status.Installed(products, meta["since"].orEmpty(), f.length())
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
        const val SUPPORTED_FORMAT = "3"

        /**
         * Where the app downloads the pack from. Null until there is a public place
         * to host it (the plan is a GitHub release); until then, install from a file.
         */
        val DOWNLOAD_URL: String? = null
    }
}
