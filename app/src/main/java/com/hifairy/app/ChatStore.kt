package com.hifairy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatCitation(val title: String, val url: String)
data class ChatAttachment(val name: String, val fileId: String)
data class ChatMessage(val role: String, val content: String, val reasoning: String = "", val citations: List<ChatCitation> = emptyList(), val serverToolCalls: String = "", val attachments: List<ChatAttachment> = emptyList(), val canContinue: Boolean = false)
data class ChatSession(val id: Long, val title: String, val messages: List<ChatMessage>)

class ChatStore(context: Context) {
    private val file = File(context.filesDir, "conversations.json")

    fun load(): List<ChatSession> = try { parse(file.readText()) } catch (_: Exception) { emptyList() }

    fun parse(text: String): List<ChatSession> {
        val root = JSONArray(text)
        return (0 until root.length()).map { index ->
            val item = root.getJSONObject(index)
            val messages = item.getJSONArray("messages")
            ChatSession(item.getLong("id"), item.getString("title"), (0 until messages.length()).map { messageIndex ->
                val message = messages.getJSONObject(messageIndex)
                val citations = message.optJSONArray("citations")
                ChatMessage(message.getString("role"), message.getString("content"), message.optString("reasoning"),
                    if (citations == null) emptyList() else (0 until citations.length()).map { citationIndex ->
                        citations.getJSONObject(citationIndex).let { ChatCitation(it.optString("title"), it.optString("url")) }
                    }, message.optString("serverToolCalls"), message.optJSONArray("attachments")?.let { files ->
                        (0 until files.length()).map { fileIndex -> files.getJSONObject(fileIndex).let { ChatAttachment(it.optString("name"), it.optString("fileId")) } }
                    } ?: emptyList(), message.optBoolean("canContinue") && !message.optBoolean("continueAsFollowUp"))
            })
        }
    }

    fun exportJson(sessions: List<ChatSession>): String {
        val root = JSONArray()
        sessions.forEach { session ->
            val messages = JSONArray()
            session.messages.forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("content", message.content).put("reasoning", message.reasoning)
                    .put("citations", JSONArray().apply { message.citations.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } })
                    .put("serverToolCalls", message.serverToolCalls)
                    .put("attachments", JSONArray().apply { message.attachments.forEach { put(JSONObject().put("name", it.name).put("fileId", it.fileId)) } })
                    .put("canContinue", message.canContinue))
            }
            root.put(JSONObject().put("id", session.id).put("title", session.title).put("messages", messages))
        }
        return root.toString(2)
    }

    fun mergeImport(existing: List<ChatSession>, json: String): List<ChatSession> {
        require(json.length <= 50 * 1024 * 1024) { "备份文件超过 50 MB" }
        val incoming = parse(json)
        val merged = existing.toMutableList()
        var nextId = maxOf(System.currentTimeMillis(), existing.maxOfOrNull { it.id } ?: 0L) + 1
        incoming.forEach { session ->
            val sameId = merged.firstOrNull { it.id == session.id }
            if (sameId == session) return@forEach
            merged.add(if (sameId == null) session else session.copy(id = nextId++))
        }
        return merged
    }

    fun save(sessions: List<ChatSession>) {
        val temporary = File(file.parentFile, "conversations.tmp")
        temporary.writeText(exportJson(sessions))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
