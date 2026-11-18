package com.hifairy.voicelab

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var decodeButton: Button
    private var selectedModel = SpeechModel.ZIPFORMER
    @Volatile private var recording = false
    @Volatile private var busy = false
    private var recordedSamples = FloatArray(0)
    private var recordMillis = 0L
    private val reports = linkedMapOf<SpeechModel, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(value: String) = TextView(this).apply { text = value; textSize = 17f; setPadding(0, 12, 0, 12); root.addView(this) }
        label("HiFairy · 三模型语音测试")
        label("先照着参考句录一次音，再切换三个模型识别同一段录音。")
        reference = EditText(this).apply { setText(samples[sampleIndex]); minLines = 2; root.addView(this, ViewGroup.LayoutParams(-1, -2)) }
        Button(this).apply { text = "下一条测试句"; setOnClickListener { sampleIndex = (sampleIndex + 1) % samples.size; reference.setText(samples[sampleIndex]); recordedSamples = FloatArray(0); reports.clear(); resetResult() }; root.addView(this) }
        recordButton = Button(this).apply { text = "录制测试语音"; setOnClickListener { if (recording) recording = false else startRecording() }; root.addView(this) }
        label("选择识别模型：")
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; root.addView(this) }
        SpeechModel.entries.forEachIndexed { index, model ->
            choices.addView(RadioButton(this).apply { text = "${model.label}（${model.detail}）"; id = index + 1; isChecked = index == 0; setOnClickListener { selectedModel = model; resetResult() } })
        }
        decodeButton = Button(this).apply { text = "用所选模型识别本次录音"; isEnabled = false; setOnClickListener { decode() }; root.addView(this) }
        status = label("等待录音。模型均在本机运行，无需联网。")
        result = label("识别结果：")
        metrics = label("准确率指标：等待测试")
        Button(this).apply { text = "复制三模型报告"; setOnClickListener { copyReport() }; root.addView(this) }
        label("CER 为字符错误率，越低越好；忽略标点和空格。模型加载与推理时间分开计量。")
    }

    private fun resetResult() { result.text = "识别结果："; metrics.text = "准确率指标：等待测试" }

    private fun startRecording() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        recordedSamples = FloatArray(0)
        reports.clear()
        resetResult()
        recording = true
        busy = true
        recordButton.text = "停止录音"
        decodeButton.isEnabled = false
        status.text = "正在录音，最长 30 秒…"
        thread(name = "voice-lab-record") {
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minimum, 3200)
            var recorder: AudioRecord? = null
            try {
                @Suppress("MissingPermission")
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                val chunks = ArrayList<ShortArray>()
                val started = SystemClock.elapsedRealtime()
                recorder.startRecording()
                while (recording && SystemClock.elapsedRealtime() - started < 30000) {
                    val chunk = ShortArray(1600)
                    val count = recorder.read(chunk, 0, chunk.size)
                    if (count > 0) chunks.add(chunk.copyOf(count))
                }
                recordMillis = SystemClock.elapsedRealtime() - started
                val total = chunks.sumOf { it.size }
                val floats = FloatArray(total)
                var offset = 0
                chunks.forEach { chunk -> chunk.forEach { floats[offset++] = it / 32768f } }
                runOnUiThread {
                    recordedSamples = floats
                    status.text = if (total > 0) "录音完成：${recordMillis} ms。现在可切换模型反复识别。" else "录音为空，请重试"
                    decodeButton.isEnabled = total > 0
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "录音失败：${error.message}" }
            } finally {
                try { recorder?.stop() } catch (_: Exception) { }
                recorder?.release()
                recording = false
                busy = false
                runOnUiThread { recordButton.text = "重新录制测试语音" }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else status.text = "麦克风权限未授予"
        }
    }

    private fun decode() {
        if (busy || recordedSamples.isEmpty()) return
        busy = true
        val model = selectedModel
        val audio = recordedSamples
        decodeButton.isEnabled = false
        recordButton.isEnabled = false
        status.text = "正在加载 ${model.label} 并识别…"
        thread(name = "voice-lab-decode") {
            try {
                val output = SpeechModels.decode(assets, model, audio)
                runOnUiThread {
                    val expected = reference.text.toString()
                    val cer = SpeechAccuracy.characterErrorRate(expected, output.text)
                    result.text = "${model.label}：${output.text}"
                    metrics.text = "字符错误率：${"%.1f".format(cer * 100)}%\n模型加载：${output.loadMillis} ms\n推理：${output.decodeMillis} ms\n录音：$recordMillis ms"
                    reports[model] = "${model.label}\n参考：$expected\n识别：${output.text}\nCER：${"%.3f".format(cer)}\n加载：${output.loadMillis} ms；推理：${output.decodeMillis} ms"
                    status.text = "识别完成。可切换模型比较同一段录音。"
                }
            } catch (error: Throwable) {
                runOnUiThread { status.text = "${model.label} 识别失败：${error.message ?: error.javaClass.simpleName}" }
            } finally {
                runOnUiThread { busy = false; decodeButton.isEnabled = true; recordButton.isEnabled = true }
            }
        }
    }

    private fun copyReport() {
        val text = "HiFairy 三模型测试\n录音：$recordMillis ms\n\n" + reports.values.joinToString("\n\n")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("三模型语音测试", text))
        status.text = "报告已复制"
    }

    override fun onDestroy() {
        recording = false
        super.onDestroy()
    }
}
