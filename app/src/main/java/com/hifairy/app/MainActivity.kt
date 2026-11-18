package com.hifairy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
            }
        }
    }
}
