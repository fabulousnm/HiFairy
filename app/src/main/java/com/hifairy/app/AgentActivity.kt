package com.hifairy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class AgentSession(val id: String, val title: String)
private data class AgentTurn(val prompt: String, val response: String)

private class AgentConnection(private val settings: SecureSettings) {
    private val base: String
        get() {
            val voice = settings.voiceEndpoint.trim().removeSuffix("/stream")
            return voice.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").trimEnd('/') + "/agent"
        }

    private fun open(path: String, method: String = "GET", body: String? = null): HttpURLConnection {
        require(base.startsWith("https://")) { "请先在设置中填写 HTTPS 语音服务地址" }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = if (path.endsWith("/events")) 0 else 20000
        connection.setRequestProperty("Authorization", "Bearer ${settings.agentToken()}")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    private fun request(path: String, method: String = "GET", body: String? = null): JSONObject {
        val connection = open(path, method, body)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Agent 服务返回 $code")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun sessions(): List<AgentSession> {
        val items = request("/sessions").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            AgentSession(it.getString("sessionId"), it.optString("title").ifBlank { "新任务" })
        }
    }

    fun create(): String = request("/sessions", "POST", "{}").getString("sessionId")
    fun send(id: String, text: String) { request("/sessions/$id/messages", "POST", JSONObject().put("text", text).toString()) }

    fun turns(id: String): List<AgentTurn> {
        val json = request("/sessions/$id/snapshot")
        val outline = json.optJSONObject("projections")?.optJSONObject("values")?.optJSONArray("turnOutline") ?: return emptyList()
        return (0 until outline.length()).map { outline.getJSONObject(it) }.map {
            AgentTurn(it.optString("prompt"), it.optString("response"))
        }
    }

    fun follow(id: String, onChange: () -> Unit) {
        val connection = open("/sessions/$id/events")
        try {
            if (connection.responseCode != 200) throw IllegalStateException("实时连接失败：${connection.responseCode}")
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("data: ") && (line.contains("assistant/message") || line.contains("turn/end") || line.contains("user/message"))) onChange()
                }
            }
        } finally { connection.disconnect() }
    }
}

class AgentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        val api = AgentConnection(settings)
        setContent {
            val scope = rememberCoroutineScope()
            var tokenDraft by remember { mutableStateOf("") }
            var tokenReady by remember { mutableStateOf(settings.agentToken().isNotBlank()) }
            var sessions by remember { mutableStateOf(emptyList<AgentSession>()) }
            var selected by remember { mutableStateOf("") }
            var turns by remember { mutableStateOf(emptyList<AgentTurn>()) }
            var draft by remember { mutableStateOf("") }
            var error by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var listOpen by remember { mutableStateOf(false) }
            var pendingPrompt by remember { mutableStateOf("") }

            suspend fun refresh() {
                try {
                    val result = withContext(Dispatchers.IO) { api.sessions() }
                    sessions = result
                    if (selected.isNotEmpty()) turns = withContext(Dispatchers.IO) { api.turns(selected) }
                    error = ""
                } catch (e: Exception) { error = e.message ?: "连接失败" }
            }

            LaunchedEffect(tokenReady) { if (tokenReady) refresh() }
            LaunchedEffect(selected, tokenReady) {
                if (!tokenReady || selected.isBlank()) return@LaunchedEffect
                refresh()
                while (true) {
                    try {
                        withContext(Dispatchers.IO) { api.follow(selected) { scope.launch { refresh(); pendingPrompt = "" } } }
                    } catch (e: Exception) { error = e.message ?: "实时连接中断" }
                    delay(1500)
                    refresh()
                }
            }
            BackHandler(listOpen) { listOpen = false }

            Column(Modifier.fillMaxSize().background(ThemeTokens.background).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("☰", color = ThemeTokens.accent, fontSize = 26.sp, modifier = Modifier.clickable { listOpen = !listOpen }.padding(end = 20.dp))
                    if (selected.isNotBlank()) FairyEye(Modifier.size(38.dp).pointerInput(Unit) {
                        detectTapGestures(onPress = { if (withTimeoutOrNull(5000L) { tryAwaitRelease() } == null) finish() })
                    })
                    Text("FAIRY // AGENT", color = ThemeTokens.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("＋", color = ThemeTokens.accent, fontSize = 27.sp, modifier = Modifier.clickable {
                        scope.launch {
                            try { selected = withContext(Dispatchers.IO) { api.create() }; turns = emptyList(); listOpen = false; refresh() }
                            catch (e: Exception) { error = e.message ?: "创建失败" }
                        }
                    })
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (listOpen) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(sessions, key = { it.id }) { session ->
                                Text(session.title, color = if (selected == session.id) ThemeTokens.accent else ThemeTokens.text,
                                    modifier = Modifier.fillMaxWidth().clickable { selected = session.id; listOpen = false; pendingPrompt = "" }.padding(15.dp))
                            }
                        }
                    } else if (!tokenReady) {
                        Column(Modifier.align(Alignment.Center)) {
                            Text("输入电脑启动脚本显示的 Agent 令牌", color = ThemeTokens.text)
                            TextField(tokenDraft, { tokenDraft = it }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                            Button(onClick = { if (tokenDraft.isNotBlank()) { settings.saveAgentToken(tokenDraft.trim()); tokenDraft = ""; tokenReady = true } }) { Text("连接电脑 Agent") }
                        }
                    } else if (selected.isBlank()) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            FairyEye(Modifier.size(150.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    if (withTimeoutOrNull(5000L) { tryAwaitRelease() } == null) finish()
                                })
                            })
                            Text("长按眼徽 5 秒返回聊天", color = ThemeTokens.secondary)
                            Button(onClick = { scope.launch { try { selected = withContext(Dispatchers.IO) { api.create() }; refresh() } catch (e: Exception) { error = e.message ?: "创建失败" } } }) { Text("创建电脑任务") }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            turns.forEach { turn ->
                                if (turn.prompt.isNotBlank()) Text(turn.prompt, color = ThemeTokens.text, modifier = Modifier.fillMaxWidth().padding(6.dp).background(ThemeTokens.userBubble).padding(14.dp))
                                if (turn.response.isNotBlank()) Text(turn.response, color = ThemeTokens.text, modifier = Modifier.fillMaxWidth().padding(6.dp).background(ThemeTokens.fairyBubble).padding(14.dp))
                            }
                            if (pendingPrompt.isNotBlank()) Text("电脑正在处理：$pendingPrompt", color = ThemeTokens.secondary, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = ThemeTokens.accent, fontSize = 12.sp)
                if (selected.isNotBlank() && !listOpen) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(draft, { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("向电脑 DSH 发送任务") })
                        Button(enabled = !busy && draft.isNotBlank(), onClick = {
                            val text = draft.trim()
                            busy = true
                            scope.launch {
                                try { withContext(Dispatchers.IO) { api.send(selected, text) }; pendingPrompt = text; draft = ""; refresh() }
                                catch (e: Exception) { error = e.message ?: "发送失败" }
                                finally { busy = false }
                            }
                        }) { Text("发送") }
                    }
                }
            }
        }
    }
}
