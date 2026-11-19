package com.hifairy.ttslab

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class TtsLabActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var benchmark: ZipVoiceBenchmark
    private lateinit var input: EditText
    private lateinit var synthButton: Button
    private lateinit var referenceButton: Button
    private lateinit var status: TextView
    private var samplePlayer: MediaPlayer? = null
    @Volatile private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        benchmark = ZipVoiceBenchmark(assets)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        column.addView(TextView(this).apply { text = "FAIRY // LOCAL VOICE TEST"; textSize = 21f; setTextColor(0xff8eb7ff.toInt()) })
        column.addView(TextView(this).apply {
            text = "ZipVoice 在本机离线推理，使用 Fairy 原声片段作为参考。它不是原版 GPT-SoVITS 权重；试听后请比较音色相似度。"
            textSize = 15f; setTextColor(0xffd9e3ff.toInt()); setPadding(0, 16, 0, 20)
        })
        input = EditText(this).apply {
            setText("主人，语音模块已就绪。今天有什么任务需要本系统处理？")
            textSize = 17f; setTextColor(0xffffffff.toInt()); setHintTextColor(0xff9aabd1.toInt())
            minLines = 2; maxLines = 5
        }
        column.addView(input)
        synthButton = Button(this).apply { text = "离线生成并播放"; isEnabled = false; setOnClickListener { synthesize() } }
        column.addView(synthButton)
        referenceButton = Button(this).apply { text = "试听 Fairy 原声参考"; setOnClickListener { playReference() } }
        column.addView(referenceButton)
        status = TextView(this).apply { text = "正在加载本地模型…"; textSize = 16f; setTextColor(0xff9adce9.toInt()); setPadding(0, 18, 0, 0) }
        column.addView(status)
        setContentView(ScrollView(this).apply { setBackgroundColor(0xff090c1d.toInt()); addView(column) })
        worker.execute {
            try {
                val loadMs = benchmark.load()
                runOnUiThread { if (!closed) { status.text = "模型加载：${loadMs} ms。可输入文字测试冷/热推理。"; synthButton.isEnabled = true } }
            } catch (error: Throwable) {
                runOnUiThread { if (!closed) status.text = "模型加载失败：${error.message ?: error.javaClass.simpleName}" }
            }
        }
    }

    private fun synthesize() {
        val text = input.text.toString().trim()
        if (text.isBlank()) { status.text = "请输入要合成的文字"; return }
        synthButton.isEnabled = false
        referenceButton.isEnabled = false
        status.text = "本机推理中…"
        samplePlayer?.release(); samplePlayer = null
        worker.execute {
            var track: AudioTrack? = null
            try {
                val output = benchmark.synthesize(text)
                val metrics = output.metrics
                val sampleRate = 24000
                check(metrics.sampleRate == sampleRate) { "采样率不匹配：${metrics.sampleRate}" }
                val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "音频输出不可用：$minimum" }
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum, 9600))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                check(track.state == AudioTrack.STATE_INITIALIZED) { "音频轨道初始化失败" }
                track.play()
                val pcm = ShortArray(output.samples.size) { index -> (output.samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                var offset = 0
                while (offset < pcm.size) {
                    val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "音频写入失败：$written" }
                    offset += written
                }
                val played = track.playbackHeadPosition.toLong() and 0xffffffffL
                val remainingMs = ((pcm.size - played).coerceAtLeast(0L) * 1000L / sampleRate).coerceAtMost(30000L)
                runOnUiThread { if (!closed) status.text = "首块生成：${metrics.firstChunkMs ?: "无"} ms\n完整推理：${metrics.inferenceMs} ms\n音频长度：${metrics.audioMs} ms\nRTF：${"%.2f".format(metrics.rtf)}\n正在播放…" }
                SystemClock.sleep(remainingMs + 150)
                runOnUiThread { if (!closed) { status.text = status.text.toString().replace("正在播放…", "播放完成"); synthButton.isEnabled = true; referenceButton.isEnabled = true } }
            } catch (error: Throwable) {
                runOnUiThread { if (!closed) { status.text = "合成失败：${error.message ?: error.javaClass.simpleName}"; synthButton.isEnabled = true; referenceButton.isEnabled = true } }
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                track?.release()
            }
        }
    }

    private fun playReference() {
        samplePlayer?.release()
        val descriptor = assets.openFd("fairy_voice/greet-noon.wav")
        samplePlayer = MediaPlayer().apply {
            setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            descriptor.close()
            setOnCompletionListener { status.text = "Fairy 原声参考播放完成" }
            prepare()
            start()
        }
        status.text = "正在播放 Fairy 原声参考（非合成）"
    }

    override fun onDestroy() {
        closed = true
        samplePlayer?.release()
        worker.execute { benchmark.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
