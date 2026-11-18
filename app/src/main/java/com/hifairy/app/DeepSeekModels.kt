package com.hifairy.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DeepSeekModel(
    val id: String,
    val name: String,
    val effortLevels: List<String>,
    val defaultEffort: String?
)

object DeepSeekModels {
    fun fetch(apiKey: String): List<DeepSeekModel> {
        val connection = (URL("https://api.deepseek.com/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("获取模型列表失败（HTTP $code）")
            val root = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val data = root.getJSONArray("data")
            return buildList {
                for (index in 0 until data.length()) {
                    val item = data.getJSONObject(index)
                    val id = item.getString("id")
                    val effort = item.optJSONObject("effort")
                    val supported = effort?.optJSONArray("supported_levels")
                    val levels = if (supported == null) emptyList() else (0 until supported.length()).map { supported.getString(it) }
                    add(DeepSeekModel(id, item.optString("name").ifBlank { id }, levels, effort?.optString("default_level")?.takeIf { it.isNotBlank() }))
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
