package com.hifairy.ttslab

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import java.util.Locale

class TtsLabActivity : Activity(), TextToSpeech.OnInitListener {
    private var engine: TextToSpeech? = null
    private lateinit var input: EditText
    private lateinit var voices: Spinner
    private lateinit var report: TextView
    private var chineseVoices = emptyList<Voice>()
    private var requestAt = 0L
    private var firstAudioAt = 0L
    private var currentId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        input = EditText(this).apply { setText("主人，H.D.D 语音链路测试完成。需要我继续处理什么任务？"); minLines = 2 }
        voices = Spinner(this)
        report = TextView(this).apply { text = "正在检查系统语音引擎…"; textSize = 16f }
        val speak = Button(this).apply { text = "播放并测量"; setOnClickListener { play() } }
        val stop = Button(this).apply { text = "停止"; setOnClickListener { engine?.stop(); report.text = "已停止" } }
        column.addView(TextView(this).apply { text = "系统中文语音基线测试（独立应用）"; textSize = 20f })
        column.addView(input)
        column.addView(voices)
        column.addView(speak)
        column.addView(stop)
        column.addView(report)
        setContentView(ScrollView(this).apply { addView(column) })
        engine = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { report.text = "系统语音引擎初始化失败：$status"; return }
        val tts = engine ?: return
        tts.language = Locale.SIMPLIFIED_CHINESE
        chineseVoices = tts.voices.orEmpty().filter { it.locale.language == "zh" && !it.isNetworkConnectionRequired }.sortedBy { it.name }
        voices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            chineseVoices.map { "${it.name} · ${it.locale} · ${if (it.isNetworkConnectionRequired) "联网" else "本地"}" })
        report.text = if (chineseVoices.isEmpty()) "未发现本地中文语音。请检查卓易通中的 TTS 引擎。" else "发现 ${chineseVoices.size} 个本地中文语音；请选择后测试首块音频延迟。"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { if (utteranceId == currentId) runOnUiThread { report.text = "开始合成…" } }
            override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (utteranceId == currentId) runOnUiThread { report.text = "合成启动；采样率 $sampleRateInHz Hz" }
            }
            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                if (utteranceId == currentId && firstAudioAt == 0L) {
                    firstAudioAt = SystemClock.elapsedRealtime()
                    val latency = firstAudioAt - requestAt
                    runOnUiThread { report.text = "首块音频延迟 ${latency} ms；正在播放…" }
                }
            }
            override fun onDone(utteranceId: String) {
                if (utteranceId == currentId) {
                    val total = SystemClock.elapsedRealtime() - requestAt
                    runOnUiThread { report.text = "首块音频：${if (firstAudioAt == 0L) "引擎未报告" else "${firstAudioAt - requestAt} ms"}；总耗时：$total ms" }
                }
            }
            override fun onError(utteranceId: String) { if (utteranceId == currentId) runOnUiThread { report.text = "播报失败" } }
        })
    }

    private fun play() {
        val tts = engine ?: return
        val voice = chineseVoices.getOrNull(voices.selectedItemPosition)
        if (voice == null) { report.text = "没有可用的中文语音"; return }
        tts.stop()
        tts.voice = voice
        currentId = "tts-${SystemClock.elapsedRealtime()}"
        firstAudioAt = 0L
        requestAt = SystemClock.elapsedRealtime()
        val result = tts.speak(input.text.toString(), TextToSpeech.QUEUE_FLUSH, null, currentId)
        if (result != TextToSpeech.SUCCESS) report.text = "语音请求失败：$result"
    }

    override fun onDestroy() { engine?.stop(); engine?.shutdown(); super.onDestroy() }
}
