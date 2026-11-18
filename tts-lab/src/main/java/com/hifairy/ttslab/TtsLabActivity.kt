package com.hifairy.ttslab

import android.app.Activity
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class TtsLabActivity : Activity() {
    private data class Sample(val title: String, val file: String)
    private val samples = listOf(
        Sample("原始问候", "greet.wav"),
        Sample("早上好", "greet-morning.wav"),
        Sample("中午好", "greet-noon.wav"),
        Sample("下午好", "greet-afternoon.wav"),
        Sample("晚上好", "greet-evening.wav"),
        Sample("深夜问候", "greet-night.wav")
    )
    private var player: MediaPlayer? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        column.addView(TextView(this).apply { text = "FAIRY // VOICE TEST"; textSize = 23f; setTextColor(0xff8eb7ff.toInt()) })
        column.addView(TextView(this).apply {
            text = "试听参考仓库的六段 Fairy 预录语音，检查卓易通中的播放、音量和音色。此包尚不支持输入任意文字合成。"
            textSize = 15f; setTextColor(0xffd9e3ff.toInt()); setPadding(0, 16, 0, 24)
        })
        status = TextView(this).apply { text = "选择一段语音播放"; textSize = 16f; setTextColor(0xff9adce9.toInt()); setPadding(0, 12, 0, 16) }
        column.addView(status)
        samples.forEach { sample ->
            column.addView(Button(this).apply {
                text = sample.title
                setAllCaps(false)
                setOnClickListener { play(sample) }
            })
        }
        column.addView(Button(this).apply { text = "停止播放"; setAllCaps(false); setOnClickListener { stop(); status.text = "已停止" } })
        setContentView(ScrollView(this).apply { setBackgroundColor(0xff090c1d.toInt()); addView(column) })
    }

    private fun play(sample: Sample) {
        stop()
        val requestedAt = SystemClock.elapsedRealtime()
        try {
            val descriptor = assets.openFd("fairy_voice/${sample.file}")
            player = MediaPlayer().apply {
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                descriptor.close()
                setOnPreparedListener {
                    val preparationMs = SystemClock.elapsedRealtime() - requestedAt
                    status.text = "${sample.title} · 准备 ${preparationMs} ms · 播放中"
                    it.start()
                }
                setOnCompletionListener { status.text = "${sample.title} · 播放完成"; stop() }
                setOnErrorListener { _, what, extra -> status.text = "播放失败：$what / $extra"; stop(); true }
                prepareAsync()
            }
            status.text = "正在准备 ${sample.title}…"
        } catch (error: Exception) {
            status.text = "无法读取音频：${error.message}"
            stop()
        }
    }

    private fun stop() { player?.release(); player = null }
    override fun onDestroy() { stop(); super.onDestroy() }
}
