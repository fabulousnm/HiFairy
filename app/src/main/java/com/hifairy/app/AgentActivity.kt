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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
private data class AgentItem(val id: String, val kind: String, val text: String, val title: String, val preview: String, val detailKey: String, val hasResult: Boolean)

@Composable
private fun AgentRecordCard(item: AgentItem, expandedText: String?, onClick: () -> Unit) {
    val tint = when (item.kind) {
        "tool" -> Color(0xFF83B9CA)
        "terminal" -> Color(0xFF83B9CA)
        "work" -> Color(0xFFB7A3DF)
        "commentary" -> Color(0xFFB7A3DF)
        else -> ThemeTokens.accent
    }
    Column(Modifier.fillMaxWidth().padding(start = 44.dp, bottom = 3.dp), horizontalAlignment = Alignment.Start) {
        Row(Modifier.widthIn(max = 300.dp).background(ThemeTokens.reasoningBubble, RoundedCornerShape(8.dp))
            .border(1.dp, tint.copy(alpha = .24f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((when (item.kind) { "tool" -> "⚙"; "terminal" -> "⌁"; "work" -> "◇"; else -> "◌" }) + "  " + item.title,
                color = tint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(if (expandedText == null) "  ›" else "  ⌄", color = tint, fontSize = 11.sp)
        }
        if (expandedText != null) Text(expandedText, color = ThemeTokens.text, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = Modifier.widthIn(max = 300.dp).heightIn(max = 260.dp).padding(top = 4.dp)
                .background(ThemeTokens.surface, RoundedCornerShape(9.dp))
                .border(1.dp, tint.copy(alpha = .25f), RoundedCornerShape(9.dp))
                .verticalScroll(rememberScrollState()).padding(9.dp))
    }
}

@Composable
private fun AgentHistoryLoading() {
    Column(Modifier.background(Brush.verticalGradient(listOf(ThemeTokens.surface, ThemeTokens.reasoningBubble)), RoundedCornerShape(24.dp))
        .border(1.dp, ThemeTokens.accent.copy(alpha = .22f), RoundedCornerShape(24.dp))
        .padding(horizontal = 30.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            FairyEye(Modifier.size(54.dp))
            androidx.compose.material3.CircularProgressIndicator(Modifier.fillMaxSize(), color = ThemeTokens.accent,
                strokeWidth = 2.dp, trackColor = ThemeTokens.accent.copy(alpha = .12f))
        }
        Spacer(Modifier.height(16.dp))
        Text("正在接入会话", color = ThemeTokens.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("读取最近 15 轮问答", color = ThemeTokens.secondary, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        androidx.compose.material3.LinearProgressIndicator(Modifier.width(150.dp).height(3.dp), color = ThemeTokens.accent,
            trackColor = ThemeTokens.accent.copy(alpha = .13f))
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
        }, active = active)
        if (progress.value > .001f) Canvas(Modifier.matchParentSize()) {
            drawArc(ThemeTokens.accent, -90f, 360f * progress.value, false,
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

    fun create(): String = request("/sessions", "POST", "{}").getString("sessionId")
    fun send(id: String, text: String) { request("/sessions/$id/messages", "POST", JSONObject().put("text", text).toString()) }

    fun recent(id: String): List<AgentItem> {
        val items = request("/sessions/$id/recent").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            AgentItem(it.optString("id"), it.optString("kind"), it.optString("text"),
                it.optString("title"), it.optString("preview"), it.optString("detailKey"), it.optBoolean("hasResult"))
        }
    }

    fun detail(id: String, item: AgentItem): String {
        val json = request("/sessions/$id/details/${item.kind}/${item.detailKey}")
        return if (item.kind == "tool") "输入\n${json.optString("arguments")}\n\n输出\n${json.optString("result")}" else json.optString("text")
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
            var tokenDraft by remember { mutableStateOf("") }
            var tokenReady by remember { mutableStateOf(settings.agentToken().isNotBlank()) }
            var sessions by remember { mutableStateOf(emptyList<AgentSession>()) }
            var selected by remember { mutableStateOf("") }
            var items by remember { mutableStateOf(emptyList<AgentItem>()) }
            var loadingHistory by remember { mutableStateOf(false) }
            var loadedSession by remember { mutableStateOf("") }
            val expanded = remember { mutableStateMapOf<String, String>() }
            var draft by remember { mutableStateOf("") }
            var error by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var listOpen by remember { mutableStateOf(false) }
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
            }

            suspend fun refreshList() {
                try {
                    sessions = withContext(Dispatchers.IO) { api.sessions() }
                    error = ""
                } catch (e: Exception) { error = e.message ?: "连接失败" }
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

            fun submit(text: String) {
                if (busy || text.isBlank()) return
                busy = true
                scope.launch {
                    try {
                        if (selected.isBlank()) {
                            streamConnected = false
                            selected = withContext(Dispatchers.IO) { api.create() }
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
                    } catch (e: Exception) { error = e.message ?: "实时连接中断" }
                    delay(1500)
                    refreshList()
                }
            }
            BackHandler(listOpen) { listOpen = false }
            LaunchedEffect(listOpen, settleVersion) {
                val target = if (listOpen) 1f else 0f
                drawerProgress.animateTo(target, DrawerMotion.settle)
                drawerProgress.snapTo(target)
            }

            BoxWithConstraints(Modifier.fillMaxSize().background(ThemeTokens.background).draggable(
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
            }.fillMaxSize().background(ThemeTokens.background).padding(20.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) { MessageAction("menu", "打开历史对话", { listOpen = true }, ThemeTokens.accent) }
                    Text("H.D.D Ⅲ · AGENT", color = ThemeTokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center))
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                        MessageAction(if (voiceAutoPlay) "speaker" else "mute", "切换自动播放", {
                            voiceAutoPlay = !voiceAutoPlay
                            settings.voiceAutoPlay = voiceAutoPlay
                            if (!voiceAutoPlay) speechPlayer.stop()
                        }, ThemeTokens.accent)
                        MessageAction("plus", "新任务", {
                            newTask()
                        }, ThemeTokens.accent)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (!tokenReady) {
                        Column(Modifier.align(Alignment.Center)) {
                            Text("输入电脑启动脚本显示的 Agent 令牌", color = ThemeTokens.text)
                            TextField(tokenDraft, { tokenDraft = it }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                            Button(onClick = { if (tokenDraft.isNotBlank()) { settings.saveAgentToken(tokenDraft.trim()); tokenDraft = ""; tokenReady = true } }) { Text("连接电脑 Agent") }
                        }
                    } else if (selected.isBlank()) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            AgentHoldEye(150.dp, onSwitch = { finish() })
                            Text(greetingForHour(LocalTime.now().hour), color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 35.dp))
                        }
                    } else {
                        AgentHoldEye(54.dp, Modifier.align(Alignment.TopStart).offset(y = 28.dp), pendingPrompt.isNotBlank()) { finish() }
                        FairyOnlineStatus(pendingPrompt.isNotBlank(), Modifier.align(Alignment.TopStart).offset(x = 70.dp, y = 46.dp))
                        val scroll = rememberScrollState()
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
                                        }, assistantName = "DSH", showActions = item.kind == "assistant")
                                } else {
                                    AgentRecordCard(item, expanded[item.id], onClick = {
                                        if (expanded.containsKey(item.id)) expanded.remove(item.id)
                                        else if (item.kind == "work") expanded[item.id] = item.text
                                        else scope.launch {
                                            val requestedSession = selected
                                            expanded[item.id] = "正在读取…"
                                            try {
                                                val detail = withContext(Dispatchers.IO) { api.detail(requestedSession, item) }
                                                if (selected == requestedSession) expanded[item.id] = detail
                                            } catch (e: Exception) { if (selected == requestedSession) expanded[item.id] = e.message ?: "读取失败" }
                                        }
                                    })
                                }
                            }
                            if (pendingPrompt.isNotBlank() && items.lastOrNull { it.kind == "user" }?.text != pendingPrompt)
                                ChatBubble(ChatMessage("user", pendingPrompt), settings.displayName, settings.avatar, false, false, false, false, true, {}, {}, {}, {}, {})
                            if (liveReasoning.isNotBlank()) AgentRecordCard(
                                AgentItem("live-reasoning", "reasoning", "", "思考进行中", liveReasoning.take(220), "", false),
                                expanded["live-reasoning"], onClick = {
                                    if (expanded.containsKey("live-reasoning")) expanded.remove("live-reasoning") else expanded["live-reasoning"] = liveReasoning
                                })
                            if (pendingPrompt.isNotBlank() || liveText.isNotBlank() || liveReasoning.isNotBlank())
                                ChatBubble(ChatMessage("assistant", liveText), settings.displayName, settings.avatar, false, false, false, false, true, {}, {}, {}, {}, {}, showActions = false, assistantName = "DSH")
                        }
                        if (loadingHistory && loadedSession != selected) Box(Modifier.align(Alignment.Center)) { AgentHistoryLoading() }
                    }
                }
                if (error.isNotBlank()) Text(error, color = ThemeTokens.accent, fontSize = 12.sp)
                if (tokenReady && voiceMode) {
                    var dragY by remember { mutableFloatStateOf(0f) }
                    Row(Modifier.fillMaxWidth().background(ThemeTokens.surface, RoundedCornerShape(18.dp))
                        .border(1.dp, ThemeTokens.pressedCardRim, RoundedCornerShape(18.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MessageAction("keyboard", "切换文字输入", {
                            if (voiceHolding) voiceInput.finish(true)
                            voiceHolding = false; voiceMode = false
                        }, ThemeTokens.accent)
                        Box(Modifier.weight(1f).height(48.dp).background(ThemeTokens.raisedCard, RoundedCornerShape(13.dp))
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
                            Text(if (voiceInput.ready) "按住说话" else "正在准备语音…", color = ThemeTokens.text, fontSize = 16.sp)
                        }
                    }
                } else if (tokenReady) {
                    val composerShape = RoundedCornerShape(18.dp)
                    Row(Modifier.fillMaxWidth().shadow(if (draft.isNotBlank()) 9.dp else 4.dp, composerShape)
                        .background(ThemeTokens.surface, composerShape)
                        .border(1.dp, if (draft.isNotBlank()) Color(0xFF5775B5) else Color(0xFF293653), composerShape)
                        .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MessageAction("mic", "切换语音输入", {
                            voiceMode = true
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceInput.prepare()
                            else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                        }, ThemeTokens.accent)
                        TextField(draft, { draft = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("向 Fairy 提问…", color = ThemeTokens.secondary) },
                            colors = TextFieldDefaults.colors(focusedTextColor = ThemeTokens.text, unfocusedTextColor = ThemeTokens.text,
                                cursorColor = ThemeTokens.accent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                        MessageAction("send", "发送消息", {
                            submit(draft)
                        }, if (busy || draft.isBlank()) ThemeTokens.secondary.copy(alpha = .6f) else ThemeTokens.accent)
                    }
                }
            }
            if (progress > .001f) Box(Modifier.align(Alignment.CenterEnd).width(maxWidth - drawerWidth * progress).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, ThemeTokens.drawerVeil.copy(alpha = .32f * progress))))
                .clickable(onClickLabel = "收起历史对话") { listOpen = false; settleVersion++ })
            if (progress > .001f || listOpen) {
                val drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
                Column(Modifier.offset(x = -drawerWidth * (1f - progress)).fillMaxHeight().width(drawerWidth)
                    .shadow(24.dp, drawerShape).background(ThemeTokens.surface, drawerShape)
                    .border(1.dp, Color(0xFF384878), drawerShape).padding(horizontal = 16.dp, vertical = 24.dp)) {
                    Text("历史对话", color = ThemeTokens.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("＋  新任务", color = ThemeTokens.accent, modifier = Modifier.fillMaxWidth().clickable {
                        newTask()
                    }.padding(vertical = 22.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        sessions.forEach { session ->
                            HistorySessionSurface(session.id == selected) {
                                Text(session.title, color = if (session.id == selected) ThemeTokens.accent else ThemeTokens.text,
                                    maxLines = 2, modifier = Modifier.fillMaxWidth().clickable {
                                        speechPlayer.stop(); items = emptyList(); loadedSession = ""; expanded.clear(); selected = session.id; listOpen = false; pendingPrompt = ""; liveText = ""; liveReasoning = ""
                                    }.padding(vertical = 8.dp))
                            }
                        }
                    }
                    Text("连接设置  ›", color = ThemeTokens.text, modifier = Modifier.fillMaxWidth().clickable {
                        listOpen = false; tokenReady = false
                    }.padding(vertical = 16.dp))
                }
            }
            if (voiceHolding && progress < .01f) {
                val glow by animateColorAsState(if (voiceCancel) ThemeTokens.voiceCancelGlow else ThemeTokens.voiceGlow, tween(180), label = "agent voice glow")
                val level by animateFloatAsState(voiceLevel, tween(100), label = "agent voice level")
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(330.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, .24f to ThemeTokens.voiceShade.copy(alpha = .94f), .56f to glow.copy(alpha = .97f), 1f to glow))) {
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
                                drawLine(if (voiceCancel) Color(0xFFFF8995) else ThemeTokens.text.copy(alpha = .9f),
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
