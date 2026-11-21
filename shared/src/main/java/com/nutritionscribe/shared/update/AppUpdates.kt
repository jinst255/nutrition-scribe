package com.nutritionscribe.shared.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Updates from GitHub (owner, 11-20: "so I don't need to plug my phone in every time").
 *
 * Each version is a GitHub release (release.yml) with the signed phone APK attached as
 * NutritionScribe-<version>.apk. The app reads the repo's release list, takes the newest
 * one above its own version, downloads that APK and hands it to Android's installer. Android
 * refuses any APK not signed with the same key, and the app checks the key itself first.
 *
 * Kept free of Android so the choices are tested on the JVM (AppUpdatesTest).
 */
object AppUpdates {
    const val REPO = "jinst255/nutrition-scribe"
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=15"
    /** Only files under this are downloaded, whatever the release list says. */
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    /** The release list's own limit; an APK past this is refused before it's downloaded. */
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    /** How often the app looks by itself, when it's opened. */
    const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

    private val PHONE_APK = Regex("""NutritionScribe-([0-9][0-9A-Za-z.\-]*)\.apk""")

    data class Release(
        val version: String,
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val apkBytes: Long,
        /** Lower-case hex SHA-256 of the APK when GitHub gives one (it does for uploads since 2025). */
        val sha256: String?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The releases in GitHub's list that carry a phone APK we'd accept: not drafts, the APK
     * under this repo's download path, a sane size. The watch APK (NutritionScribe-Watch-…)
     * never matches. Anything that doesn't parse gives an empty list, never a crash.
     */
    fun parse(text: String): List<Release> = runCatching {
        (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            if (r["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val tag = r["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val assets = runCatching { r["assets"]!!.jsonArray }.getOrNull() ?: return@mapNotNull null
            assets.firstNotNullOfOrNull { a -> asset(a as? JsonObject ?: return@firstNotNullOfOrNull null) }
                ?.let { (version, url, bytes, sha) ->
                    Release(version, tag, r["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), url, bytes, sha)
                }
        }
    }.getOrDefault(emptyList())

    private data class Asset(val version: String, val url: String, val bytes: Long, val sha: String?)

    private fun asset(a: JsonObject): Asset? {
        val name = a["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = PHONE_APK.matchEntire(name)?.groupValues?.get(1) ?: return null
        val url = a["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!url.startsWith(DOWNLOAD_PREFIX) || !url.endsWith("/$name") || ".." in url) return null
        val bytes = a["size"]?.jsonPrimitive?.longOrNull ?: return null
        if (bytes !in 1..MAX_APK_BYTES) return null
        val sha = a["digest"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("sha256:")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        return Asset(version, url, bytes, sha)
    }

    /**
     * Compares "1.10-test" with "1.9-test" by their numbers, so 1.10 is newer than 1.9 and
     * 1.3.1 newer than 1.3. The words after the numbers (-test, -beta) don't count: the
     * same numbers are the same version. Null when either has no leading number.
     */
    fun compare(a: String, b: String): Int? {
        val x = numbers(a) ?: return null
        val y = numbers(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun numbers(v: String): List<Long>? =
        Regex("""^v?(\d+(?:\.\d+)*)""").find(v.trim())?.groupValues?.get(1)?.split('.')?.map { it.toLongOrNull() ?: return null }

    /** The newest release above [current], or null when this phone is up to date. */
    fun newest(releases: List<Release>, current: String): Release? =
        releases.filter { (compare(it.version, current) ?: 0) > 0 }
            .maxWithOrNull { a, b -> compare(a.version, b.version) ?: 0 }

    /** Whether opening the app should look again: never looked, a clock that went backwards, or 12 h on. */
    fun dueToCheck(lastCheckAt: Long?, now: Long): Boolean =
        lastCheckAt == null || now < lastCheckAt || now - lastCheckAt >= CHECK_EVERY_MS

    /**
     * The release notes as plain lines for the What's new sheet. Only the "New in …" (or
     * "What's new") section when there is one, since the rest of a GitHub release page is how
     * to install it, which someone updating from inside the app has just done; otherwise
     * everything but an "Install" section. Headings and list marks dropped, **bold**
     * unwrapped, blank lines gone. Kept short.
     */
    fun noteLines(notes: String, max: Int = 14): List<String> {
        val lines = notes.lines()
        fun heading(l: String) = l.trimStart().startsWith("#")
        fun title(l: String) = l.trim().trimStart('#').trim().lowercase()
        val news = lines.indexOfFirst { heading(it) && (title(it).startsWith("new in") || title(it).startsWith("what's new") || title(it).startsWith("what\u2019s new")) }
        val picked = if (news >= 0) {
            lines.drop(news + 1).takeWhile { !heading(it) }
        } else {
            var skipping = false
            lines.filter { l ->
                if (heading(l)) skipping = title(l).startsWith("install")
                !skipping
            }
        }
        return picked
            .map { it.trim().removePrefix("#").trimStart('#', ' ').let { l -> Regex("""^[-*]\s+""").replace(l, "") }.replace("**", "").replace("`", "") }
            .filter { it.isNotBlank() }
            .take(max)
    }
}
