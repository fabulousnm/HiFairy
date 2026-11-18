package com.hifairy.app

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import kotlin.concurrent.thread

class VoiceInput(private val context: Context, private val onState: (String) -> Unit, private val onText: (String) -> Unit) {
    private var model: Model? = null
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var loading = false
    private var pendingStart = false
    private var stopping = false
    private var confirmed = ""
    private var partial = ""

    val listening: Boolean get() = service != null

    fun start() {
        if (service != null || loading) return
        if (model == null) {
            loading = true
            pendingStart = true
            onState("正在准备离线中文识别模型…")
            StorageService.unpack(context, "vosk-model-small-cn-0.22", "vosk-cn", { loaded ->
                loading = false
                model = loaded
                if (pendingStart) beginListening()
            }, { error ->
                loading = false
                pendingStart = false
                onState("识别模型加载失败：${error.message ?: "未知错误"}")
            })
        } else beginListening()
    }

    private fun beginListening() {
        pendingStart = false
        stopping = false
        confirmed = ""
        partial = ""
        try {
            val rec = Recognizer(model ?: return, 16000f)
            val speech = SpeechService(rec, 16000f)
            recognizer = rec
            service = speech
            speech.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    partial = JSONObject(hypothesis).optString("partial")
                    onText(confirmed + partial)
                }
                override fun onResult(hypothesis: String) { appendFinal(hypothesis) }
                override fun onFinalResult(hypothesis: String) {
                    appendFinal(hypothesis)
                    releaseSession()
                    onState(if (confirmed.isBlank()) "没有识别到语音，请重试" else "已转写，请检查后发送")
                }
                override fun onError(error: Exception) {
                    releaseSession()
                    onState("语音识别失败：${error.message ?: "未知错误"}")
                }
                override fun onTimeout() {
                    releaseSession()
                    onState("语音识别已结束")
                }
            })
            onState("正在听，请说话…")
        } catch (error: IOException) {
            releaseSession()
            onState("麦克风启动失败：${error.message ?: "未知错误"}")
        }
    }

    private fun appendFinal(hypothesis: String) {
        val text = JSONObject(hypothesis).optString("text")
        if (text.isNotBlank()) confirmed += text
        partial = ""
        onText(confirmed)
    }

    fun stop() {
        pendingStart = false
        val speech = service ?: return
        if (stopping) return
        stopping = true
        onState("正在结束识别…")
        thread(name = "vosk-stop") { speech.stop() }
    }

    private fun releaseSession() {
        stopping = false
        service?.shutdown()
        service = null
        recognizer?.close()
        recognizer = null
    }

    fun close() {
        pendingStart = false
        service?.cancel()
        releaseSession()
        model?.close()
        model = null
    }
}
