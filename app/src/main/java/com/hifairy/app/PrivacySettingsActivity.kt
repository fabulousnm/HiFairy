package com.hifairy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PrivacySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        setContent {
            var apiKey by remember { mutableStateOf("") }
            var voiceToken by remember { mutableStateOf("") }
            var agentToken by remember { mutableStateOf("") }
            var notice by remember { mutableStateOf("") }
            val colors = TextFieldDefaults.colors(focusedTextColor = ThemeTokens.text, unfocusedTextColor = ThemeTokens.text,
                cursorColor = ThemeTokens.accent, focusedContainerColor = ThemeTokens.surface,
                unfocusedContainerColor = ThemeTokens.surface)
            Column(Modifier.fillMaxSize().background(ThemeTokens.background).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("‹", color = ThemeTokens.accent, fontSize = 32.sp, modifier = Modifier.clickable { finish() })
                    Text("隐私安全", color = ThemeTokens.text, fontSize = 20.sp)
                }
                Text("访问凭据仅在本机加密保存；留空不会覆盖已保存的值。", color = ThemeTokens.secondary,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp, bottom = 18.dp))
                @androidx.compose.runtime.Composable fun secretField(title: String, saved: Boolean, value: String, onChange: (String) -> Unit) {
                    Text(title, color = ThemeTokens.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    TextField(value, onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), colors = colors,
                        placeholder = { Text(if (saved) "已保存 · 输入新值可替换" else "尚未设置", color = ThemeTokens.secondary) })
                }
                secretField("DeepSeek API Key", settings.apiKey().isNotBlank(), apiKey) { apiKey = it }
                secretField("语音服务访问令牌", settings.voiceToken().isNotBlank(), voiceToken) { voiceToken = it }
                secretField("电脑 Agent 访问令牌", settings.agentToken().isNotBlank(), agentToken) { agentToken = it }
                Button(onClick = {
                    if (apiKey.isNotBlank()) settings.saveApiKey(apiKey.trim())
                    if (voiceToken.isNotBlank()) settings.saveVoiceToken(voiceToken.trim())
                    if (agentToken.isNotBlank()) settings.saveAgentToken(agentToken.trim())
                    apiKey = ""; voiceToken = ""; agentToken = ""; notice = "已加密保存"
                }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("保存访问凭据") }
                if (notice.isNotBlank()) Text(notice, color = ThemeTokens.accent, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
