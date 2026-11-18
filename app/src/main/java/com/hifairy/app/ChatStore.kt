package com.hifairy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(val role: String, val content: String, val reasoning: String = "")
data class ChatSession(val id: Long, val title: String, val messages: List<ChatMessage>)

class ChatStore(context: Context) {
    private val file = File(context.filesDir, "conversations.json")

    fun load(): List<ChatSession> = try {
        val root = JSONArray(file.readText())
        (0 until root.length()).map { index ->
            val item = root.getJSONObject(index)
            val messages = item.getJSONArray("messages")
            ChatSession(item.getLong("id"), item.getString("title"), (0 until messages.length()).map { messageIndex ->
                val message = messages.getJSONObject(messageIndex)
                ChatMessage(message.getString("role"), message.getString("content"), message.optString("reasoning"))
            })
        }
    } catch (_: Exception) { emptyList() }

    fun save(sessions: List<ChatSession>) {
        val root = JSONArray()
        sessions.forEach { session ->
            val messages = JSONArray()
            session.messages.forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("content", message.content).put("reasoning", message.reasoning))
            }
            root.put(JSONObject().put("id", session.id).put("title", session.title).put("messages", messages))
        }
        val temporary = File(file.parentFile, "conversations.tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
