package com.hifairy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.widget.VideoView
import android.widget.TextView
import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.content.pm.ActivityInfo
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.launch

private object ThemeTokens {
    val background = Color(0xFF070818)
    val surface = Color(0xFF11152C)
    val accent = Color(0xFF8FB4FF)
    val secondary = Color(0xFF9AA4D6)
    val text = Color(0xFFE8F3FF)
    val fairyBubble = Color(0xFF161A2A)
    val userBubble = Color(0xFF293765)
    val codeBackground = 0xFF080914.toInt()
    val reasoningBubble = Color(0xFF101B27)
    val historyCard = Color(0xFF20263E)
    val activeHistoryCard = Color(0xFF0B1022)
    val drawerVeil = Color(0xFFC8D5FF)
}

private object TypeTokens {
    val interfaceFont = FontFamily.SansSerif
    val terminalFont = FontFamily.Monospace
    val markFont = FontFamily.Serif
    val eyebrowTracking = 0.22.sp
    val brandTracking = 0.34.sp
    val labelTracking = 0.06.sp
}

private fun normalizeMath(markdown: String): String = markdown
    .replace("\\[", "\n$$\n").replace("\\]", "\n$$\n")
    .replace("\\(", "$$").replace("\\)", "$$")

@Composable
private fun MarkdownMessage(message: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(44f) { it.inlinesEnabled(true) })
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.codeBlockBackgroundColor(ThemeTokens.codeBackground)
                        .codeBlockTextColor(android.graphics.Color.rgb(232, 243, 255))
                        .codeBackgroundColor(ThemeTokens.codeBackground)
                }
            })
            .build()
    }
    AndroidView(
        factory = { TextView(it).apply { setTextColor(android.graphics.Color.rgb(232, 243, 255)); textSize = 16f; setLineSpacing(4f, 1f); setTextIsSelectable(true); setLinkTextColor(android.graphics.Color.rgb(143, 180, 255)) } },
        update = { markwon.setMarkdown(it, normalizeMath(message)) },
        modifier = modifier
    )
}

@Composable
private fun ChatAvatar(role: String, avatar: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resource = if (role == "assistant") R.drawable.fairy_eye else if (avatar == "zhe") R.drawable.avatar_zhe else R.drawable.avatar_ling
    val custom = remember(avatar) {
        if (role == "user" && avatar == "custom") BitmapFactory.decodeFile(File(context.filesDir, "custom-avatar.jpg").absolutePath)?.asImageBitmap() else null
    }
    Box(modifier.aspectRatio(1f).clip(CircleShape), contentAlignment = Alignment.Center) {
        val imageModifier = Modifier.fillMaxSize().scale(if (role == "user" && custom == null) 1.14f else 1f)
        if (custom != null) Image(custom, contentDescription = "我的头像", modifier = imageModifier, contentScale = ContentScale.Crop)
        else Image(painterResource(resource), contentDescription = if (role == "assistant") "Fairy 眼徽" else "我的头像", modifier = imageModifier, contentScale = ContentScale.Crop)
    }
}

@Composable
private fun ActionGlyph(kind: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val unit = size.width / 20f
        val stroke = 2f * unit
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke, cap = StrokeCap.Round)
        }
        when (kind) {
            "copy" -> {
                line(3f, 13f, 3f, 3f); line(3f, 3f, 13f, 3f); line(13f, 3f, 13f, 5f)
                line(7f, 7f, 17f, 7f); line(17f, 7f, 17f, 17f)
                line(17f, 17f, 7f, 17f); line(7f, 17f, 7f, 7f)
            }
            "retry" -> {
                drawArc(color, 42f, 285f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
                line(17f, 3f, 17f, 8f); line(17f, 8f, 12f, 8f)
            }
            "edit" -> {
                line(4f, 16f, 6f, 11f); line(6f, 11f, 14f, 3f)
                line(14f, 3f, 17f, 6f); line(17f, 6f, 9f, 14f)
                line(9f, 14f, 4f, 16f)
            }
            "cancel" -> { line(5f, 5f, 15f, 15f); line(15f, 5f, 5f, 15f) }
        }
    }
}

@Composable
private fun MessageAction(kind: String, label: String, onClick: () -> Unit, color: Color = ThemeTokens.secondary) {
    Box(Modifier.size(40.dp).semantics { contentDescription = label }.clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        ActionGlyph(kind, color)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, displayName: String, avatar: String, showRegenerate: Boolean, showEdit: Boolean, onCopy: () -> Unit, onEdit: () -> Unit, onRegenerate: () -> Unit) {
    val isUser = message.role == "user"
    var reasoningExpanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!isUser) ChatAvatar("assistant", avatar, Modifier.size(36.dp))
        Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 8.dp), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(if (isUser) displayName else "Fairy", color = ThemeTokens.secondary, fontSize = 11.sp, fontFamily = TypeTokens.terminalFont, modifier = Modifier.padding(bottom = 5.dp))
            if (!isUser && message.reasoning.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(ThemeTokens.reasoningBubble, RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF294451), RoundedCornerShape(14.dp)).clickable { reasoningExpanded = !reasoningExpanded }.padding(12.dp)) {
                    Text(if (reasoningExpanded) "思考过程  ⌃" else "思考过程  ›  点击展开", color = ThemeTokens.accent, fontSize = 12.sp)
                    if (reasoningExpanded) MarkdownMessage(message.reasoning, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Spacer(Modifier.height(7.dp))
            }
            if (message.content.isNotBlank() || message.reasoning.isBlank()) {
                Box(Modifier.background(if (isUser) ThemeTokens.userBubble else ThemeTokens.fairyBubble, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                    MarkdownMessage(message.content.ifBlank { "正在思考…" }, Modifier.fillMaxWidth())
                }
            }
            if (!isUser && message.content.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                MessageAction("copy", "复制回答", onCopy)
                if (showRegenerate) MessageAction("retry", "重新生成回答", onRegenerate)
            }
            if (isUser && showEdit) MessageAction("edit", "编辑并重新发送", onEdit)
        }
        if (isUser) ChatAvatar("user", avatar, Modifier.size(36.dp))
    }
}

@Composable
private fun darkFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = ThemeTokens.text, unfocusedTextColor = ThemeTokens.text,
    cursorColor = ThemeTokens.accent,
    focusedContainerColor = ThemeTokens.background, unfocusedContainerColor = ThemeTokens.background,
    focusedIndicatorColor = ThemeTokens.accent, unfocusedIndicatorColor = ThemeTokens.secondary
)

@Composable
private fun SettingsCard(eyebrow: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(ThemeTokens.surface, RoundedCornerShape(20.dp)).border(1.dp, Color(0xFF25335C), RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(eyebrow, color = ThemeTokens.accent, fontSize = 10.sp, fontFamily = TypeTokens.terminalFont, letterSpacing = TypeTokens.eyebrowTracking)
        Text(title, color = ThemeTokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

class MainActivity : ComponentActivity() {
    private var microphoneGranted by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var hasRecording by mutableStateOf(false)
    private var status by mutableStateOf("准备录音")
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var page by mutableStateOf("chat")
    private var historyOpen by mutableStateOf(false)
    private var introPhase by mutableStateOf("boot")
    private var draft by mutableStateOf("")
    private val messages = mutableStateListOf<ChatMessage>()
    private val sessions = mutableStateListOf<ChatSession>()
    private var currentSessionId by mutableLongStateOf(0L)
    private lateinit var chatStore: ChatStore
    private var activeClient: DeepSeekClient? = null
    private var requestId = 0L
    private var renamingId by mutableLongStateOf(0L)
    private var historyMenuId by mutableLongStateOf(0L)
    private var renameDraft by mutableStateOf("")
    private var editingMessageIndex by mutableIntStateOf(-1)
    private var sending by mutableStateOf(false)
    private lateinit var secureSettings: SecureSettings
    private var keyDraft by mutableStateOf("")
    private var modelDraft by mutableStateOf("deepseek-flash")
    private var effortDraft by mutableStateOf("high")
    private var displayNameDraft by mutableStateOf("绳匠")
    private var avatarChoice by mutableStateOf("ling")
    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            contentResolver.openInputStream(uri)?.use { source -> File(filesDir, "custom-avatar.jpg").outputStream().use { source.copyTo(it) } }
            avatarChoice = "custom"
            secureSettings.avatar = "custom"
        } catch (_: Exception) { status = "头像读取失败" }
    }
    private val recordingFile by lazy { File(cacheDir, "voice-check.m4a") }
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphoneGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureSettings = SecureSettings(this)
        chatStore = ChatStore(this)
        sessions.addAll(chatStore.load())
        sessions.lastOrNull()?.let { messages.addAll(it.messages); currentSessionId = it.id }
        modelDraft = secureSettings.model
        effortDraft = secureSettings.effort
        displayNameDraft = secureSettings.displayName
        avatarChoice = secureSettings.avatar
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val drawerWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() * 0.75f }
            val drawerProgress = remember { Animatable(0f) }
            val closeDrawer = { historyOpen = false; scope.launch { drawerProgress.animateTo(0f, tween(240)) } }
            BackHandler(historyOpen || drawerProgress.value > 0.01f) { closeDrawer() }
            LaunchedEffect(historyOpen) { drawerProgress.animateTo(if (historyOpen) 1f else 0f, tween(360)) }
            val curtain = remember { Animatable(0f) }
            val pulse = rememberInfiniteTransition(label = "eye pulse")
            val eyeScale by pulse.animateFloat(initialValue = 0.97f, targetValue = 1.04f, animationSpec = infiniteRepeatable(tween(1700, easing = EaseInOutSine), RepeatMode.Reverse), label = "eye scale")
            LaunchedEffect(introPhase) {
                if (introPhase == "boot") requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                if (introPhase == "transition") {
                    curtain.animateTo(1f, tween(320))
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    kotlinx.coroutines.delay(380)
                    introPhase = "greeting"
                    scope.launch { curtain.animateTo(0f, tween(380)) }
                }
                if (introPhase == "greeting") {
                    kotlinx.coroutines.delay(2100)
                    introPhase = "ready"
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(ThemeTokens.background).draggable(
                state = rememberDraggableState { delta ->
                    scope.launch { drawerProgress.snapTo((drawerProgress.value + delta / drawerWidthPx).coerceIn(0f, 1f)) }
                },
                orientation = Orientation.Horizontal,
                onDragStarted = { drawerProgress.stop() },
                onDragStopped = { velocity ->
                    val open = if (velocity > 700f) true else if (velocity < -700f) false else drawerProgress.value >= 0.5f
                    drawerProgress.animateTo(if (open) 1f else 0f, tween(240))
                    historyOpen = open
                }
            )) {
            val drawerWidth = maxWidth * 0.75f
            val progress = drawerProgress.value
            val chatOffset = drawerWidth * progress
            val chatScale = 1f - 0.04f * progress
            val chatRotation = -5f * progress
            if (introPhase == "boot" || introPhase == "transition") {
                Box(Modifier.fillMaxSize().background(Color(0xFF02050D))) {
                    AndroidView(
                        factory = { VideoView(it).apply {
                            setVideoURI(android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.fairy_boot}"))
                            setOnCompletionListener { if (introPhase == "boot") introPhase = "transition" }
                            start()
                        } },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (introPhase == "boot") Text("SKIP", color = Color(0xD9CEF0FF), fontSize = 10.sp, fontFamily = TypeTokens.interfaceFont, letterSpacing = 2.6.sp, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).background(Color(0x8C061022)).border(1.dp, Color(0x616EDCFF)).clickable { introPhase = "transition" }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            } else {
            Column(Modifier.offset(x = chatOffset).graphicsLayer {
                scaleX = chatScale
                scaleY = chatScale
                rotationY = chatRotation
                transformOrigin = TransformOrigin(0f, 0.5f)
                cameraDistance = 24f * density
                shadowElevation = 14.dp.toPx() * progress
                shape = RoundedCornerShape(20.dp)
                clip = progress > 0f
            }.fillMaxSize().background(ThemeTokens.background).padding(20.dp)) {
                if (page == "settings") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("‹", color = ThemeTokens.accent, fontSize = 34.sp, modifier = Modifier.clickable { page = "chat"; historyOpen = true }.padding(end = 24.dp))
                        Text("设置", color = ThemeTokens.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("☰", color = ThemeTokens.accent, fontSize = 27.sp, modifier = Modifier.clickable { historyOpen = true })
                        Text("H.D.D Ⅲ", color = ThemeTokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = TypeTokens.terminalFont, letterSpacing = TypeTokens.brandTracking)
                        Text("＋", color = ThemeTokens.accent, fontSize = 27.sp, modifier = Modifier.clickable { newChat() })
                    }
                }
                when (page) {
                    "chat" -> {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val hasConversation = messages.isNotEmpty()
                            val eyeSize by animateDpAsState(if (hasConversation) 54.dp else 154.dp, tween(550), label = "eye size")
                            val eyeX by animateDpAsState(if (hasConversation) 0.dp else (maxWidth - eyeSize) / 2, tween(550), label = "eye x")
                            val eyeY by animateDpAsState(if (hasConversation) 28.dp else (maxHeight - eyeSize) / 2, tween(550), label = "eye y")
                            Image(painterResource(R.drawable.fairy_eye), contentDescription = "Fairy 眼徽", modifier = Modifier.offset(eyeX, eyeY).size(eyeSize).scale(eyeScale))
                            if (hasConversation) {
                                Text("FAIRY // ONLINE", color = ThemeTokens.accent, fontSize = 10.sp, fontFamily = TypeTokens.terminalFont, letterSpacing = TypeTokens.eyebrowTracking, modifier = Modifier.offset(70.dp, 46.dp))
                                val scrollState = rememberScrollState()
                                LaunchedEffect(messages.size) { scrollState.animateScrollTo(scrollState.maxValue) }
                                LaunchedEffect(messages.lastOrNull()) {
                                    if (!scrollState.isScrollInProgress && scrollState.maxValue - scrollState.value < 120) scrollState.scrollTo(scrollState.maxValue)
                                }
                                Column(Modifier.fillMaxSize().padding(top = 94.dp).verticalScroll(scrollState).padding(bottom = 16.dp)) {
                                    messages.forEachIndexed { index, message ->
                                        key(currentSessionId, index) {
                                            ChatBubble(message, displayNameDraft, avatarChoice,
                                                showRegenerate = index == messages.lastIndex && !sending,
                                                showEdit = index == messages.indexOfLast { it.role == "user" } && !sending,
                                                onCopy = { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Fairy 回答", message.content)) },
                                                onEdit = { stopGeneration(); editingMessageIndex = index; draft = message.content },
                                                onRegenerate = { regenerate() })
                                        }
                                    }
                                }
                            } else {
                                Text("晚上好，$displayNameDraft。", color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = TypeTokens.interfaceFont, letterSpacing = TypeTokens.labelTracking, modifier = Modifier.align(Alignment.Center).offset(y = 120.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().background(if (editingMessageIndex >= 0) Color(0xFF1C2549) else ThemeTokens.surface, RoundedCornerShape(18.dp)).border(if (editingMessageIndex >= 0) 1.dp else 0.dp, ThemeTokens.accent, RoundedCornerShape(18.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (editingMessageIndex >= 0) ActionGlyph("edit", ThemeTokens.accent, Modifier.padding(start = 8.dp))
                            TextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("向 Fairy 提问…", color = ThemeTokens.secondary) }, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedTextColor = ThemeTokens.text, unfocusedTextColor = ThemeTokens.text, cursorColor = ThemeTokens.accent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                            if (editingMessageIndex >= 0) MessageAction("cancel", "取消编辑", { editingMessageIndex = -1; draft = "" }, ThemeTokens.text)
                            Text(if (sending) "■" else "➤", color = ThemeTokens.accent, fontSize = 25.sp, modifier = Modifier.clickable { if (sending) stopGeneration() else sendMessage() })
                        }
                    }
                    else -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            SettingsCard("PROFILE // IDENTITY", "我的资料") {
                                Text("昵称", color = ThemeTokens.secondary, fontSize = 12.sp)
                                TextField(value = displayNameDraft, onValueChange = { displayNameDraft = it.take(20); secureSettings.displayName = displayNameDraft.ifBlank { "绳匠" } }, modifier = Modifier.fillMaxWidth(), colors = darkFieldColors())
                                Text("头像", color = ThemeTokens.secondary, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    listOf("ling" to "铃", "zhe" to "哲").forEach { (choice, label) ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { avatarChoice = choice; secureSettings.avatar = choice }) {
                                            ChatAvatar("user", choice, Modifier.size(52.dp).border(if (avatarChoice == choice) 2.dp else 0.dp, ThemeTokens.accent, CircleShape))
                                            Text(label, color = ThemeTokens.text, fontSize = 12.sp)
                                        }
                                    }
                                    Text("自选图片", color = ThemeTokens.accent, fontSize = 13.sp, modifier = Modifier.clickable { pickAvatar.launch("image/*") }.padding(10.dp))
                                }
                                if (avatarChoice == "custom") ChatAvatar("user", "custom", Modifier.size(52.dp))
                            }
                            SettingsCard("DEEPSEEK // CONNECTION", "连接配置") {
                                Text("API KEY", color = ThemeTokens.secondary, fontSize = 11.sp, fontFamily = TypeTokens.terminalFont)
                                TextField(value = keyDraft, onValueChange = { keyDraft = it }, visualTransformation = PasswordVisualTransformation(), placeholder = { Text(if (secureSettings.apiKey().isEmpty()) "输入 API Key" else "已保存 · 输入新 Key 可替换", color = ThemeTokens.secondary) }, modifier = Modifier.fillMaxWidth(), colors = darkFieldColors())
                                Text("密钥仅保存在本机的加密存储中", color = ThemeTokens.secondary, fontSize = 12.sp)
                                Button(onClick = { if (keyDraft.isNotBlank()) { secureSettings.saveApiKey(keyDraft.trim()); keyDraft = ""; status = "API Key 已加密保存" } }) { Text("保存密钥") }
                            }
                            SettingsCard("MODEL // RESPONSE", "回答偏好") {
                                Text("模型", color = ThemeTokens.secondary, fontSize = 12.sp)
                                TextField(value = modelDraft, onValueChange = { modelDraft = it; secureSettings.model = it }, modifier = Modifier.fillMaxWidth(), colors = darkFieldColors())
                                Text("思考强度", color = ThemeTokens.secondary, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("none", "low", "high", "max").forEach { option -> Text(option, color = if (effortDraft == option) ThemeTokens.background else ThemeTokens.secondary, fontSize = 12.sp, modifier = Modifier.background(if (effortDraft == option) ThemeTokens.accent else ThemeTokens.background, RoundedCornerShape(10.dp)).clickable { effortDraft = option; secureSettings.effort = option }.padding(horizontal = 10.dp, vertical = 9.dp)) } }
                            }
                            SettingsCard("VOICE // DEVICE", "语音设备测试") {
                                Text(status, color = ThemeTokens.secondary, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) }) { Text(if (microphoneGranted) "已授权" else "授权麦克风") }
                                    Button(enabled = microphoneGranted, onClick = { if (recording) stopRecording() else startRecording() }) { Text(if (recording) "停止" else "录音") }
                                }
                                Button(enabled = hasRecording && !recording, onClick = { playRecording() }) { Text("播放测试录音") }
                            }
                        }
                    }
                }
            }
            }
            if (introPhase == "ready" || introPhase == "greeting") {
                if (progress > 0.001f) Box(Modifier.align(Alignment.CenterEnd).width(maxWidth - chatOffset).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Transparent, ThemeTokens.drawerVeil.copy(alpha = 0.32f * progress)))).clickable(onClickLabel = "收起历史对话") { closeDrawer() })
                if (progress > 0.001f || historyOpen) {
                    val drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
                    Column(Modifier.offset(x = -drawerWidth * (1f - progress)).fillMaxHeight().width(drawerWidth).shadow(24.dp, drawerShape).background(ThemeTokens.surface, drawerShape).border(1.dp, Color(0xFF384878), drawerShape).padding(horizontal = 16.dp, vertical = 24.dp)) {
                        Text("历史对话", color = ThemeTokens.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("＋  新对话", color = ThemeTokens.accent, modifier = Modifier.fillMaxWidth().clickable { newChat() }.padding(vertical = 22.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            sessions.asReversed().forEach { session ->
                                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(if (session.id == currentSessionId) ThemeTokens.activeHistoryCard else ThemeTokens.historyCard, RoundedCornerShape(12.dp)).padding(10.dp)) {
                                    if (renamingId == session.id) {
                                        TextField(renameDraft, onValueChange = { renameDraft = it.take(50) }, modifier = Modifier.fillMaxWidth(), colors = darkFieldColors())
                                        Text("保存", color = ThemeTokens.accent, modifier = Modifier.clickable { renameSession(session.id, renameDraft); renamingId = 0L }.padding(top = 8.dp))
                                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(session.title, color = ThemeTokens.text, maxLines = 2, modifier = Modifier.weight(1f).clickable { openSession(session) }.padding(vertical = 8.dp))
                                        Box(Modifier.size(32.dp).clickable(onClickLabel = "会话选项") { historyMenuId = session.id }, contentAlignment = Alignment.Center) {
                                            Canvas(Modifier.size(18.dp)) {
                                                val dot = 1.6.dp.toPx()
                                                listOf(3f, 9f, 15f).forEach { y -> drawCircle(ThemeTokens.secondary, dot, Offset(size.width / 2, y.dp.toPx())) }
                                            }
                                            DropdownMenu(expanded = historyMenuId == session.id, onDismissRequest = { historyMenuId = 0L }, modifier = Modifier.background(ThemeTokens.surface)) {
                                                DropdownMenuItem(text = { Text("重命名", color = ThemeTokens.text) }, onClick = { historyMenuId = 0L; renamingId = session.id; renameDraft = session.title })
                                                DropdownMenuItem(text = { Text("删除", color = ThemeTokens.text) }, onClick = { historyMenuId = 0L; deleteSession(session.id) })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text("设置  ›", color = ThemeTokens.text, modifier = Modifier.fillMaxWidth().clickable { historyOpen = false; page = "settings" }.padding(vertical = 16.dp))
                    }
                }
            }
            Box(Modifier.fillMaxSize().alpha(curtain.value).background(Color.Black))
            }
        }
    }

    private fun sendMessage() {
        val input = draft.trim()
        if (input.isEmpty() || sending) return
        if (secureSettings.apiKey().isEmpty()) { page = "settings"; status = "请先配置 DeepSeek API Key"; return }
        draft = ""
        if (editingMessageIndex >= 0) {
            val index = editingMessageIndex
            editingMessageIndex = -1
            if (index in messages.indices && messages[index].role == "user") {
                while (messages.size > index) messages.removeAt(messages.lastIndex)
            }
        }
        messages.add(ChatMessage("user", input))
        if (currentSessionId == 0L) currentSessionId = System.currentTimeMillis()
        saveCurrentSession()
        beginGeneration()
    }

    private fun beginGeneration() {
        if (sending) return
        val apiKey = secureSettings.apiKey()
        if (apiKey.isEmpty()) { page = "settings"; return }
        val context = messages.filter { it.content.isNotBlank() && it.content != "已停止生成" && !it.content.startsWith("请求失败：") }
            .map { it.role to it.content }
        val responseIndex = messages.size
        messages.add(ChatMessage("assistant", ""))
        sending = true
        val id = ++requestId
        val client = DeepSeekClient()
        activeClient = client
        Thread {
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            try {
                client.stream(apiKey, secureSettings.model, secureSettings.effort, context, { token ->
                    answer.append(token)
                    val snapshot = answer.toString()
                    runOnUiThread { if (id == requestId && responseIndex < messages.size) messages[responseIndex] = ChatMessage("assistant", snapshot, reasoning.toString()) }
                }, { token ->
                    reasoning.append(token)
                    val snapshot = reasoning.toString()
                    runOnUiThread { if (id == requestId && responseIndex < messages.size) messages[responseIndex] = ChatMessage("assistant", answer.toString(), snapshot) }
                })
                runOnUiThread {
                    if (id == requestId) {
                        messages[responseIndex] = ChatMessage("assistant", answer.toString().ifBlank { "未收到回答" }, reasoning.toString())
                        sending = false
                        activeClient = null
                        saveCurrentSession()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (id == requestId) {
                    messages[responseIndex] = ChatMessage("assistant", "请求失败：${e.message}", reasoning.toString())
                    sending = false
                    activeClient = null
                    saveCurrentSession()
                } }
            }
        }.start()
    }

    private fun stopGeneration() {
        if (!sending) return
        requestId++
        activeClient?.cancel()
        activeClient = null
        sending = false
        val index = messages.lastIndex
        if (index >= 0 && messages[index].role == "assistant" && messages[index].content.isBlank()) {
            messages[index] = messages[index].copy(content = "已停止生成")
        }
        saveCurrentSession()
    }

    private fun regenerate() {
        if (sending || messages.lastOrNull()?.role != "assistant") return
        messages.removeAt(messages.lastIndex)
        saveCurrentSession()
        beginGeneration()
    }

    private fun newChat() {
        historyOpen = false
        stopGeneration()
        saveCurrentSession()
        messages.clear()
        currentSessionId = 0L
        editingMessageIndex = -1
        draft = ""
        page = "chat"
    }

    private fun openSession(session: ChatSession) {
        historyOpen = false
        stopGeneration()
        saveCurrentSession()
        messages.clear()
        messages.addAll(session.messages)
        currentSessionId = session.id
        editingMessageIndex = -1
        page = "chat"
    }

    private fun saveCurrentSession() {
        if (currentSessionId == 0L || messages.isEmpty()) return
        val title = sessions.firstOrNull { it.id == currentSessionId }?.title
            ?: messages.firstOrNull { it.role == "user" }?.content?.take(28).orEmpty()
        val updated = ChatSession(currentSessionId, title, messages.toList())
        val index = sessions.indexOfFirst { it.id == currentSessionId }
        if (index >= 0) sessions[index] = updated else sessions.add(updated)
        chatStore.save(sessions)
    }

    private fun renameSession(id: Long, title: String) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0 && title.isNotBlank()) {
            sessions[index] = sessions[index].copy(title = title.trim())
            chatStore.save(sessions)
        }
    }

    private fun deleteSession(id: Long) {
        if (currentSessionId == id) { stopGeneration(); messages.clear(); currentSessionId = 0L }
        sessions.removeAll { it.id == id }
        chatStore.save(sessions)
    }

    private fun startRecording() {
        try {
            player?.release()
            player = null
            recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordingFile.absolutePath)
                prepare()
                start()
            }
            recording = true
            hasRecording = false
            status = "正在录音…"
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            status = "录音失败：${e.javaClass.simpleName}"
        }
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
            hasRecording = recordingFile.length() > 0
            status = if (hasRecording) "录音完成，可播放" else "录音文件为空"
        } catch (e: Exception) {
            status = "停止录音失败：${e.javaClass.simpleName}"
        } finally {
            recorder?.release()
            recorder = null
            recording = false
        }
    }

    private fun playRecording() {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(recordingFile.absolutePath)
                setOnCompletionListener { status = "播放完成" }
                prepare()
                start()
            }
            status = "正在播放…"
        } catch (e: Exception) {
            status = "播放失败：${e.javaClass.simpleName}"
        }
    }

    override fun onDestroy() {
        activeClient?.cancel()
        recorder?.release()
        player?.release()
        super.onDestroy()
    }
}
