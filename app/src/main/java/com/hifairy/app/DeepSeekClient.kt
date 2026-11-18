package com.hifairy.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DeepSeekClient {
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { connection?.disconnect() }

    fun stream(apiKey: String, model: String, effort: String, history: List<Pair<String, String>>, onText: (String) -> Unit, onReasoning: (String) -> Unit) {
        val messages = JSONArray()
        history.forEach { (role, content) -> messages.put(JSONObject().put("role", role).put("content", content)) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("reasoning_effort", effort)
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
                    (delta.opt("content") as? String)?.takeIf { it.isNotEmpty() }?.let(onText)
                    (delta.opt("reasoning_content") as? String)?.takeIf { it.isNotEmpty() }?.let(onReasoning)
                }
            }
        } finally { connection.disconnect(); this.connection = null }
    }
}
