package com.hifairy.voicelab

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import kotlin.concurrent.thread

class VoiceLabActivity : Activity() {
    private val samples = listOf(
        "早上好，主人，请问今天有什么安排？",
        "帮我计算二十三乘以四十七等于多少。",
        "请解释一下人工智能和机器学习的区别。",
        "我想查询明天上海的天气，然后设置一个提醒。",
        "绝区零中的绳匠与仙灵正在执行新的委托。"
    )
    private var sampleIndex = 0
    private lateinit var reference: EditText
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var recordButton: Button
    private var model: Model? = null
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var loading = false
    private var stopping = false
    private var confirmed = ""
    private var finalText = ""
    private var startedAt = 0L
    private var firstPartialAt = 0L
    private var loadMillis = 0L
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 18f; setPadding(0, 14, 0, 14); root.addView(this) }
        label("HiFairy 语音识别实验室")
        label("请照着下面的文字朗读。可修改参考文本后再测试。")
        reference = EditText(this).apply { setText(samples[sampleIndex]); minLines = 2; root.addView(this, ViewGroup.LayoutParams(-1, -2)) }
        Button(this).apply { text = "下一条测试句"; setOnClickListener { sampleIndex = (sampleIndex + 1) % samples.size; reference.setText(samples[sampleIndex]); resetResult() }; root.addView(this) }
        recordButton = Button(this).apply { text = "开始录音识别"; setOnClickListener { if (service == null) start() else stop() }; root.addView(this) }
        status = label("准备就绪。首次启动需解包并加载模型。")
        result = label("识别结果：")
        metrics = label("准确率指标：等待测试")
        Button(this).apply { text = "复制测试报告"; setOnClickListener { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("语音测试报告", report)); status.text = "报告已复制" }; root.addView(this) }
        label("测试条件：Vosk small-cn-0.22；16 kHz、单声道、VOICE_RECOGNITION 音频源。字符错误率越低越好。")
    }

    private fun resetResult() { result.text = "识别结果："; metrics.text = "准确率指标：等待测试"; finalText = "" }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (loading) return
        if (model == null) {
            loading = true
            recordButton.isEnabled = false
            status.text = "正在加载离线模型…"
            val loadStarted = SystemClock.elapsedRealtime()
            StorageService.unpack(this, "vosk-model-small-cn-0.22", "vosk-cn", { loaded ->
                model = loaded
                loadMillis = SystemClock.elapsedRealtime() - loadStarted
                loading = false
                recordButton.isEnabled = true
                beginListening()
            }, { error ->
                loading = false
                recordButton.isEnabled = true
                status.text = "模型加载失败：${error.message}"
            })
        } else beginListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
            else status.text = "麦克风权限未授予"
        }
    }

    private fun beginListening() {
        try {
            resetResult()
            confirmed = ""
            firstPartialAt = 0L
            stopping = false
            val rec = Recognizer(model ?: return, 16000f)
            val speech = SpeechService(rec, 16000f)
            recognizer = rec
            service = speech
            startedAt = SystemClock.elapsedRealtime()
            speech.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    val partial = JSONObject(hypothesis).optString("partial")
                    if (partial.isNotBlank() && firstPartialAt == 0L) firstPartialAt = SystemClock.elapsedRealtime()
                    result.text = "识别结果：${confirmed + partial}"
                }
                override fun onResult(hypothesis: String) { append(hypothesis) }
                override fun onFinalResult(hypothesis: String) {
                    append(hypothesis)
                    finalText = confirmed
                    releaseSession()
                    finishReport()
                }
                override fun onError(error: Exception) { releaseSession(); status.text = "识别失败：${error.message}" }
                override fun onTimeout() { releaseSession(); finishReport() }
            })
            recordButton.text = "停止并计算准确率"
            status.text = "正在识别，请朗读参考文本…"
        } catch (error: Exception) {
            releaseSession()
            status.text = "启动失败：${error.message}"
        }
    }

    private fun append(hypothesis: String) {
        confirmed += JSONObject(hypothesis).optString("text")
        result.text = "识别结果：$confirmed"
    }

    private fun stop() {
        if (stopping) return
        stopping = true
        recordButton.isEnabled = false
        status.text = "正在结束录音…"
        val speech = service ?: return
        thread(name = "voice-lab-stop") { speech.stop() }
    }

    private fun releaseSession() {
        service?.shutdown()
        service = null
        recognizer?.close()
        recognizer = null
        stopping = false
        recordButton.isEnabled = true
        recordButton.text = "开始录音识别"
    }

    private fun finishReport() {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val first = if (firstPartialAt > 0) firstPartialAt - startedAt else -1L
        val expected = reference.text.toString()
        val cer = SpeechAccuracy.characterErrorRate(expected, finalText)
        metrics.text = "字符错误率：${"%.1f".format(cer * 100)}%\n首字延迟：${if (first >= 0) "$first ms" else "无结果"}\n录音时长：$elapsed ms\n模型首次加载：$loadMillis ms"
        status.text = "测试完成。请记录识别错误的词句。"
        report = "参考：$expected\n识别：$finalText\nCER：${"%.3f".format(cer)}\n首字：$first ms\n总时长：$elapsed ms\n首次模型加载：$loadMillis ms\n模型：vosk-model-small-cn-0.22"
    }

    override fun onDestroy() {
        service?.cancel()
        releaseSession()
        model?.close()
        super.onDestroy()
    }
}
