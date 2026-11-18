package com.hifairy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.widget.VideoView
import android.widget.TextView
import android.os.Build
import android.content.pm.ActivityInfo
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.launch

private object ThemeTokens {
    val background = Color(0xFF070818)
    val surface = Color(0xFF11152C)
    val accent = Color(0xFF8FB4FF)
    val secondary = Color(0xFF9AA4D6)
    val text = Color(0xFFE8F3FF)
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
            .usePlugin(JLatexMathPlugin.create(44f))
            .build()
    }
    AndroidView(
        factory = { TextView(it).apply { setTextColor(android.graphics.Color.rgb(232, 243, 255)); textSize = 16f; setLineSpacing(4f, 1f); setTextIsSelectable(true); setLinkTextColor(android.graphics.Color.rgb(143, 180, 255)) } },
        update = { markwon.setMarkdown(it, normalizeMath(message)) },
        modifier = modifier
    )
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
    private var introPhase by mutableStateOf("boot")
    private var draft by mutableStateOf("")
    private val messages = mutableStateListOf<String>()
    private val conversation = mutableListOf<Pair<String, String>>()
    private var sending by mutableStateOf(false)
    private lateinit var secureSettings: SecureSettings
    private var keyDraft by mutableStateOf("")
    private var modelDraft by mutableStateOf("deepseek-flash")
    private var effortDraft by mutableStateOf("high")
    private val recordingFile by lazy { File(cacheDir, "voice-check.m4a") }
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphoneGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureSettings = SecureSettings(this)
        modelDraft = secureSettings.model
        effortDraft = secureSettings.effort
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
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
            Box(Modifier.fillMaxSize()) {
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
            Column(Modifier.fillMaxSize().background(ThemeTokens.background).padding(20.dp)) {
                if (page == "settings") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("‹", color = ThemeTokens.accent, fontSize = 34.sp, modifier = Modifier.clickable { page = "history" }.padding(end = 24.dp))
                        Text("设置", color = ThemeTokens.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("☰", color = ThemeTokens.accent, fontSize = 27.sp, modifier = Modifier.clickable { page = "history" })
                        Text("H.D.D Ⅲ", color = ThemeTokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = TypeTokens.terminalFont, letterSpacing = TypeTokens.brandTracking)
                        Text("＋", color = ThemeTokens.accent, fontSize = 27.sp, modifier = Modifier.clickable { page = "chat"; draft = ""; messages.clear(); conversation.clear() })
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
                                Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(top = 110.dp, bottom = 16.dp)) {
                                    messages.forEach { message -> MarkdownMessage(message, Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                                }
                            } else {
                                Text("晚上好，绳匠。", color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = TypeTokens.interfaceFont, letterSpacing = TypeTokens.labelTracking, modifier = Modifier.align(Alignment.Center).offset(y = 120.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().background(ThemeTokens.surface, RoundedCornerShape(18.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("向 Fairy 提问…", color = ThemeTokens.secondary) }, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedTextColor = ThemeTokens.text, unfocusedTextColor = ThemeTokens.text, cursorColor = ThemeTokens.accent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                            Text("➤", color = ThemeTokens.accent, fontSize = 25.sp, modifier = Modifier.clickable { sendMessage() })
                        }
                    }
                    "history" -> {
                        Text("历史对话", color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = TypeTokens.interfaceFont, modifier = Modifier.padding(top = 32.dp))
                        Text("新对话", color = ThemeTokens.accent, modifier = Modifier.fillMaxWidth().clickable { page = "chat"; messages.clear(); conversation.clear() }.padding(vertical = 24.dp))
                        Spacer(Modifier.weight(1f))
                        Text("设置  ›", color = ThemeTokens.text, modifier = Modifier.fillMaxWidth().clickable { page = "settings" }.padding(vertical = 20.dp))
                    }
                    else -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
            Box(Modifier.fillMaxSize().alpha(curtain.value).background(Color.Black))
            }
        }
    }

    private fun sendMessage() {
        val input = draft.trim()
        if (input.isEmpty() || sending) return
        val apiKey = secureSettings.apiKey()
        if (apiKey.isEmpty()) { page = "settings"; status = "请先配置 DeepSeek API Key"; return }
        draft = ""
        messages.add("你：$input")
        conversation.add("user" to input)
        val responseIndex = messages.size
        messages.add("Fairy：")
        sending = true
        val context = conversation.toList()
        Thread {
            val answer = StringBuilder()
            try {
                DeepSeekClient().stream(apiKey, secureSettings.model, secureSettings.effort, context) { token ->
                    answer.append(token)
                    runOnUiThread { messages[responseIndex] = "Fairy：$answer" }
                }
                runOnUiThread { conversation.add("assistant" to answer.toString()); sending = false }
            } catch (e: Exception) {
                runOnUiThread { messages[responseIndex] = "请求失败：${e.message}"; sending = false }
            }
        }.start()
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
        recorder?.release()
        player?.release()
        super.onDestroy()
    }
}
