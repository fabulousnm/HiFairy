package com.hifairy.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DeepSeekClient {
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { connection?.disconnect() }

    fun stream(apiKey: String, model: String, effort: String, history: List<ChatMessage>, webSearch: Boolean, onText: (String) -> Unit, onReasoning: (String) -> Unit): Pair<List<ChatCitation>, String> {
        val messages = JSONArray()
        history.forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content).apply {
                if (message.role == "assistant" && message.serverToolCalls.isNotBlank()) put("server_tool_calls", JSONArray(message.serverToolCalls))
            })
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("reasoning_effort", effort)
        if (webSearch) body.put("tools", JSONArray().put(JSONObject().put("type", "deepseek_web_search"))
            .put(JSONObject().put("type", "deepseek_read_url")))
        var annotations = JSONArray()
        var serverToolCalls = JSONArray()
        val connection = (URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        this.connection = connection
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP ${connection.responseCode}: ${detail.take(180)}")
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data: ")) return@forEach
                    val data = line.removePrefix("data: ")
                    if (data == "[DONE]") return@forEach
                    val chunk = JSONObject(data)
                    if (chunk.has("error")) throw IllegalStateException(chunk.getJSONObject("error").optString("message", "流式请求失败"))
                    val choices = chunk.optJSONArray("choices") ?: return@forEach
                    if (choices.length() == 0) return@forEach
                    val delta = choices.getJSONObject(0).getJSONObject("delta")
                    delta.optJSONArray("annotations")?.let { annotations = it }
                    delta.optJSONArray("server_tool_calls")?.let { serverToolCalls = it }
                    (delta.opt("content") as? String)?.takeIf { it.isNotEmpty() }?.let(onText)
                    (delta.opt("reasoning_content") as? String)?.takeIf { it.isNotEmpty() }?.let(onReasoning)
                }
            }
        } finally { connection.disconnect(); this.connection = null }
        val citations = (0 until annotations.length()).mapNotNull { index ->
            val citation = annotations.optJSONObject(index)?.optJSONObject("url_citation") ?: return@mapNotNull null
            val url = citation.optString("url")
            if (!url.startsWith("https://")) return@mapNotNull null
            ChatCitation(citation.optString("title").ifBlank { url }, url)
        }.distinctBy { it.url }
        return citations to serverToolCalls.toString()
    }
}
