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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private object ThemeTokens {
    val background = Color(0xFF080D17)
    val accent = Color(0xFF73F1EC)
    val secondary = Color(0xFF9CAABA)
}

class MainActivity : ComponentActivity() {
    private var microphoneGranted by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var hasRecording by mutableStateOf(false)
    private var status by mutableStateOf("准备录音")
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private val recordingFile by lazy { File(cacheDir, "voice-check.m4a") }
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphoneGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            Column(
                modifier = Modifier.fillMaxSize().background(ThemeTokens.background).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("H.D.D", color = ThemeTokens.accent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text("HiFairy 兼容性验证", color = Color.White, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                Text("设备安装与麦克风权限", color = ThemeTokens.secondary)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(if (microphoneGranted) "麦克风权限已授予" else "测试麦克风权限")
                }
                Spacer(Modifier.height(16.dp))
                Text(status, color = ThemeTokens.secondary)
                Spacer(Modifier.height(12.dp))
                Button(enabled = microphoneGranted, onClick = { if (recording) stopRecording() else startRecording() }) {
                    Text(if (recording) "停止录音" else "开始录音")
                }
                Button(enabled = hasRecording && !recording, onClick = { playRecording() }) {
                    Text("播放录音")
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
