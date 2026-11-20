package com.nutritionscribe.app.data.resolve

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.nutritionscribe.shared.stores.Store
import com.nutritionscribe.shared.stores.StoreCatalog
import com.nutritionscribe.shared.stores.StorePackViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Store packs: extra package labels for one store each, from Open Food Facts
 * (tools/build_store_pack.py), downloaded from the GitHub release "store-packs".
 *
 * Owner, 11-19: get Costco/Kirkland and Great Value items "on board" without
 * crossing legal lines, let people choose what to download or abstract it for
 * them. People pick a store, never a database; the data's source and licence
 * (ODbL) are credited on the Store foods screen.
 *
 * Safety: a pack is only ever attached after its size and sha256 match the
 * catalogue and its own meta says it is that store in that slot
 * ([StorePackViews.metaProblem]). Ids and file names come from the app's own
 * [StoreCatalog], never from the downloaded catalogue, so a bad catalogue can't
 * write outside the packs folder. A failed download leaves a working pack as it was.
 */
class StorePacks(private val context: Context) {

    @Serializable
    data class Remote(
        val id: String, val title: String, val shop: String = "", val slot: Int,
        val products: Int, val bytes: Long, val sha256: String, val file: String,
    )

    @Serializable
    private data class Catalog(val format: Int = 1, val built: String = "", val stores: List<Remote> = emptyList())

    data class Installed(val store: Store, val products: Int, val bytes: Long, val built: String, val file: File)

    sealed class Job {
        data class Downloading(val bytes: Long, val total: Long) : Job()
        data class Failed(val message: String) : Job()
    }

    private val dir = File(context.filesDir, "stores").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<List<Installed>> = _installed

    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs

    private val _remote = MutableStateFlow(readCachedCatalog())
    /** The last catalogue fetched (kept on disk), or empty before the first fetch. */
    val remote: StateFlow<List<Remote>> = _remote

    /** Called after a pack is added or removed, so the label pack re-attaches. */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Slot and file of each installed pack, for [LabelPack] to attach. */
    fun attachments(): List<Pair<Int, File>> = _installed.value.map { it.store.slot to it.file }

    suspend fun refreshCatalog(): List<Remote> = withContext(Dispatchers.IO) {
        runCatching {
            val text = get(StoreCatalog.RELEASE_BASE + StoreCatalog.CATALOG_FILE, maxBytes = 256_000) { it.readBytes() }
                .toString(Charsets.UTF_8)
            val stores = parseCatalog(text)
            File(dir, StoreCatalog.CATALOG_FILE).writeText(text)
            _remote.value = stores
            stores
        }.getOrElse { _remote.value }
    }

    suspend fun download(id: String): Boolean = withContext(Dispatchers.IO) {
        val store = StoreCatalog.byId(id) ?: return@withContext fail(id, "Unknown store.")
        val remote = (_remote.value.takeIf { r -> r.any { it.id == id } } ?: refreshCatalog()).firstOrNull { it.id == id }
            ?: return@withContext fail(id, "That pack isn't available yet.")
        if (remote.slot != store.slot) return@withContext fail(id, "The pack list looks wrong; try again later.")
        if (remote.bytes <= 0 || remote.bytes > MAX_PACK_BYTES) return@withContext fail(id, "That pack is too big.")
        val gz = File(dir, "$id.db.gz.part")
        val db = File(dir, "$id.db.part")
        try {
            setJob(id, Job.Downloading(0, remote.bytes))
            val digest = MessageDigest.getInstance("SHA-256")
            get(StoreCatalog.RELEASE_BASE + "$id.db.gz", maxBytes = remote.bytes) { input ->
                gz.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > remote.bytes) throw IOException("bigger than the list says")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        setJob(id, Job.Downloading(total, remote.bytes))
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(remote.sha256, ignoreCase = true)) throw IOException("the download was damaged")
            GZIPInputStream(gz.inputStream().buffered()).use { input ->
                db.outputStream().use { out -> input.copyTo(out) }
            }
            val meta = readMeta(db) ?: throw IOException("that isn't a store pack")
            StorePackViews.metaProblem(meta, id, store.slot)?.let { throw IOException(it) }
            val target = File(dir, "$id.db")
            synchronized(this@StorePacks) {
                if (!db.renameTo(target)) {
                    target.delete()
                    if (!db.renameTo(target)) throw IOException("couldn't save it")
                }
            }
            _installed.value = scan()
            clearJob(id)
            onChanged?.invoke()
            true
        } catch (e: Exception) {
            fail(id, "Download failed: ${e.message ?: "no connection"}")
        } finally {
            gz.delete()
            db.delete()
        }
    }

    fun remove(id: String) {
        val store = StoreCatalog.byId(id) ?: return
        synchronized(this) { File(dir, "${store.id}.db").delete() }
        _installed.value = scan()
        onChanged?.invoke()
    }

    /** Called on a reset: every pack goes. */
    fun removeAll() {
        synchronized(this) { dir.listFiles()?.forEach { it.delete() } }
        _installed.value = emptyList()
        _remote.value = emptyList()
        onChanged?.invoke()
    }

    private fun scan(): List<Installed> = StoreCatalog.BUILT_IN.mapNotNull { store ->
        val f = File(dir, "${store.id}.db")
        if (!f.exists()) return@mapNotNull null
        val meta = readMeta(f) ?: return@mapNotNull null
        if (StorePackViews.metaProblem(meta, store.id, store.slot) != null) return@mapNotNull null
        Installed(store, meta["products"]?.toIntOrNull() ?: 0, f.length(), meta["built"].orEmpty(), f)
    }

    private fun readMeta(f: File): Map<String, String>? = runCatching {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT key, value FROM meta", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
        }
    }.getOrNull()

    private fun readCachedCatalog(): List<Remote> =
        runCatching { parseCatalog(File(dir, StoreCatalog.CATALOG_FILE).readText()) }.getOrDefault(emptyList())

    /** Only stores the app knows, in their own slots, with a sane size and checksum. */
    private fun parseCatalog(text: String): List<Remote> =
        json.decodeFromString(Catalog.serializer(), text).stores.filter { r ->
            val known = StoreCatalog.byId(r.id)
            known != null && known.slot == r.slot && r.sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
                r.bytes in 1..MAX_PACK_BYTES && r.products > 0
        }

    private fun <T> get(url: String, maxBytes: Long, read: (java.io.InputStream) -> T): T {
        require(url.startsWith("https://")) { "only https" }
        var target = URL(url)
        // GitHub sends release downloads on to its file host: follow a few https redirects.
        repeat(5) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "NutritionScribe")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val next = conn.getHeaderField("Location") ?: throw IOException("redirect without a place")
                        target = URL(target, next)
                        if (target.protocol != "https") throw IOException("refused a non-https redirect")
                    }
                    in 200..299 -> {
                        if (conn.contentLengthLong > maxBytes) throw IOException("too big")
                        return conn.inputStream.use(read)
                    }
                    else -> throw IOException("the server said $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun setJob(id: String, job: Job) { _jobs.value = _jobs.value + (id to job) }
    private fun clearJob(id: String) { _jobs.value = _jobs.value - id }
    private fun fail(id: String, message: String): Boolean { setJob(id, Job.Failed(message)); return false }

    companion object {
        /** No store's pack comes near this; anything bigger is refused unread. */
        const val MAX_PACK_BYTES = 40L * 1024 * 1024
    }
}
