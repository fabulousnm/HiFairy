package com.hifairy.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
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
import java.time.LocalTime
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private data class AgentSession(val id: String, val title: String)
private data class AgentItem(val id: String, val kind: String, val text: String, val title: String, val detailKey: String, val parentId: String, val hasResult: Boolean)
private data class AgentDetail(val text: String = "", val input: String = "", val output: String = "", val terminal: Boolean = false)
private data class AgentChoice(val id: String, val title: String, val isDefault: Boolean = false)

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
private fun AgentHoldEye(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, active: Boolean = false, onSwitch: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(modifier.size(size)) {
        FairyEye(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onPress = {
                progress.snapTo(0f)
                val animation = scope.launch { progress.animateTo(1f, tween(AGENT_MODE_HOLD_MS.toInt())) }
                val released = withTimeoutOrNull(AGENT_MODE_HOLD_MS) { tryAwaitRelease() }
                animation.cancel()
                if (released == null) onSwitch()
                progress.animateTo(0f, tween(180))
            })
        }, active = active, style = EyeStyle.YOUKAI)
        if (progress.value > .001f) Canvas(Modifier.matchParentSize()) {
            drawArc(YoukaiTokens.accent, -90f, 360f * progress.value, false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private class AgentConnection(private val settings: SecureSettings) {
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
    fun create(workspaceId: String, agentPreset: String): String = request("/sessions", "POST",
        JSONObject().put("workspaceId", workspaceId).put("agentPreset", agentPreset).toString()).getString("sessionId")
    fun send(id: String, text: String) { request("/sessions/$id/messages", "POST", JSONObject().put("text", text).toString()) }

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
                try { onFrame(JSONObject(text)) } catch (error: Exception) {
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
    private var activeSpeech: FairySpeechPlayer? = null
    private var activeVoiceInput: ParaformerVoiceInput? = null

    override fun onStop() {
        activeSpeech?.stop()
        activeVoiceInput?.let { if (it.recording) it.finish(true) }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        val api = AgentConnection(settings)
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
            var selected by remember { mutableStateOf("") }
            var items by remember { mutableStateOf(emptyList<AgentItem>()) }
            var loadingHistory by remember { mutableStateOf(false) }
            var loadedSession by remember { mutableStateOf("") }
            val expanded = remember { mutableStateMapOf<String, AgentDetail>() }
            var draft by remember { mutableStateOf("") }
            var error by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var modes by remember { mutableStateOf(emptyList<AgentChoice>()) }
            var workspaces by remember { mutableStateOf(emptyList<AgentChoice>()) }
            var chosenMode by remember { mutableStateOf("") }
            var chosenWorkspace by remember { mutableStateOf("") }
            var choicesLoading by remember { mutableStateOf(false) }
            var choicesRefreshVersion by remember { mutableIntStateOf(0) }
            val drawerWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() * .75f }
            val drawerProgress = remember { Animatable(0f) }
            var dragProgress by remember { mutableFloatStateOf(0f) }
            var draggingDrawer by remember { mutableStateOf(false) }
            var dragStartedOpen by remember { mutableStateOf(false) }
            var settleVersion by remember { mutableIntStateOf(0) }
            var pendingPrompt by remember { mutableStateOf("") }
            var streamConnected by remember { mutableStateOf(false) }
            var voiceMode by remember { mutableStateOf(false) }
            var voiceHolding by remember { mutableStateOf(false) }
            var voiceCancel by remember { mutableStateOf(false) }
            var voiceStatus by remember { mutableStateOf("") }
            var voiceLevel by remember { mutableFloatStateOf(0f) }
            var playingIndex by remember { mutableIntStateOf(-1) }
            var voiceAutoPlay by remember { mutableStateOf(settings.voiceAutoPlay) }
            val speechPlayer = remember { FairySpeechPlayer { _, active, _ -> if (!active) runOnUiThread { playingIndex = -1 } } }
            activeSpeech = speechPlayer
            DisposableEffect(Unit) { onDispose { speechPlayer.close() } }
            var liveText by remember { mutableStateOf("") }
            var liveReasoning by remember { mutableStateOf("") }
            val streamReducer = remember(selected) { AgentStreamReducer() }
            DisposableEffect(selected) { onDispose { api.closeFollow() } }
            DisposableEffect(Unit) { onDispose { api.close() } }

            fun newTask() {
                speechPlayer.stop()
                selected = ""
                items = emptyList()
                loadedSession = ""
                expanded.clear()
                pendingPrompt = ""
                liveText = ""
                liveReasoning = ""
                streamConnected = false
                listOpen = false
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
                    modes = freshModes
                    workspaces = freshWorkspaces
                    if (freshModes.none { it.id == chosenMode }) chosenMode = freshModes.firstOrNull { it.isDefault }?.id ?: freshModes.firstOrNull()?.id.orEmpty()
                    if (freshWorkspaces.none { it.id == chosenWorkspace }) chosenWorkspace = freshWorkspaces.firstOrNull()?.id.orEmpty()
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
                if (busy || text.isBlank()) return
                busy = true
                scope.launch {
                    try {
                        if (selected.isBlank()) {
                            streamConnected = false
                            if (chosenMode.isBlank() || chosenWorkspace.isBlank()) throw IllegalStateException("请先选择电脑模式和工作区")
                            selected = withContext(Dispatchers.IO) { api.create(chosenWorkspace, chosenMode) }
                            items = emptyList()
                        }
                        withTimeout(10000L) { while (!streamConnected) delay(50) }
                        pendingPrompt = text.trim()
                        withContext(Dispatchers.IO) { api.send(selected, text.trim()) }
                        draft = ""
                        refreshSelected()
                        refreshList()
                    } catch (e: Exception) {
                        pendingPrompt = ""
                        error = e.message ?: "发送失败"
                    } finally { busy = false }
                }
            }

            val voiceInput = remember { ParaformerVoiceInput(this@AgentActivity, { voiceStatus = it }, { voiceLevel = it }, { transcript -> submit(transcript) }) }
            activeVoiceInput = voiceInput
            DisposableEffect(Unit) { onDispose { voiceInput.close() } }
            val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) voiceInput.prepare() else voiceStatus = "需要麦克风权限"
            }

            LaunchedEffect(tokenReady) { if (tokenReady) refreshList() }
            LaunchedEffect(tokenReady, selected, choicesRefreshVersion) {
                if (!tokenReady || selected.isNotBlank()) return@LaunchedEffect
                refreshChoices()
            }
            LaunchedEffect(selected, tokenReady) { if (tokenReady && selected.isNotBlank()) refreshSelected() }
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
                                if (eventType in setOf("tool/call", "tool/result", "step/start", "step/end")) refreshSelected()
                                if (frame.optString("type") == "event" && eventType == "assistant/message") {
                                    refreshSelected()
                                    liveText = ""
                                    liveReasoning = ""
                                }
                                if (frame.optString("type") == "event" && eventType == "turn/end") {
                                    refreshSelected()
                                    refreshList()
                                    val latest = items.lastOrNull { it.kind == "assistant" }?.text.orEmpty()
                                    if (voiceAutoPlay && latest.isNotBlank()) {
                                        val index = items.indexOfLast { it.kind == "assistant" }
                                        if (speechPlayer.play(settings.voiceEndpoint, settings.voiceToken(), latest, index.toLong())) playingIndex = index
                                    }
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
            BackHandler(listOpen) { listOpen = false }
            BackHandler(!listOpen) { if (modeSwitchTrigger == 0) modeSwitchTrigger++ }
            LaunchedEffect(listOpen, settleVersion) {
                val target = if (listOpen) 1f else 0f
                drawerProgress.animateTo(target, DrawerMotion.settle)
                drawerProgress.snapTo(target)
            }

            BoxWithConstraints(Modifier.fillMaxSize()
                .modeCircularReveal(reveal.value, if (modeSwitchTrigger > 0) modeSwitchOrigin else entryOrigin, YoukaiTokens.accent)
                .background(YoukaiTokens.background).draggable(
                state = rememberDraggableState { delta -> dragProgress = (dragProgress + delta / drawerWidthPx).coerceIn(0f, 1f) },
                orientation = Orientation.Horizontal,
                onDragStarted = { drawerProgress.stop(); dragProgress = drawerProgress.value.coerceIn(0f, 1f); dragStartedOpen = dragProgress >= .5f; draggingDrawer = true },
                onDragStopped = { velocity ->
                    val open = when {
                        velocity > DrawerMotion.flingVelocity -> true
                        velocity < -DrawerMotion.flingVelocity -> false
                        dragStartedOpen -> dragProgress >= DrawerMotion.closeThreshold
                        else -> dragProgress >= DrawerMotion.openThreshold
                    }
                    drawerProgress.snapTo(dragProgress)
                    draggingDrawer = false
                    listOpen = open
                    settleVersion++
                }
            )) {
            val drawerWidth = maxWidth * .75f
            val progress = (if (draggingDrawer) dragProgress else drawerProgress.value).coerceIn(0f, 1f)
            Column(Modifier.offset(x = drawerWidth * progress).graphicsLayer {
                scaleX = 1f - .04f * progress
                scaleY = 1f - .04f * progress
                rotationY = -5f * progress
                transformOrigin = TransformOrigin(0f, .5f)
                cameraDistance = 24f * density
                shadowElevation = 14.dp.toPx() * progress
                shape = RoundedCornerShape(20.dp)
                clip = progress > 0f
            }.fillMaxSize().background(YoukaiTokens.background).padding(20.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) { MessageAction("menu", "打开历史对话", { listOpen = true }, YoukaiTokens.accent) }
                    Text("H.D.D Ⅲ · AGENT", color = YoukaiTokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center))
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                        MessageAction(if (voiceAutoPlay) "speaker" else "mute", "切换自动播放", {
                            voiceAutoPlay = !voiceAutoPlay
                            settings.voiceAutoPlay = voiceAutoPlay
                            if (!voiceAutoPlay) speechPlayer.stop()
                        }, YoukaiTokens.accent)
                        MessageAction("plus", "新任务", {
                            newTask()
                        }, YoukaiTokens.accent)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val hasConversation = selected.isNotBlank()
                    val eyeSize by androidx.compose.animation.core.animateDpAsState(if (hasConversation) 54.dp else 150.dp, tween(550), label = "agent eye size")
                    val eyeX by androidx.compose.animation.core.animateDpAsState(if (hasConversation) 0.dp else (maxWidth - eyeSize) / 2, tween(550), label = "agent eye x")
                    val eyeY by androidx.compose.animation.core.animateDpAsState(if (hasConversation) 28.dp else (maxHeight - eyeSize) / 2, tween(550), label = "agent eye y")
                    if (tokenReady) AgentHoldEye(eyeSize, Modifier.offset(eyeX, eyeY)
                        .onGloballyPositioned { coordinates ->
                            modeSwitchOrigin = coordinates.positionInRoot() + Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
                        }, pendingPrompt.isNotBlank()) { if (modeSwitchTrigger == 0) modeSwitchTrigger++ }
                    if (!tokenReady) {
                        Column(Modifier.align(Alignment.Center)) {
                            Text("请在隐私安全中设置电脑 Agent 访问令牌", color = YoukaiTokens.text)
                            Button(onClick = { privacyLauncher.launch(android.content.Intent(this@AgentActivity, MainActivity::class.java).putExtra("open_settings_only", true)) },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = YoukaiTokens.raisedCard,
                                    contentColor = YoukaiTokens.text)) { Text("打开设置") }
                        }
                    } else if (selected.isBlank()) {
                        Text(greetingForHour(LocalTime.now().hour), color = YoukaiTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center).offset(y = eyeSize / 2 + 42.dp))
                    } else {
                        Text(if (pendingPrompt.isNotBlank()) "●  YOUKAI // WORKING" else "●  YOUKAI // ONLINE",
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
                                        false, false, false, playingIndex == index, false,
                                        onCopy = { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DSH 消息", item.text)) },
                                        onEdit = {}, onRegenerate = {}, onContinue = {},
                                        onReplay = {
                                            if (playingIndex == index) { speechPlayer.stop(); playingIndex = -1 }
                                            else if (speechPlayer.play(settings.voiceEndpoint, settings.voiceToken(), item.text, index.toLong())) playingIndex = index
                                        }, assistantName = "YOUKAI", showActions = item.kind == "assistant", eyeStyle = EyeStyle.YOUKAI)
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
                        }
                        if (loadingHistory && loadedSession != selected) Box(Modifier.align(Alignment.Center)) { AgentHistoryLoading() }
                    }
                }
                if (error.isNotBlank()) Text(error, color = YoukaiTokens.accent, fontSize = 12.sp)
                if (tokenReady && selected.isBlank()) Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentChoiceMenu(false, modes, chosenMode, choicesLoading) { chosenMode = it }
                    AgentChoiceMenu(true, workspaces, chosenWorkspace, choicesLoading) { chosenWorkspace = it }
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
                        TextField(draft, { draft = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("向 Fairy 提问…", color = YoukaiTokens.secondary) },
                            colors = TextFieldDefaults.colors(focusedTextColor = YoukaiTokens.text, unfocusedTextColor = YoukaiTokens.text,
                                cursorColor = YoukaiTokens.accent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                        MessageAction("send", "发送消息", {
                            submit(draft)
                        }, if (busy || draft.isBlank()) YoukaiTokens.secondary.copy(alpha = .6f) else YoukaiTokens.accent)
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
                                        speechPlayer.stop(); items = emptyList(); loadedSession = ""; expanded.clear(); selected = session.id; listOpen = false; pendingPrompt = ""; liveText = ""; liveReasoning = ""
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
            if (voiceHolding && progress < .01f) {
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
