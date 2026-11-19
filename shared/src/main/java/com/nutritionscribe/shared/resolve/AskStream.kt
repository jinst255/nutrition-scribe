package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ask's answer as it is written, and what it is doing meanwhile.
 *
 * Owner, 11-16: "it would be beneficial to be streaming tokens instead of just
 * delivering them one chunk at a time, and any useful information on the turns
 * that are actually taking place, whether it is looking through things in the
 * database or performing web searches or actually thinking". Before, Ask showed
 * one fixed "Thinking…" line until the whole answer came.
 *
 * OpenAI's /v1/responses sends server-sent events with "stream": true. The reply is
 * JSON (Ask's strict schema), so the text shown is the "reply" field read out of
 * the JSON written so far.
 */
object AskStream {

    sealed class Progress {
        /** What it's doing: "Looking through your log…". */
        data class Status(val text: String) : Progress()
        /** The answer so far. */
        data class Text(val text: String) : Progress()
    }

    /** POST with "stream": true; [onData] gets each event's JSON. Returns (status, the finished response's JSON or the error body). */
    fun interface StreamTransport {
        fun stream(url: String, headers: Map<String, String>, body: String, onData: (String) -> Unit): Pair<Int, String>
    }

    /** Over HttpURLConnection. The finished response comes from the "response.completed" event. */
    val HttpSse = StreamTransport { url, headers, body, onData ->
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val status = conn.responseCode
            if (status !in 200..299) return@StreamTransport status to (conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            var finished: String? = null
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    completed(data)?.let { finished = it }
                    runCatching { onData(data) }
                }
            }
            status to (finished ?: throw IOException("stream ended early"))
        } finally {
            conn.disconnect()
        }
    }

    /** The finished response's JSON, from a "response.completed" event. */
    fun completed(data: String): String? = runCatching {
        val o = Json.parseToJsonElement(data).jsonObject
        if ((o["type"] as? JsonPrimitive)?.content == "response.completed") o["response"]?.toString() else null
    }.getOrNull()

    /**
     * What one event means for the person watching. [answer] collects the answer's
     * JSON as it streams; the text shown is its "reply" so far.
     */
    fun progressOf(data: String, answer: StringBuilder): Progress? = runCatching {
        val o = Json.parseToJsonElement(data).jsonObject
        when ((o["type"] as? JsonPrimitive)?.content) {
            "response.output_text.delta" -> {
                answer.append((o["delta"] as? JsonPrimitive)?.content.orEmpty())
                partialString(answer.toString(), "reply")?.takeIf { it.isNotBlank() }?.let { Progress.Text(it) }
            }
            "response.web_search_call.in_progress", "response.web_search_call.searching" -> Progress.Status("Searching the web…")
            "response.output_item.added" -> {
                val item = o["item"] as? JsonObject
                when ((item?.get("type") as? JsonPrimitive)?.content) {
                    "function_call" -> when ((item["name"] as? JsonPrimitive)?.content) {
                        "query_log" -> Progress.Status("Looking through your log…")
                        "find_my_foods" -> Progress.Status("Looking at your saved foods…")
                        else -> null
                    }
                    "web_search_call" -> Progress.Status("Searching the web…")
                    "reasoning" -> Progress.Status("Thinking…")
                    "message" -> Progress.Status("Writing…")
                    else -> null
                }
            }
            else -> null
        }
    }.getOrNull()

    /**
     * The string value of [key] in JSON that may stop part-way: `{"reply":"Cottage ch`
     * gives "Cottage ch". Escapes are read; an escape cut in half is left off.
     */
    fun partialString(json: String, key: String): String? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"").find(json) ?: return null
        val out = StringBuilder()
        var i = m.range.last + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (i + 1 >= json.length) return out.toString()
                    when (val e = json[i + 1]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> {}
                        '"', '\\', '/' -> out.append(e)
                        'u' -> {
                            if (i + 5 >= json.length) return out.toString()
                            json.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 4
                        }
                        else -> out.append(e)
                    }
                    i += 2; continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
