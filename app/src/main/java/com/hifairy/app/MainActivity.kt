package com.hifairy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

class MainActivity : ComponentActivity() {
    private var microphoneGranted by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var hasRecording by mutableStateOf(false)
    private var status by mutableStateOf("准备录音")
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var page by mutableStateOf("chat")
    private var draft by mutableStateOf("")
    private val recordingFile by lazy { File(cacheDir, "voice-check.m4a") }
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphoneGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            Column(Modifier.fillMaxSize().background(ThemeTokens.background).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("☰", color = ThemeTokens.accent, fontSize = 27.sp, modifier = Modifier.clickable { page = "history" })
                    Text("H.D.D Ⅲ", color = ThemeTokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = TypeTokens.terminalFont, letterSpacing = TypeTokens.brandTracking)
                    Text("＋", color = ThemeTokens.accent, fontSize = 27.sp, modifier = Modifier.clickable { page = "chat"; draft = "" })
                }
                when (page) {
                    "chat" -> {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(painterResource(R.drawable.fairy_eye), contentDescription = "Fairy 眼徽", modifier = Modifier.size(154.dp))
                            Spacer(Modifier.height(24.dp))
                            Text("晚上好，绳匠。", color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = TypeTokens.interfaceFont, letterSpacing = TypeTokens.labelTracking)
                            Spacer(Modifier.height(8.dp))
                            Text("FAIRY // READY", color = ThemeTokens.secondary, fontSize = 10.sp, fontFamily = TypeTokens.terminalFont, letterSpacing = TypeTokens.eyebrowTracking)
                        }
                        Row(Modifier.fillMaxWidth().background(ThemeTokens.surface, RoundedCornerShape(18.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("向 Fairy 提问…") }, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                            Text("➤", color = ThemeTokens.accent, fontSize = 25.sp)
                        }
                    }
                    "history" -> {
                        Text("历史对话", color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = TypeTokens.interfaceFont, modifier = Modifier.padding(top = 32.dp))
                        Text("新对话", color = ThemeTokens.accent, modifier = Modifier.fillMaxWidth().clickable { page = "chat" }.padding(vertical = 24.dp))
                        Spacer(Modifier.weight(1f))
                        Text("设置  ›", color = ThemeTokens.text, modifier = Modifier.fillMaxWidth().clickable { page = "settings" }.padding(vertical = 20.dp))
                    }
                    else -> {
                        Text("设置", color = ThemeTokens.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = TypeTokens.interfaceFont, modifier = Modifier.padding(top = 32.dp))
                        Text("语音设备测试", color = ThemeTokens.accent, modifier = Modifier.padding(top = 32.dp))
                        Button(onClick = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) }) { Text(if (microphoneGranted) "麦克风已授权" else "授权麦克风") }
                        Text(status, color = ThemeTokens.secondary)
                        Button(enabled = microphoneGranted, onClick = { if (recording) stopRecording() else startRecording() }) { Text(if (recording) "停止录音" else "开始录音") }
                        Button(enabled = hasRecording && !recording, onClick = { playRecording() }) { Text("播放录音") }
                    }
                }
            }
        }
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
