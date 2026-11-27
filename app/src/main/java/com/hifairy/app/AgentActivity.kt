package com.hifairy.app

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import java.time.LocalTime
import java.time.Instant
import java.math.BigDecimal
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class AgentSession(val id: String, val title: String, val running: Boolean = false)
internal data class AgentItem(val id: String, val kind: String, val text: String, val title: String, val detailKey: String, val parentId: String, val hasResult: Boolean)
internal data class AgentDetail(val text: String = "", val input: String = "", val output: String = "", val terminal: Boolean = false)
internal data class AgentChoice(val id: String, val title: String, val isDefault: Boolean = false)
internal data class AgentApproval(val id: String, val toolName: String, val reason: String)

class AgentApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getStringExtra("session") ?: return
        val approval = intent.getStringExtra("approval") ?: return
        val allow = intent.getBooleanExtra("allow", false)
        val pending = goAsync()
        Thread {
            try {
                val api = AgentConnection(SecureSettings(context), context)
                try { api.decideApproval(session, approval, allow) } finally { api.close() }
            } catch (_: Exception) { }
            finally { pending.finish() }
        }.start()
    }
}
internal data class AgentFile(val name: String, val directory: Boolean)
internal data class AgentFolder(val path: String, val parent: String?, val items: List<AgentFile>)
internal data class AgentUpload(val name: String, val path: String)
private data class AgentPendingAttachment(val name: String, val uri: Uri)
private fun youkaiGreetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "早上好，法厄同"
    in 12..17 -> "下午好，法厄同"
    else -> "晚上好，法厄同"
}

private fun agentConnectionError(error: Exception): String =
    if (error is java.net.SocketTimeoutException || error.message?.contains("timeout", ignoreCase = true) == true)
        "连接超时，请确认电脑服务已启动，并检查设置中的临时通道地址是否更新"
    else error.message ?: "连接失败"

@Composable
private fun AgentChoiceIcon(folder: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        if (folder) {
            val shape = Path().apply {
                moveTo(size.width * .08f, size.height * .27f)
                lineTo(size.width * .39f, size.height * .27f)
                lineTo(size.width * .49f, size.height * .39f)
                lineTo(size.width * .92f, size.height * .39f)
                lineTo(size.width * .86f, size.height * .83f)
                lineTo(size.width * .12f, size.height * .83f)
                close()
            }
            drawPath(shape, YoukaiTokens.accent, style = stroke)
        } else {
            drawCircle(YoukaiTokens.accent, radius = size.minDimension * .39f, style = stroke)
            drawCircle(YoukaiTokens.accent, radius = size.minDimension * .14f)
            drawLine(YoukaiTokens.accent, androidx.compose.ui.geometry.Offset(size.width * .5f, 0f),
                androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .12f), strokeWidth = 1.6.dp.toPx())
        }
    }
}

@Composable
private fun AgentChoiceMenu(folder: Boolean, choices: List<AgentChoice>, selectedId: String,
                            loading: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId }
    val density = LocalDensity.current
    val popupPositioner = remember(density) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                       layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val gap = with(density) { 8.dp.roundToPx() }
            return IntOffset(anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0))
        }
    } }
    Box {
        Row(Modifier.widthIn(max = 168.dp).height(34.dp)
            .shadow(3.dp, RoundedCornerShape(12.dp))
            .background(YoukaiTokens.raisedCard, RoundedCornerShape(12.dp))
            .border(1.dp, YoukaiTokens.accent.copy(alpha = .35f), RoundedCornerShape(12.dp))
            .clickable(enabled = choices.isNotEmpty()) { expanded = true }
            .padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentChoiceIcon(folder)
            Text(selected?.title ?: if (loading) "同步中…" else "暂无选项",
                color = YoukaiTokens.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("⌃", color = YoukaiTokens.accent, fontSize = 11.sp)
        }
        if (expanded) Popup(popupPositionProvider = popupPositioner,
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.widthIn(min = 185.dp, max = 260.dp).heightIn(max = 350.dp)
                .shadow(18.dp, RoundedCornerShape(16.dp))
                .background(YoukaiTokens.surface, RoundedCornerShape(16.dp))
                .border(1.dp, YoukaiTokens.accent.copy(alpha = .48f), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(6.dp)) {
                choices.forEach { choice ->
                    val active = choice.id == selectedId
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .background(if (active) YoukaiTokens.accent.copy(alpha = .19f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onSelect(choice.id); expanded = false }
                        .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(choice.title, color = if (active) YoukaiTokens.accent else YoukaiTokens.text,
                            fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (active) Text("✓", color = YoukaiTokens.accent, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentTerminalSection(label: String, content: String, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 7.dp).background(Color(YoukaiTokens.codeBackground), RoundedCornerShape(11.dp))
        .border(1.dp, YoukaiTokens.codeCardRim.let { Color(it) }.copy(alpha = .65f), RoundedCornerShape(11.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("●  $label", color = YoukaiTokens.accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            MessageAction("copy", "复制$label", onCopy, YoukaiTokens.secondary)
        }
        Text(content.ifBlank { "（无内容）" }, color = YoukaiTokens.text, fontSize = 11.sp, lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
    }
}

@Composable
private fun AgentRecordCard(item: AgentItem, detail: AgentDetail?, depth: Int = 0, childCount: Int = 0,
                            onClick: () -> Unit, onCopy: (String, String) -> Unit) {
    val tint = when (item.kind) {
        "tool" -> YoukaiTokens.tool
        "terminal" -> YoukaiTokens.tool
        "work" -> YoukaiTokens.work
        "commentary" -> YoukaiTokens.work
        else -> YoukaiTokens.accent
    }
    Column(Modifier.fillMaxWidth().padding(start = (44 + depth * 14).dp, bottom = 3.dp), horizontalAlignment = Alignment.Start) {
        Row(Modifier.width(270.dp - (depth * 14).dp).background(YoukaiTokens.reasoningBubble, RoundedCornerShape(8.dp))
            .border(1.dp, tint.copy(alpha = .24f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((when (item.kind) { "tool" -> "⚙"; "terminal" -> "⌁"; "work" -> "◇"; else -> "◌" }) + "  " + item.title +
                if (childCount > 0) "  ·  $childCount 项" else "",
                color = tint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (detail == null) "›" else "⌄", color = tint, fontSize = 11.sp)
        }
        if (detail != null) Column(Modifier.width(270.dp - (depth * 14).dp).padding(top = 4.dp)
            .background(YoukaiTokens.surface, RoundedCornerShape(9.dp))
            .border(1.dp, tint.copy(alpha = .25f), RoundedCornerShape(9.dp)).padding(9.dp)) {
            if (item.kind == "tool") {
                if (detail.terminal) {
                    AgentTerminalSection("输入", detail.input) { onCopy("工具输入", detail.input) }
                    AgentTerminalSection("输出", detail.output) { onCopy("工具输出", detail.output) }
                } else {
                    Text("输入", color = tint, fontSize = 10.sp)
                    MarkdownMessage(detail.input, Modifier.fillMaxWidth(), textSizeSp = 12f, eyeStyle = EyeStyle.YOUKAI)
                    Text("输出", color = tint, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                    MarkdownMessage(detail.output, Modifier.fillMaxWidth(), textSizeSp = 12f, eyeStyle = EyeStyle.YOUKAI)
                }
            } else if (item.kind == "terminal") AgentTerminalSection("终端记录", detail.text) { onCopy("终端记录", detail.text) }
            else MarkdownMessage(detail.text, Modifier.fillMaxWidth(), textSizeSp = 12f, eyeStyle = EyeStyle.YOUKAI)
        }
    }
}

@Composable
private fun AgentHistoryLoading() {
    Column(Modifier.background(Brush.verticalGradient(listOf(YoukaiTokens.surface, YoukaiTokens.reasoningBubble)), RoundedCornerShape(24.dp))
        .border(1.dp, YoukaiTokens.accent.copy(alpha = .22f), RoundedCornerShape(24.dp))
        .padding(horizontal = 30.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            FairyEye(Modifier.size(54.dp), style = EyeStyle.YOUKAI)
            androidx.compose.material3.CircularProgressIndicator(Modifier.fillMaxSize(), color = YoukaiTokens.accent,
                strokeWidth = 2.dp, trackColor = YoukaiTokens.accent.copy(alpha = .12f))
        }
        Spacer(Modifier.height(16.dp))
        Text("正在接入会话", color = YoukaiTokens.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("读取最近 15 轮问答", color = YoukaiTokens.secondary, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        androidx.compose.material3.LinearProgressIndicator(Modifier.width(150.dp).height(3.dp), color = YoukaiTokens.accent,
            trackColor = YoukaiTokens.accent.copy(alpha = .13f))
    }
}

@Composable
private fun AgentHoldEye(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, active: Boolean = false,
                         blinkToken: Int = 0, onSwitch: () -> Unit, onTap: () -> Unit, onDoubleTap: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var holdTriggered by remember { mutableStateOf(false) }
    Box(modifier.size(size)) {
        FairyEye(Modifier.fillMaxSize().pointerInput(active) {
            detectTapGestures(onPress = {
                progress.snapTo(0f)
                val animation = scope.launch { progress.animateTo(1f, tween(AGENT_MODE_HOLD_MS.toInt())) }
                val released = withTimeoutOrNull(AGENT_MODE_HOLD_MS) { tryAwaitRelease() }
                animation.cancel()
                if (released == null) {
                    holdTriggered = true
                    onSwitch()
                    scope.launch { delay(500); holdTriggered = false }
                }
                progress.animateTo(0f, tween(180))
            }, onTap = { if (!active && !holdTriggered) onTap() },
                onDoubleTap = { if (!active) onDoubleTap() })
        }, active = active, blinkToken = blinkToken, style = EyeStyle.YOUKAI)
        if (progress.value > .001f) Canvas(Modifier.matchParentSize()) {
            drawArc(YoukaiTokens.accent, -90f, 360f * progress.value, false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

internal class AgentConnection(private val settings: SecureSettings, private val context: android.content.Context,
                               private val allowPhoneWorkspace: Boolean = false) {
    private val socketClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var followSocket: WebSocket? = null
    private val base: String
        get() {
            val voice = settings.voiceEndpoint.trim().removeSuffix("/stream")
            return voice.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").trimEnd('/') + "/agent"
        }

    private fun open(path: String, method: String = "GET", body: String? = null): HttpURLConnection {
        require(base.startsWith("https://")) { "请先在设置中填写 HTTPS 语音服务地址" }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = if (path.endsWith("/events")) 0 else if (path.contains("/upload?") || path.contains("/attachments?") || path.contains("/download?")) 120000 else 20000
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
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}")
                    .optString("detail") }.getOrDefault("")
                if (code == 503 && detail == "bridge_unavailable")
                    throw IllegalStateException("电脑 DSH 桥接未启动，请检查 DSH 插件和本机 18765 端口")
                throw IllegalStateException("Agent 服务返回 $code${if (detail.isNotBlank()) "：$detail" else ""}")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun sessions(): List<AgentSession> {
        val items = request("/sessions").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            AgentSession(it.getString("sessionId"), it.optString("title").ifBlank { "新任务" }, it.optBoolean("running"))
        }
    }

    fun modes(): List<AgentChoice> {
        val items = request("/modes").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optBoolean("available", true) }.map {
            AgentChoice(it.getString("id"), it.optString("title").ifBlank { it.getString("id") }, it.optBoolean("isDefault"))
        }
    }
    fun workspaces(): List<AgentChoice> {
        val items = request("/workspaces").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            AgentChoice(it.getString("id"), it.optString("title").ifBlank { it.optString("path") })
        }
    }
    fun create(workspaceId: String, agentPreset: String, permission: String): String = request("/sessions", "POST",
        JSONObject().put("workspaceId", workspaceId).put("agentPreset", agentPreset).put("permission", permission).toString()).getString("sessionId")
    fun permissions(): Pair<List<AgentChoice>, String> {
        val json = request("/permissions")
        val options = json.getJSONArray("options")
        return (0 until options.length()).map { options.getJSONObject(it) }.filter { it.optString("value") != "custom" }
            .map { AgentChoice(it.getString("value"), it.optString("name").ifBlank { it.getString("value") }) } to json.optString("defaultPreset")
    }
    fun permission(sessionId: String): String = request("/sessions/$sessionId/permission").getString("value")
    fun setPermission(sessionId: String, value: String): String = request("/sessions/$sessionId/permission", "POST",
        JSONObject().put("value", value).toString()).getString("value")
    fun approvals(sessionId: String): List<AgentApproval> {
        val items = request("/sessions/$sessionId/approvals").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            AgentApproval(it.getString("id"), it.optString("toolName"), it.optString("reason"))
        }
    }
    fun decideApproval(sessionId: String, approvalId: String, allow: Boolean) {
        request("/sessions/$sessionId/approvals/$approvalId", "POST",
            JSONObject().put("outcome", if (allow) "allowed-once" else "rejected").toString())
    }
    private fun answerPhoneWorkspace(sessionId: String, frame: JSONObject) {
        val id = frame.optString("id")
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return
        Thread {
            val result = runCatching {
                val store = PhoneWorkspaceStore(context)
                when (frame.optString("action")) {
                    "probe" -> store.probe()
                    "list" -> store.listPage(frame.getInt("offset"))
                    "test_write" -> store.testWrite(frame.getString("name"), frame.getString("content"))
                    else -> "未知手机工作区请求，已拒绝。"
                }
            }.getOrElse { "手机工作区操作失败：${it.javaClass.simpleName}: ${it.message}" }
            runCatching {
                request("/sessions/$sessionId/phone-workspace-result", "POST",
                    JSONObject().put("id", id).put("text", result).toString())
            }
        }.start()
    }
    fun send(id: String, text: String, receipts: List<String> = emptyList()) {
        val body = JSONObject().put("text", text).put("attachments", org.json.JSONArray(receipts))
        request("/sessions/$id/messages", "POST", body.toString())
    }
    fun cancel(id: String) { request("/sessions/$id/cancel", "POST", "{}") }
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    fun folder(workspaceId: String, path: String): AgentFolder {
        val endpoint = if (workspaceId.isBlank())
            if (path.isBlank()) "/filesystem/roots" else "/filesystem/files?path=${encoded(path)}"
        else "/workspaces/$workspaceId/files?path=${encoded(path)}"
        val json = request(endpoint)
        val entries = json.getJSONArray("items")
        return AgentFolder(json.optString("path"), if (json.has("parent") && !json.isNull("parent")) json.getString("parent") else null,
            (0 until entries.length()).map { entries.getJSONObject(it) }.map { AgentFile(it.getString("name"), it.optBoolean("directory")) })
    }
    fun upload(workspaceId: String, path: String, name: String, bytes: ByteArray): AgentUpload {
        val endpoint = if (workspaceId.isBlank()) "/filesystem/upload" else "/workspaces/$workspaceId/upload"
        val connection = open("$endpoint?path=${encoded(path)}&name=${encoded(name)}", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode !in 200..299) throw IllegalStateException("上传失败：${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return AgentUpload(json.getString("name"), json.getString("path"))
        } finally { connection.disconnect() }
    }
    fun uploadAttachment(sessionId: String, name: String, bytes: ByteArray): String {
        val connection = open("/sessions/$sessionId/attachments?name=${encoded(name)}", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode !in 200..299) throw IllegalStateException("附件上传失败：${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("receiptId")
        } finally { connection.disconnect() }
    }
    fun download(workspaceId: String, path: String): ByteArray {
        val endpoint = if (workspaceId.isBlank()) "/filesystem/download" else "/workspaces/$workspaceId/download"
        val connection = open("$endpoint?path=${encoded(path)}")
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("下载失败：${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    fun recent(id: String): List<AgentItem> {
        val items = request("/sessions/$id/recent").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            AgentItem(it.optString("id"), it.optString("kind"), it.optString("text"),
                it.optString("title"), it.optString("detailKey"), it.optString("parentId"), it.optBoolean("hasResult"))
        }
    }

    fun detail(id: String, item: AgentItem): AgentDetail {
        val json = request("/sessions/$id/details/${item.kind}/${item.detailKey}")
        return if (item.kind == "tool") AgentDetail(input = json.optString("input"), output = json.optString("result"), terminal = json.optBoolean("terminal"))
            else AgentDetail(text = json.optString("text"))
    }

    suspend fun follow(id: String, onFrame: (JSONObject) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val socketUrl = base.replaceFirst("https://", "wss://") + "/live/$id"
        val socket = socketClient.newWebSocket(Request.Builder().url(socketUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("token", settings.agentToken()).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = JSONObject(text)
                    if (frame.optString("type") == "phone-workspace-request") {
                        if (allowPhoneWorkspace) answerPhoneWorkspace(id, frame)
                    }
                    else onFrame(frame)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                    webSocket.cancel()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        })
        followSocket = socket
        continuation.invokeOnCancellation { socket.cancel() }
    }

    fun closeFollow() { followSocket?.cancel(); followSocket = null }
    fun close() { closeFollow(); socketClient.dispatcher.executorService.shutdown(); socketClient.connectionPool.evictAll() }
}

class AgentActivity : ComponentActivity() {
    private var balancePlayer: MediaPlayer? = null
    private var activeVoiceInput: ParaformerVoiceInput? = null

    private fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(AGENT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun stopBalanceVoice() {
        balancePlayer?.let { player ->
            balancePlayer = null
            try { player.stop() } catch (_: Exception) { }
            player.release()
        }
    }

    private fun playBalanceVoice(low: Boolean) {
        stopBalanceVoice()
        val resource = if (low) R.raw.fairy_balance_low else R.raw.fairy_balance_ok
        val player = MediaPlayer.create(this, resource) ?: return
        balancePlayer = player
        player.setOnCompletionListener { if (balancePlayer === player) balancePlayer = null; player.release() }
        player.start()
    }

    private fun playYoukaiGreeting() {
        stopBalanceVoice()
        val resource = when (LocalTime.now().hour) {
            in 5..11 -> R.raw.youkai_greet_morning
            in 12..17 -> R.raw.youkai_greet_afternoon
            else -> R.raw.youkai_greet_evening
        }
        balancePlayer = MediaPlayer.create(this, resource)?.apply {
            setOnCompletionListener { stopBalanceVoice() }
            start()
        }
    }

    override fun onPause() {
        stopBalanceVoice()
        super.onPause()
    }

    override fun onStop() {
        activeVoiceInput?.let { if (it.recording) it.finish(true) }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        val api = AgentConnection(settings, this, true)
        setContent {
            val scope = rememberCoroutineScope()
            var modeSwitchTrigger by remember { mutableIntStateOf(0) }
            var modeSwitchOrigin by remember { mutableStateOf(Offset.Zero) }
            val entryOrigin = remember { Offset(intent.getFloatExtra("reveal_x", 0f), intent.getFloatExtra("reveal_y", 0f)) }
            val reveal = remember { Animatable(0f) }
            LaunchedEffect(Unit) { reveal.animateTo(1f, tween(720)) }
            LaunchedEffect(modeSwitchTrigger) {
                if (modeSwitchTrigger > 0) {
                    reveal.animateTo(0f, tween(720))
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }
            var tokenReady by remember { mutableStateOf(settings.agentToken().isNotBlank()) }
            var listOpen by remember { mutableStateOf(false) }
            var settingsOpenedFromDrawer by remember { mutableStateOf(false) }
            val privacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                tokenReady = settings.agentToken().isNotBlank()
                if (settingsOpenedFromDrawer) listOpen = true
                settingsOpenedFromDrawer = false
            }
            var sessions by remember { mutableStateOf(emptyList<AgentSession>()) }
            var selected by remember { mutableStateOf(intent.getStringExtra("sessionId").orEmpty()) }
            var items by remember { mutableStateOf(emptyList<AgentItem>()) }
            var loadingHistory by remember { mutableStateOf(false) }
            var loadedSession by remember { mutableStateOf("") }
            val expanded = remember { mutableStateMapOf<String, AgentDetail>() }
            var draft by remember { mutableStateOf("") }
            var error by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var fileDrawerOpen by remember { mutableStateOf(false) }
            var browseComputer by remember { mutableStateOf(false) }
            var computerPathDraft by remember { mutableStateOf("") }
            var folder by remember { mutableStateOf<AgentFolder?>(null) }
            var folderLoading by remember { mutableStateOf(false) }
            var folderError by remember { mutableStateOf("") }
            var folderWorkspace by remember { mutableStateOf("") }
            var folderRequestSerial by remember { mutableIntStateOf(0) }
            var attachOnUpload by remember { mutableStateOf(false) }
            val pendingUploads = remember { mutableStateListOf<AgentPendingAttachment>() }
            var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }
            var interruptedSession by remember { mutableStateOf("") }
            var interruptedText by remember { mutableStateOf("") }
            var modes by remember { mutableStateOf(emptyList<AgentChoice>()) }
            var workspaces by remember { mutableStateOf(emptyList<AgentChoice>()) }
            var permissions by remember { mutableStateOf(emptyList<AgentChoice>()) }
            var chosenMode by remember { mutableStateOf("") }
            var chosenWorkspace by remember { mutableStateOf("") }
            var chosenPermission by remember { mutableStateOf("") }
            var pendingApproval by remember { mutableStateOf<AgentApproval?>(null) }
            var approvalBusy by remember { mutableStateOf(false) }
            val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null && (chosenWorkspace.isNotBlank() || (fileDrawerOpen && browseComputer))) scope.launch {
                    try {
                        var name = "附件"
                        var declaredSize = -1L
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(0) ?: name
                                if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
                            }
                        }
                        if (declaredSize > 25L * 1024 * 1024) throw IllegalStateException("附件最大 25 MB")
                        if (attachOnUpload) {
                            if (pendingUploads.size >= 5) throw IllegalStateException("每次最多添加 5 个附件")
                            pendingUploads.add(AgentPendingAttachment(name, uri))
                        }
                        else {
                            val bytes = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readNBytes(25 * 1024 * 1024 + 1) } ?: throw IllegalStateException("无法读取附件") }
                            if (bytes.size > 25 * 1024 * 1024) throw IllegalStateException("附件最大 25 MB")
                            val currentPath = folder?.path.orEmpty()
                            val target = if (browseComputer) "" else chosenWorkspace
                            withContext(Dispatchers.IO) { api.upload(target, currentPath, name, bytes) }
                            if (fileDrawerOpen) folder = withContext(Dispatchers.IO) { api.folder(target, currentPath) }
                        }
                        error = ""
                    } catch (e: Exception) { error = e.message ?: "上传失败" }
                }
            }
            val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
                val target = pendingSave
                pendingSave = null
                if (uri != null && target != null) scope.launch {
                    try {
                        val bytes = withContext(Dispatchers.IO) { api.download(target.first, target.second) }
                        withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("无法保存文件") }
                        error = ""
                    } catch (e: Exception) { error = e.message ?: "下载失败" }
                }
            }
            var choicesLoading by remember { mutableStateOf(false) }
            var choicesRefreshVersion by remember { mutableIntStateOf(0) }
            val drawerWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() * .75f }
            val fileDrawerWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() * .84f }
            val drawerProgress = remember { Animatable(0f) }
            val fileDrawerProgress = remember { Animatable(0f) }
            var dragProgress by remember { mutableFloatStateOf(0f) }
            var fileDragProgress by remember { mutableFloatStateOf(0f) }
            var draggingDrawer by remember { mutableStateOf(false) }
            var dragSide by remember { mutableIntStateOf(0) }
            var dragStartedOpen by remember { mutableStateOf(false) }
            var settleVersion by remember { mutableIntStateOf(0) }
            var pendingPrompt by remember { mutableStateOf("") }
            var streamConnected by remember { mutableStateOf(false) }
            var voiceMode by remember { mutableStateOf(false) }
            var voiceHolding by remember { mutableStateOf(false) }
            var voiceCancel by remember { mutableStateOf(false) }
            var voiceStatus by remember { mutableStateOf("") }
            var voiceLevel by remember { mutableFloatStateOf(0f) }
            var balanceVoiceEnabled by remember { mutableStateOf(settings.agentBalanceVoiceEnabled) }
            LaunchedEffect(Unit) {
                delay(760)
                if (balanceVoiceEnabled && selected.isBlank()) playYoukaiGreeting()
            }
            var eyeCardOpen by remember { mutableStateOf(false) }
            var balanceLoading by remember { mutableStateOf(false) }
            var accountBalance by remember { mutableStateOf<AccountBalance?>(null) }
            var balanceRequestSerial by remember { mutableIntStateOf(0) }
            var eyeBlinkToken by remember { mutableIntStateOf(0) }
            var balanceClock by remember { mutableStateOf(Instant.now()) }
            LaunchedEffect(eyeCardOpen) {
                while (eyeCardOpen) { balanceClock = Instant.now(); delay(1000) }
            }
            var liveText by remember { mutableStateOf("") }
            var liveReasoning by remember { mutableStateOf("") }
            val streamReducer = remember(selected) { AgentStreamReducer() }
            DisposableEffect(selected) { onDispose { api.closeFollow() } }
            DisposableEffect(Unit) { onDispose { api.close() } }

            fun newTask() {
                AgentMonitorService.stop(this@AgentActivity)
                stopBalanceVoice()
                eyeCardOpen = false
                balanceRequestSerial++
                selected = ""
                items = emptyList()
                loadedSession = ""
                expanded.clear()
                pendingPrompt = ""
                liveText = ""
                liveReasoning = ""
                streamConnected = false
                listOpen = false
                fileDrawerOpen = false
                interruptedSession = ""
                interruptedText = ""
                pendingUploads.clear()
                pendingApproval = null
                choicesRefreshVersion++
            }

            suspend fun refreshList() {
                try {
                    sessions = withContext(Dispatchers.IO) { api.sessions() }
                    error = ""
                } catch (e: Exception) { error = agentConnectionError(e) }
            }

            suspend fun refreshChoices() {
                choicesLoading = true
                try {
                    val freshModes = withContext(Dispatchers.IO) { api.modes() }
                    val freshWorkspaces = withContext(Dispatchers.IO) { api.workspaces() }
                    val (freshPermissions, defaultPermission) = withContext(Dispatchers.IO) { api.permissions() }
                    modes = freshModes
                    workspaces = freshWorkspaces
                    permissions = freshPermissions
                    if (freshModes.none { it.id == chosenMode }) chosenMode = freshModes.firstOrNull { it.isDefault }?.id ?: freshModes.firstOrNull()?.id.orEmpty()
                    if (freshWorkspaces.none { it.id == chosenWorkspace }) chosenWorkspace = freshWorkspaces.firstOrNull()?.id.orEmpty()
                    if (freshPermissions.none { it.id == chosenPermission }) chosenPermission = defaultPermission
                    error = ""
                } catch (e: Exception) { error = agentConnectionError(e) }
                finally { choicesLoading = false }
            }

            suspend fun refreshSelected() {
                val target = selected
                if (target.isBlank()) return
                loadingHistory = true
                try {
                    val result = withContext(Dispatchers.IO) { api.recent(target) }
                    if (selected == target) { items = result; loadedSession = target; error = "" }
                } catch (e: Exception) { if (selected == target) error = e.message ?: "读取会话失败" }
                finally { if (selected == target) loadingHistory = false }
            }
            suspend fun refreshPermission() {
                val target = selected
                if (target.isBlank()) return
                try {
                    val value = withContext(Dispatchers.IO) { api.permission(target) }
                    if (selected == target) chosenPermission = value
                } catch (e: Exception) { if (selected == target) error = agentConnectionError(e) }
            }
            suspend fun refreshApproval() {
                val target = selected
                if (target.isBlank()) return
                try {
                    val requests = withContext(Dispatchers.IO) { api.approvals(target) }
                    if (selected == target) {
                        pendingApproval = requests.firstOrNull()
                    }
                } catch (e: Exception) { if (selected == target) error = agentConnectionError(e) }
            }
            fun choosePermission(value: String) {
                if (selected.isBlank()) { chosenPermission = value; return }
                val target = selected
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { api.setPermission(target, value) }
                        if (selected == target) chosenPermission = result
                    } catch (e: Exception) { error = agentConnectionError(e) }
                }
            }
            fun decideApproval(allow: Boolean) {
                val approval = pendingApproval ?: return
                val target = selected
                approvalBusy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.decideApproval(target, approval.id, allow) }
                        if (selected == target) pendingApproval = null
                    } catch (e: Exception) { error = agentConnectionError(e); refreshApproval() }
                    finally { approvalBusy = false }
                }
            }

            fun toggleRecord(item: AgentItem) {
                if (expanded.containsKey(item.id)) { expanded.remove(item.id); return }
                if (item.kind == "work") { expanded[item.id] = AgentDetail(text = item.text); return }
                scope.launch {
                    val requestedSession = selected
                    expanded[item.id] = AgentDetail(text = "正在读取…")
                    try {
                        val detail = withContext(Dispatchers.IO) { api.detail(requestedSession, item) }
                        if (selected == requestedSession) expanded[item.id] = detail
                    } catch (e: Exception) { if (selected == requestedSession) expanded[item.id] = AgentDetail(text = e.message ?: "读取失败") }
                }
            }

            fun submit(text: String) {
                if (busy || (text.isBlank() && pendingUploads.isEmpty())) return
                busy = true
                scope.launch {
                    try {
                        if (selected.isBlank()) {
                            streamConnected = false
                            if (chosenMode.isBlank() || chosenWorkspace.isBlank() || chosenPermission.isBlank()) throw IllegalStateException("请先选择电脑模式、工作区和权限")
                            selected = withContext(Dispatchers.IO) { api.create(chosenWorkspace, chosenMode, chosenPermission) }
                            items = emptyList()
                        }
                        withTimeout(10000L) { while (!streamConnected) delay(50) }
                        val attachments = pendingUploads.toList()
                        val receipts = withContext(Dispatchers.IO) { attachments.map { attachment ->
                            val bytes = contentResolver.openInputStream(attachment.uri)?.use { it.readNBytes(25 * 1024 * 1024 + 1) }
                                ?: throw IllegalStateException("无法读取附件")
                            if (bytes.size > 25 * 1024 * 1024) throw IllegalStateException("附件最大 25 MB")
                            api.uploadAttachment(selected, attachment.name, bytes)
                        } }
                        pendingPrompt = text.trim().ifBlank { "已发送 ${attachments.size} 个附件" }
                        interruptedSession = ""
                        interruptedText = ""
                        AgentMonitorService.monitor(this@AgentActivity, selected)
                        withContext(Dispatchers.IO) { api.send(selected, text.trim(), receipts) }
                        draft = ""
                        pendingUploads.clear()
                        refreshSelected()
                        refreshList()
                    } catch (e: Exception) {
                        AgentMonitorService.stop(this@AgentActivity)
                        pendingPrompt = ""
                        error = e.message ?: "发送失败"
                    } finally { busy = false }
                }
            }

            fun loadFolder(workspaceId: String, path: String) {
                folderLoading = true
                folderError = ""
                folder = null // Release the previous directory before requesting the next one.
                folderWorkspace = workspaceId
                val serial = ++folderRequestSerial
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { api.folder(workspaceId, path) }
                        if (serial == folderRequestSerial && folderWorkspace == workspaceId && fileDrawerOpen) {
                            folder = result
                            if (workspaceId.isBlank()) computerPathDraft = result.path
                            error = ""
                        }
                    } catch (e: Exception) {
                        if (serial == folderRequestSerial) folderError = if (e.message?.contains("404") == true)
                            "电脑文件接口未加载，请重启电脑上的 DSH 后重试" else agentConnectionError(e)
                    }
                    finally { if (serial == folderRequestSerial) folderLoading = false }
                }
            }

            fun openHistoryDrawer() {
                scope.launch {
                    if (fileDrawerOpen || fileDrawerProgress.value > .001f) {
                        fileDrawerOpen = false
                        fileDrawerProgress.animateTo(0f, DrawerMotion.settle)
                        folder = null
                    }
                    listOpen = true
                }
            }

            fun openFilesDrawer() {
                scope.launch {
                    if (listOpen || drawerProgress.value > .001f) {
                        listOpen = false
                        drawerProgress.animateTo(0f, DrawerMotion.settle)
                    }
                    browseComputer = chosenWorkspace.isBlank()
                    fileDrawerOpen = true
                    loadFolder(if (browseComputer) "" else chosenWorkspace, "")
                }
            }

            fun previewFile(workspaceId: String, path: String, name: String) {
                scope.launch {
                    try {
                        val bytes = withContext(Dispatchers.IO) { api.download(workspaceId, path) }
                        val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                        val preview = File(cacheDir, "agent-preview").apply { mkdirs() }
                        preview.listFiles()?.forEach { it.delete() }
                        val local = File(preview, safeName)
                        withContext(Dispatchers.IO) { local.writeBytes(bytes) }
                        val uri = FileProvider.getUriForFile(this@AgentActivity, "$packageName.files", local)
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(local.extension.lowercase()) ?: "application/octet-stream"
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        error = ""
                    } catch (e: Exception) { error = e.message ?: "本机无法预览此文件" }
                }
            }

            fun cancelTask() {
                val session = selected
                if (session.isBlank()) return
                AgentMonitorService.stop(this@AgentActivity)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.cancel(session) }
                        interruptedSession = session
                        interruptedText = liveText
                        pendingPrompt = ""
                        refreshSelected()
                        refreshList()
                    } catch (e: Exception) { error = e.message ?: "终止失败" }
                }
            }

            val voiceInput = remember { ParaformerVoiceInput(this@AgentActivity, { voiceStatus = it }, { voiceLevel = it }, { transcript -> submit(transcript) }) }
            activeVoiceInput = voiceInput
            DisposableEffect(Unit) { onDispose { voiceInput.close() } }
            val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) voiceInput.prepare() else voiceStatus = "需要麦克风权限"
            }
            var notificationsGranted by remember { mutableStateOf(notificationsEnabled()) }
            val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                notificationsGranted = it && notificationsEnabled()
            }
            val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                notificationsGranted = notificationsEnabled()
            }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            LaunchedEffect(tokenReady) { if (tokenReady) refreshList() }
            LaunchedEffect(tokenReady, choicesRefreshVersion) {
                if (!tokenReady) return@LaunchedEffect
                refreshChoices()
            }
            LaunchedEffect(selected, tokenReady) {
                pendingApproval = null
                if (tokenReady && selected.isNotBlank()) {
                    refreshSelected()
                    refreshPermission()
                    refreshApproval()
                }
            }
            LaunchedEffect(selected, tokenReady) {
                if (!tokenReady || selected.isBlank()) return@LaunchedEffect
                streamConnected = false
                while (true) {
                    try {
                        val followingSession = selected
                        api.follow(followingSession) { frame ->
                            scope.launch {
                                if (selected != followingSession) return@launch
                                if (frame.optString("type") == "snapshot") {
                                    streamConnected = true
                                    if (loadedSession != followingSession) refreshSelected()
                                }
                                if (streamReducer.apply(frame)) {
                                    liveText = streamReducer.text
                                    liveReasoning = streamReducer.reasoning
                                }
                                val eventType = frame.optJSONObject("event")?.optString("type")
                                if (eventType == "user/message" && interruptedSession == followingSession && pendingPrompt.isBlank()) {
                                    interruptedSession = ""; interruptedText = ""
                                }
                                if (eventType in setOf("tool/call", "tool/result", "step/start", "step/end")) refreshSelected()
                                if (eventType in setOf("approval/asked", "approval/decided")) refreshApproval()
                                if (frame.optString("type") == "event" && eventType == "assistant/message") {
                                    refreshSelected()
                                    liveText = ""
                                    liveReasoning = ""
                                }
                                if (frame.optString("type") == "event" && eventType == "turn/end") {
                                    refreshSelected()
                                    refreshList()
                                    liveText = ""
                                    liveReasoning = ""
                                    pendingPrompt = ""
                                }
                            }
                        }
                    } catch (e: Exception) { error = agentConnectionError(e) }
                    delay(1500)
                    refreshList()
                }
            }
            BackHandler(fileDrawerOpen) {
                val parent = folder?.parent
                if (parent != null) loadFolder(if (browseComputer) "" else chosenWorkspace, parent)
                else { fileDrawerOpen = false; folder = null }
            }
            BackHandler(listOpen && !fileDrawerOpen) { listOpen = false }
            BackHandler(!listOpen && !fileDrawerOpen) { if (modeSwitchTrigger == 0) modeSwitchTrigger++ }
            LaunchedEffect(listOpen, fileDrawerOpen, selected, pendingPrompt) {
                if (listOpen || fileDrawerOpen || pendingPrompt.isNotBlank()) {
                    eyeCardOpen = false
                    balanceRequestSerial++
                    stopBalanceVoice()
                }
            }
            LaunchedEffect(listOpen, fileDrawerOpen, settleVersion) {
                coroutineScope {
                    launch {
                        val target = if (listOpen) 1f else 0f
                        drawerProgress.animateTo(target, DrawerMotion.settle)
                        drawerProgress.snapTo(target)
                    }
                    launch {
                        val target = if (fileDrawerOpen) 1f else 0f
                        fileDrawerProgress.animateTo(target, DrawerMotion.settle)
                        fileDrawerProgress.snapTo(target)
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxSize()
                .modeCircularReveal(reveal.value, if (modeSwitchTrigger > 0) modeSwitchOrigin else entryOrigin, YoukaiTokens.accent)
                .background(YoukaiTokens.background).draggable(
                state = rememberDraggableState { delta ->
                    if (dragSide == 0) dragSide = when {
                        listOpen || drawerProgress.value > .01f -> 1
                        fileDrawerOpen || fileDrawerProgress.value > .01f -> -1
                        delta >= 0 -> 1
                        else -> -1
                    }
                    if (dragSide == 1) dragProgress = (dragProgress + delta / drawerWidthPx).coerceIn(0f, 1f)
                    else fileDragProgress = (fileDragProgress - delta / fileDrawerWidthPx).coerceIn(0f, 1f)
                },
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    drawerProgress.stop(); fileDrawerProgress.stop()
                    dragProgress = drawerProgress.value.coerceIn(0f, 1f)
                    fileDragProgress = fileDrawerProgress.value.coerceIn(0f, 1f)
                    dragSide = 0
                    dragStartedOpen = dragProgress >= .5f || fileDragProgress >= .5f
                    draggingDrawer = true
                },
                onDragStopped = { velocity ->
                    val directedVelocity = if (dragSide == -1) -velocity else velocity
                    val currentProgress = if (dragSide == -1) fileDragProgress else dragProgress
                    val open = when {
                        directedVelocity > DrawerMotion.flingVelocity -> true
                        directedVelocity < -DrawerMotion.flingVelocity -> false
                        dragStartedOpen -> currentProgress >= DrawerMotion.closeThreshold
                        else -> currentProgress >= DrawerMotion.openThreshold
                    }
                    if (dragSide == -1) {
                        fileDrawerProgress.snapTo(fileDragProgress)
                        fileDrawerOpen = open
                        listOpen = false
                        if (open && folder == null) loadFolder(if (browseComputer) "" else chosenWorkspace, "")
                        if (!open) folder = null
                    } else {
                        drawerProgress.snapTo(dragProgress)
                        listOpen = open
                        fileDrawerOpen = false
                        if (!open) folder = null
                    }
                    draggingDrawer = false
                    dragSide = 0
                    settleVersion++
                }
            )) {
            val drawerWidth = maxWidth * .75f
            val filesWidth = maxWidth * .84f
            val progress = (if (draggingDrawer) dragProgress else drawerProgress.value).coerceIn(0f, 1f)
            val filesProgress = (if (draggingDrawer) fileDragProgress else fileDrawerProgress.value).coerceIn(0f, 1f)
            Column(Modifier.offset(x = drawerWidth * progress - filesWidth * filesProgress).graphicsLayer {
                val exposure = maxOf(progress, filesProgress)
                scaleX = 1f - .04f * exposure
                scaleY = 1f - .04f * exposure
                rotationY = -5f * progress + 5f * filesProgress
                transformOrigin = TransformOrigin(if (filesProgress > progress) 1f else 0f, .5f)
                cameraDistance = 24f * density
                shadowElevation = 14.dp.toPx() * exposure
                shape = RoundedCornerShape(20.dp)
                clip = exposure > 0f
            }.fillMaxSize().background(YoukaiTokens.background).padding(20.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) { MessageAction("menu", "打开历史对话", { openHistoryDrawer() }, YoukaiTokens.accent) }
                    Text("H.D.D Ⅲ", color = YoukaiTokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center))
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                        MessageAction(if (balanceVoiceEnabled) "speaker" else "mute", "切换余额语音", {
                            balanceVoiceEnabled = !balanceVoiceEnabled
                            settings.agentBalanceVoiceEnabled = balanceVoiceEnabled
                            if (!balanceVoiceEnabled) stopBalanceVoice()
                        }, YoukaiTokens.accent)
                        MessageAction("folder", "电脑文件", { openFilesDrawer() }, YoukaiTokens.accent)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val hasConversation = selected.isNotBlank()
                    val eyeSize by androidx.compose.animation.core.animateDpAsState(if (hasConversation) 54.dp else 150.dp, tween(550), label = "agent eye size")
                    val eyeX by androidx.compose.animation.core.animateDpAsState(if (hasConversation) 0.dp else (maxWidth - eyeSize) / 2, tween(550), label = "agent eye x")
                    val eyeY by androidx.compose.animation.core.animateDpAsState(if (hasConversation) 28.dp else (maxHeight - eyeSize) / 2, tween(550), label = "agent eye y")
                    val agentWorking = interruptedSession != selected && (pendingPrompt.isNotBlank() || liveText.isNotBlank() || sessions.any { it.id == selected && it.running })
                    if (tokenReady) AgentHoldEye(eyeSize, Modifier.offset(eyeX, eyeY)
                        .onGloballyPositioned { coordinates ->
                            modeSwitchOrigin = coordinates.positionInRoot() + Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
                        }, agentWorking, eyeBlinkToken,
                        onSwitch = { eyeCardOpen = false; stopBalanceVoice(); if (modeSwitchTrigger == 0) modeSwitchTrigger++ },
                        onTap = {
                            if (eyeCardOpen) {
                                eyeCardOpen = false
                                balanceRequestSerial++
                                stopBalanceVoice()
                            } else {
                                eyeCardOpen = true
                                balanceLoading = true
                                accountBalance = null
                                val serial = ++balanceRequestSerial
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { DeepSeekBalance.fetch(settings.apiKey()) }
                                    if (eyeCardOpen && serial == balanceRequestSerial) {
                                        accountBalance = result
                                        balanceLoading = false
                                        if (balanceVoiceEnabled && result.currency == "CNY" && result.amount != null)
                                            playBalanceVoice(result.amount < BigDecimal.TEN)
                                    }
                                }
                            }
                        }, onDoubleTap = { eyeBlinkToken++ })
                    if (!tokenReady) {
                        Column(Modifier.align(Alignment.Center)) {
                            Text("请在隐私安全中设置电脑 Agent 访问令牌", color = YoukaiTokens.text)
                            Button(onClick = { privacyLauncher.launch(android.content.Intent(this@AgentActivity, MainActivity::class.java).putExtra("open_settings_only", true)) },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = YoukaiTokens.raisedCard,
                                    contentColor = YoukaiTokens.text)) { Text("打开设置") }
                        }
                    } else if (selected.isBlank()) {
                        Text(youkaiGreetingForHour(LocalTime.now().hour), color = YoukaiTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center).offset(y = eyeSize / 2 + 42.dp))
                    } else {
                        Text(if (agentWorking) "●  YOUKAI // WORKING" else "●  YOUKAI // ONLINE",
                            color = YoukaiTokens.accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.TopStart).offset(x = 70.dp, y = 46.dp))
                        val scroll = rememberScrollState()
                        var initialScrollPending by remember(selected) { mutableStateOf(true) }
                        LaunchedEffect(selected, loadedSession, items.size) {
                            if (selected.isNotBlank() && loadedSession == selected) {
                                // Markdown views may grow after the first Compose layout. Keep following
                                // their measured height until the initial conversation settles.
                                repeat(24) {
                                    withFrameNanos { }
                                    if (initialScrollPending) scroll.scrollTo(scroll.maxValue)
                                    delay(70)
                                }
                                initialScrollPending = false
                            }
                        }
                        LaunchedEffect(items.size, liveText) { if (!scroll.isScrollInProgress && scroll.maxValue - scroll.value < 120) scroll.scrollTo(scroll.maxValue) }
                        Column(Modifier.fillMaxSize().padding(top = 94.dp).verticalScroll(scroll)) {
                            items.forEachIndexed { index, item ->
                                if (item.kind == "user" || item.kind == "assistant") {
                                    ChatBubble(ChatMessage(item.kind, item.text), settings.displayName, settings.avatar,
                                        false, item.kind == "assistant" && interruptedSession == selected && interruptedText.isBlank() && index == items.indexOfLast { it.kind == "assistant" }, false, false, false,
                                        onCopy = { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DSH 消息", item.text)) },
                                        onEdit = {}, onRegenerate = {}, onContinue = { submit("请继续执行刚才中断的任务，从已有进度接着完成。") },
                                        onReplay = {}, assistantName = "YOUKAI", showActions = item.kind == "assistant",
                                        eyeStyle = EyeStyle.YOUKAI, allowReplay = false)
                                } else if (item.parentId.isBlank()) {
                                    val children = if (item.kind == "work") items.filter { it.parentId == item.id } else emptyList()
                                    AgentRecordCard(item, expanded[item.id], childCount = children.size,
                                        onClick = { toggleRecord(item) }, onCopy = { label, value ->
                                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
                                        })
                                    if (item.kind == "work" && expanded.containsKey(item.id)) children.forEach { child ->
                                        AgentRecordCard(child, expanded[child.id], depth = 1,
                                            onClick = { toggleRecord(child) }, onCopy = { label, value ->
                                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
                                            })
                                    }
                                }
                            }
                            if (pendingPrompt.isNotBlank() && items.lastOrNull { it.kind == "user" }?.text != pendingPrompt)
                                ChatBubble(ChatMessage("user", pendingPrompt), settings.displayName, settings.avatar, false, false, false, false, true, {}, {}, {}, {}, {}, eyeStyle = EyeStyle.YOUKAI)
                            if (liveReasoning.isNotBlank()) AgentRecordCard(
                                AgentItem("live-reasoning", "reasoning", "", "思考进行中", "", "", false),
                                if (expanded.containsKey("live-reasoning")) AgentDetail(text = liveReasoning) else null,
                                onClick = {
                                    if (expanded.containsKey("live-reasoning")) expanded.remove("live-reasoning") else expanded["live-reasoning"] = AgentDetail(text = liveReasoning)
                                }, onCopy = { _, _ -> })
                            if (pendingPrompt.isNotBlank() || liveText.isNotBlank() || liveReasoning.isNotBlank())
                                ChatBubble(ChatMessage("assistant", liveText), settings.displayName, settings.avatar, false, false, false, false, true, {}, {}, {}, {}, {}, showActions = false, assistantName = "YOUKAI", eyeStyle = EyeStyle.YOUKAI)
                            if (interruptedSession == selected && interruptedText.isNotBlank())
                                ChatBubble(ChatMessage("assistant", interruptedText), settings.displayName, settings.avatar,
                                    false, true, false, false, false, {}, {}, {},
                                    { submit("请继续执行刚才中断的任务，从已有进度接着完成。") }, {}, assistantName = "YOUKAI", eyeStyle = EyeStyle.YOUKAI, allowReplay = false)
                            if (interruptedSession == selected && interruptedText.isBlank() && items.none { it.kind == "assistant" })
                                Row(Modifier.fillMaxWidth().padding(start = 70.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("任务已终止", color = YoukaiTokens.secondary, fontSize = 11.sp)
                                    MessageAction("continue", "继续任务", { submit("请继续执行刚才中断的任务，从已有进度接着完成。") }, YoukaiTokens.accent)
                                }
                        }
                        if (loadingHistory && loadedSession != selected) Box(Modifier.align(Alignment.Center)) { AgentHistoryLoading() }
                    }
                    if (eyeCardOpen) Box(Modifier.align(Alignment.TopStart).padding(top = 96.dp)) {
                        EyeBalanceCard(accountBalance, balanceLoading, balanceClock,
                            modifier = Modifier.fillMaxWidth(), eyeStyle = EyeStyle.YOUKAI)
                    }
                }
                if (error.isNotBlank()) Text(error, color = YoukaiTokens.accent, fontSize = 12.sp)
                if (!notificationsGranted) Text("开启通知以接收后台进展与审批  ›", color = YoukaiTokens.accent,
                    fontSize = 12.sp, modifier = Modifier.clickable {
                        notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }.padding(bottom = 8.dp))
                if (tokenReady) Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected.isBlank()) {
                        AgentChoiceMenu(false, modes, chosenMode, choicesLoading) { chosenMode = it }
                        AgentChoiceMenu(true, workspaces, chosenWorkspace, choicesLoading) { chosenWorkspace = it }
                    }
                    AgentChoiceMenu(false, permissions, chosenPermission, choicesLoading) { choosePermission(it) }
                }
                if (tokenReady && voiceMode) {
                    var dragY by remember { mutableFloatStateOf(0f) }
                    Row(Modifier.fillMaxWidth().background(YoukaiTokens.surface, RoundedCornerShape(18.dp))
                        .border(1.dp, YoukaiTokens.pressedCardRim, RoundedCornerShape(18.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MessageAction("keyboard", "切换文字输入", {
                            if (voiceHolding) voiceInput.finish(true)
                            voiceHolding = false; voiceMode = false
                        }, YoukaiTokens.accent)
                        Box(Modifier.weight(1f).height(48.dp).background(YoukaiTokens.raisedCard, RoundedCornerShape(13.dp))
                            .pointerInput(voiceInput.ready) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragY = 0f; voiceCancel = false
                                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                                            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                        else if (voiceInput.start()) voiceHolding = true
                                        else voiceInput.prepare()
                                    },
                                    onDrag = { change, amount -> dragY += amount.y; voiceCancel = dragY < -72.dp.toPx(); change.consume() },
                                    onDragEnd = { if (voiceHolding) voiceInput.finish(voiceCancel); voiceHolding = false; voiceCancel = false },
                                    onDragCancel = { if (voiceHolding) voiceInput.finish(true); voiceHolding = false; voiceCancel = false }
                                )
                            }, contentAlignment = Alignment.Center) {
                            Text(if (voiceInput.ready) "按住说话" else "正在准备语音…", color = YoukaiTokens.text, fontSize = 16.sp)
                        }
                    }
                } else if (tokenReady) {
                    if (pendingUploads.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pendingUploads.forEach { upload ->
                            Text("▣ ${upload.name}  ×", color = YoukaiTokens.accent, fontSize = 11.sp,
                                modifier = Modifier.background(YoukaiTokens.raisedCard, RoundedCornerShape(8.dp))
                                    .clickable { pendingUploads.remove(upload) }.padding(6.dp), maxLines = 1)
                        }
                    }
                    val composerShape = RoundedCornerShape(18.dp)
                    Row(Modifier.fillMaxWidth().shadow(if (draft.isNotBlank()) 9.dp else 4.dp, composerShape)
                        .background(YoukaiTokens.surface, composerShape)
                        .border(1.dp, if (draft.isNotBlank()) YoukaiTokens.accent.copy(alpha = .7f) else YoukaiTokens.pressedCardRim, composerShape)
                        .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MessageAction("mic", "切换语音输入", {
                            voiceMode = true
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceInput.prepare()
                            else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                        }, YoukaiTokens.accent)
                        MessageAction("attach", "上传附件", {
                            if (chosenWorkspace.isBlank()) error = "请先选择电脑工作区"
                            else { attachOnUpload = true; openDocument.launch(arrayOf("*/*")) }
                        }, YoukaiTokens.accent)
                        TextField(draft, { draft = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("向 YOUKAI 提问…", color = YoukaiTokens.secondary) },
                            colors = TextFieldDefaults.colors(focusedTextColor = YoukaiTokens.text, unfocusedTextColor = YoukaiTokens.text,
                                cursorColor = YoukaiTokens.accent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                        val canStop = selected.isNotBlank() && interruptedSession != selected && (pendingPrompt.isNotBlank() || sessions.any { it.id == selected && it.running }) && draft.isBlank() && pendingUploads.isEmpty()
                        MessageAction(if (canStop) "stop" else "send", if (canStop) "终止生成" else "发送消息", {
                            if (canStop) cancelTask() else submit(draft)
                        }, if (canStop || (!busy && (draft.isNotBlank() || pendingUploads.isNotEmpty()))) YoukaiTokens.accent else YoukaiTokens.secondary.copy(alpha = .6f))
                    }
                }
            }
            if (progress > .001f) Box(Modifier.align(Alignment.CenterEnd).width(maxWidth - drawerWidth * progress).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, YoukaiTokens.drawerVeil.copy(alpha = .32f * progress))))
                .clickable(onClickLabel = "收起历史对话") { listOpen = false; settleVersion++ })
            if (progress > .001f || listOpen) {
                val drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
                Column(Modifier.offset(x = -drawerWidth * (1f - progress)).fillMaxHeight().width(drawerWidth)
                    .shadow(24.dp, drawerShape).background(YoukaiTokens.surface, drawerShape)
                    .border(1.dp, YoukaiTokens.pressedCardRim, drawerShape).padding(horizontal = 16.dp, vertical = 24.dp)) {
                    Text("历史对话", color = YoukaiTokens.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("＋  新任务", color = YoukaiTokens.accent, modifier = Modifier.fillMaxWidth().clickable {
                        newTask()
                    }.padding(vertical = 22.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        sessions.forEach { session ->
                            HistorySessionSurface(session.id == selected, eyeStyle = EyeStyle.YOUKAI) {
                                Text(session.title, color = if (session.id == selected) YoukaiTokens.accent else YoukaiTokens.text,
                                    maxLines = 2, modifier = Modifier.fillMaxWidth().clickable {
                                        stopBalanceVoice(); eyeCardOpen = false; balanceRequestSerial++
                                        AgentMonitorService.stop(this@AgentActivity)
                                        items = emptyList(); loadedSession = ""; expanded.clear(); selected = session.id; listOpen = false; pendingPrompt = ""; liveText = ""; liveReasoning = ""
                                        if (session.running) AgentMonitorService.monitor(this@AgentActivity, session.id)
                                    }.padding(vertical = 8.dp))
                            }
                        }
                    }
                    Text("设置  ›", color = YoukaiTokens.text, modifier = Modifier.fillMaxWidth().clickable {
                        settingsOpenedFromDrawer = true
                        privacyLauncher.launch(android.content.Intent(this@AgentActivity, MainActivity::class.java).putExtra("open_settings_only", true))
                    }.padding(vertical = 16.dp))
                }
            }
            if (filesProgress > .001f) {
                Box(Modifier.align(Alignment.CenterStart).width(maxWidth - filesWidth * filesProgress).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(YoukaiTokens.drawerVeil.copy(alpha = .32f * filesProgress), Color.Transparent)))
                    .clickable(onClickLabel = "收起电脑文件") { fileDrawerOpen = false; folder = null; settleVersion++ })
                Column(Modifier.align(Alignment.CenterEnd).offset(x = filesWidth * (1f - filesProgress))
                    .width(filesWidth).fillMaxHeight().shadow(22.dp, RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .background(YoukaiTokens.surface, RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .border(1.dp, YoukaiTokens.pressedCardRim, RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("电脑文件", color = YoukaiTokens.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        MessageAction("plus", "上传到当前文件夹", {
                            if (browseComputer && folder?.path.isNullOrBlank()) error = "请先打开一个电脑文件夹"
                            else { attachOnUpload = false; openDocument.launch(arrayOf("*/*")) }
                        }, YoukaiTokens.accent)
                        MessageAction("cancel", "关闭电脑文件", { fileDrawerOpen = false; folder = null }, YoukaiTokens.secondary)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "工作区", true to "此电脑").forEach { (computer, title) ->
                            Text(title, color = if (browseComputer == computer) YoukaiTokens.text else YoukaiTokens.secondary,
                                fontSize = 12.sp, modifier = Modifier.background(
                                    if (browseComputer == computer) YoukaiTokens.pressedCard else YoukaiTokens.raisedCard,
                                    RoundedCornerShape(9.dp)).clickable {
                                        browseComputer = computer
                                        loadFolder(if (computer) "" else chosenWorkspace, "")
                                    }.padding(horizontal = 13.dp, vertical = 8.dp))
                        }
                    }
                    if (!browseComputer) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        workspaces.forEach { workspace ->
                            Text(workspace.title, color = if (workspace.id == chosenWorkspace) YoukaiTokens.text else YoukaiTokens.secondary,
                                fontSize = 11.sp, maxLines = 1,
                                modifier = Modifier.background(if (workspace.id == chosenWorkspace) YoukaiTokens.pressedCard else YoukaiTokens.raisedCard,
                                    RoundedCornerShape(9.dp)).clickable { chosenWorkspace = workspace.id; loadFolder(workspace.id, "") }.padding(8.dp))
                        }
                    }
                    if (browseComputer) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(computerPathDraft, { computerPathDraft = it }, modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("电脑文件夹绝对路径", color = YoukaiTokens.secondary, fontSize = 11.sp) },
                            colors = TextFieldDefaults.colors(focusedTextColor = YoukaiTokens.text, unfocusedTextColor = YoukaiTokens.text,
                                cursorColor = YoukaiTokens.accent, focusedContainerColor = YoukaiTokens.raisedCard,
                                unfocusedContainerColor = YoukaiTokens.raisedCard, focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent))
                        MessageAction("send", "打开电脑文件夹", { loadFolder("", computerPathDraft.trim()) }, YoukaiTokens.accent)
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("‹", color = if (folder?.parent != null) YoukaiTokens.accent else YoukaiTokens.secondary, fontSize = 26.sp,
                            modifier = Modifier.clickable { folder?.parent?.let { loadFolder(if (browseComputer) "" else chosenWorkspace, it) } }.padding(end = 12.dp))
                        Text(if (folder?.path.isNullOrBlank()) if (browseComputer) "此电脑" else "工作区根目录" else folder!!.path, color = YoukaiTokens.secondary,
                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (folderLoading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = YoukaiTokens.accent)
                    if (folderError.isNotBlank()) Text(folderError, color = YoukaiTokens.accent, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { loadFolder(if (browseComputer) "" else chosenWorkspace, if (browseComputer) computerPathDraft else "") }
                            .padding(vertical = 12.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(folder?.items.orEmpty(), key = { it.name }) { entry ->
                            val path = if (browseComputer && folder?.path.isNullOrBlank()) entry.name
                                else if (browseComputer) "${folder!!.path.trimEnd('\\', '/')}\\${entry.name}"
                                else listOfNotNull(folder?.path?.takeIf { it.isNotBlank() }, entry.name).joinToString("/")
                            val source = if (browseComputer) "" else chosenWorkspace
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .background(YoukaiTokens.raisedCard, RoundedCornerShape(11.dp))
                                .clickable { if (entry.directory) loadFolder(source, path) else previewFile(source, path, entry.name) }
                                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (entry.directory) "▣" else "▤", color = YoukaiTokens.accent, fontSize = 15.sp)
                                Text(entry.name, color = YoukaiTokens.text, fontSize = 12.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                if (!entry.directory) Text("↓", color = YoukaiTokens.accent, fontSize = 18.sp,
                                    modifier = Modifier.clickable {
                                        pendingSave = source to path
                                        saveDocument.launch(entry.name)
                                    }.padding(horizontal = 10.dp))
                            }
                        }
                    }
                }
            }
            if (pendingApproval != null && progress < .01f && filesProgress < .01f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)).clickable(enabled = false) {})
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .shadow(22.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(YoukaiTokens.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .border(1.dp, YoukaiTokens.accent.copy(alpha = .38f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("等待审批", color = YoukaiTokens.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(pendingApproval!!.reason.ifBlank { "工具 ${pendingApproval!!.toolName} 请求越权执行" },
                        color = YoukaiTokens.text, fontSize = 16.sp)
                    Text(pendingApproval!!.toolName, color = YoukaiTokens.secondary, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("拒绝", color = YoukaiTokens.text, fontSize = 14.sp,
                            modifier = Modifier.weight(1f).background(YoukaiTokens.raisedCard, RoundedCornerShape(12.dp))
                                .clickable(enabled = !approvalBusy) { decideApproval(false) }.padding(14.dp))
                        Text("允许一次", color = YoukaiTokens.text, fontSize = 14.sp,
                            modifier = Modifier.weight(1f).background(YoukaiTokens.accent.copy(alpha = .36f), RoundedCornerShape(12.dp))
                                .clickable(enabled = !approvalBusy) { decideApproval(true) }.padding(14.dp))
                    }
                }
            }
            if (voiceHolding && progress < .01f && filesProgress < .01f && pendingApproval == null) {
                val glow by animateColorAsState(if (voiceCancel) YoukaiTokens.voiceCancelGlow else YoukaiTokens.voiceGlow, tween(180), label = "agent voice glow")
                val level by animateFloatAsState(voiceLevel, tween(100), label = "agent voice level")
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(330.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, .24f to YoukaiTokens.voiceShade.copy(alpha = .94f), .56f to glow.copy(alpha = .97f), 1f to glow))) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (voiceCancel) "松手取消" else "松手发送 · 上滑取消", color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(56.dp))
                        Canvas(Modifier.fillMaxWidth(.72f).height(32.dp)) {
                            val count = 42
                            val gap = size.width / (count - 1)
                            for (index in 0 until count) {
                                val profile = .35f + .65f * kotlin.math.abs(kotlin.math.sin(index * .57f))
                                val height = size.height * (.15f + level * .75f * profile)
                                val x = index * gap
                                drawLine(if (voiceCancel) Color(0xFFFF8995) else YoukaiTokens.text.copy(alpha = .9f),
                                    Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), 2.dp.toPx(), cap = StrokeCap.Round)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
