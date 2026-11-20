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
private data class AgentTurn(val prompt: String, val response: String)

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

    fun turns(id: String): List<AgentTurn> {
        val json = request("/sessions/$id/snapshot")
        val outline = json.optJSONObject("projections")?.optJSONObject("values")?.optJSONArray("turnOutline") ?: return emptyList()
        return (0 until outline.length()).map { outline.getJSONObject(it) }.map {
            AgentTurn(it.optString("prompt"), it.optString("response"))
        }
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
            var turns by remember { mutableStateOf(emptyList<AgentTurn>()) }
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
                turns = emptyList()
                pendingPrompt = ""
                liveText = ""
                liveReasoning = ""
                streamConnected = false
                listOpen = false
            }

            suspend fun refresh() {
                try {
                    val result = withContext(Dispatchers.IO) { api.sessions() }
                    sessions = result
                    if (selected.isNotEmpty()) turns = withContext(Dispatchers.IO) { api.turns(selected) }
                    error = ""
                } catch (e: Exception) { error = e.message ?: "连接失败" }
            }

            fun submit(text: String) {
                if (busy || text.isBlank()) return
                busy = true
                scope.launch {
                    try {
                        if (selected.isBlank()) {
                            streamConnected = false
                            selected = withContext(Dispatchers.IO) { api.create() }
                            turns = emptyList()
                        }
                        withTimeout(10000L) { while (!streamConnected) delay(50) }
                        pendingPrompt = text.trim()
                        withContext(Dispatchers.IO) { api.send(selected, text.trim()) }
                        draft = ""
                        refresh()
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

            LaunchedEffect(tokenReady) { if (tokenReady) refresh() }
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
                                    refresh()
                                }
                                if (streamReducer.apply(frame)) {
                                    liveText = streamReducer.text
                                    liveReasoning = streamReducer.reasoning
                                }
                                val eventType = frame.optJSONObject("event")?.optString("type")
                                if (frame.optString("type") == "event" && eventType == "assistant/message") {
                                    refresh()
                                    liveText = ""
                                    liveReasoning = ""
                                }
                                if (frame.optString("type") == "event" && eventType == "turn/end") {
                                    refresh()
                                    val latest = turns.lastOrNull()?.response.orEmpty()
                                    if (voiceAutoPlay && latest.isNotBlank()) {
                                        val index = turns.lastIndex
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
                    refresh()
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
                            FairyEye(Modifier.size(150.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    if (withTimeoutOrNull(AGENT_MODE_HOLD_MS) { tryAwaitRelease() } == null) finish()
                                })
                            })
                            Text(greetingForHour(LocalTime.now().hour), color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 35.dp))
                        }
                    } else {
                        FairyEye(Modifier.align(Alignment.TopStart).offset(y = 28.dp).size(54.dp).pointerInput(Unit) {
                            detectTapGestures(onPress = { if (withTimeoutOrNull(AGENT_MODE_HOLD_MS) { tryAwaitRelease() } == null) finish() })
                        }, active = pendingPrompt.isNotBlank())
                        FairyOnlineStatus(pendingPrompt.isNotBlank(), Modifier.align(Alignment.TopStart).offset(x = 70.dp, y = 46.dp))
                        val scroll = rememberScrollState()
                        LaunchedEffect(turns.size, liveText) { if (!scroll.isScrollInProgress && scroll.maxValue - scroll.value < 120) scroll.scrollTo(scroll.maxValue) }
                        Column(Modifier.fillMaxSize().padding(top = 94.dp).verticalScroll(scroll)) {
                            turns.forEachIndexed { index, turn ->
                                if (turn.prompt.isNotBlank()) ChatBubble(ChatMessage("user", turn.prompt), settings.displayName, settings.avatar,
                                    false, false, false, false, false, {}, {}, {}, {}, {})
                                if (turn.response.isNotBlank()) ChatBubble(ChatMessage("assistant", turn.response), settings.displayName, settings.avatar,
                                    false, false, false, playingIndex == index, false,
                                    onCopy = { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DSH 回答", turn.response)) },
                                    onEdit = {}, onRegenerate = {}, onContinue = {},
                                    onReplay = {
                                        if (playingIndex == index) { speechPlayer.stop(); playingIndex = -1 }
                                        else if (speechPlayer.play(settings.voiceEndpoint, settings.voiceToken(), turn.response, index.toLong())) playingIndex = index
                                    }, assistantName = "DSH")
                            }
                            if (pendingPrompt.isNotBlank() && turns.lastOrNull()?.prompt != pendingPrompt)
                                ChatBubble(ChatMessage("user", pendingPrompt), settings.displayName, settings.avatar, false, false, false, false, true, {}, {}, {}, {}, {})
                            if (pendingPrompt.isNotBlank() || liveText.isNotBlank() || liveReasoning.isNotBlank())
                                ChatBubble(ChatMessage("assistant", liveText, liveReasoning), settings.displayName, settings.avatar, false, false, false, false, true, {}, {}, {}, {}, {}, showActions = false, assistantName = "DSH")
                        }
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
                                        speechPlayer.stop(); selected = session.id; listOpen = false; pendingPrompt = ""; liveText = ""; liveReasoning = ""
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
