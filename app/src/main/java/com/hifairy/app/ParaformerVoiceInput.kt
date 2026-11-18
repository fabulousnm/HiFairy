package com.hifairy.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlin.concurrent.thread
import kotlin.math.sqrt

class ParaformerVoiceInput(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onTranscript: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var preparing = false
    @Volatile private var capturing = false
    @Volatile private var workerRunning = false
    @Volatile private var discard = false
    @Volatile private var closed = false

    val ready: Boolean get() = recognizer != null
    val recording: Boolean get() = capturing

    fun prepare() {
        if (closed || preparing || ready) return
        preparing = true
        onStatus("正在准备语音模型…")
        thread(name = "paraformer-prepare") {
            try {
                val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig("model.int8.onnx"),
                    tokens = "tokens.txt", modelType = "paraformer", numThreads = 2
                ))
                val loaded = OfflineRecognizer(context.assets, config)
                if (closed) loaded.release() else {
                    recognizer = loaded
                    main.post { onStatus("按住说话") }
                }
            } catch (error: Throwable) {
                main.post { onStatus("语音模型加载失败：${error.message ?: error.javaClass.simpleName}") }
            } finally { preparing = false }
        }
    }

    fun start(): Boolean {
        if (!ready || closed || workerRunning || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        capturing = true
        workerRunning = true
        discard = false
        thread(name = "paraformer-record") {
            var recorder: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 3200))
                recorder = audio
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用" }
                val chunks = ArrayList<ShortArray>()
                val started = SystemClock.elapsedRealtime()
                audio.startRecording()
                main.post { onStatus("正在聆听…") }
                while (capturing && SystemClock.elapsedRealtime() - started < 30000) {
                    val buffer = ShortArray(1600)
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        chunks.add(buffer.copyOf(count))
                        var power = 0.0
                        for (index in 0 until count) power += buffer[index].toDouble() * buffer[index]
                        val level = (sqrt(power / count) / 9000.0).toFloat().coerceIn(0f, 1f)
                        main.post { onLevel(level) }
                    }
                }
                audio.stop()
                if (discard || closed) { main.post { onStatus("已取消语音输入") }; return@thread }
                val total = chunks.sumOf { it.size }
                if (total < 3200) { main.post { onStatus("录音太短，请重试") }; return@thread }
                main.post { onStatus("正在识别…") }
                val samples = FloatArray(total)
                var offset = 0
                chunks.forEach { chunk -> chunk.forEach { samples[offset++] = it / 32768f } }
                val engine = recognizer ?: return@thread
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    engine.decode(stream)
                    val transcript = engine.getResult(stream).text.trim()
                    main.post {
                        if (transcript.isBlank()) onStatus("没有识别到语音，请重试")
                        else { onStatus(""); onTranscript(transcript) }
                    }
                } finally { stream.release() }
            } catch (error: Throwable) {
                main.post { onStatus("语音识别失败：${error.message ?: error.javaClass.simpleName}") }
            } finally {
                try { recorder?.stop() } catch (_: Exception) { }
                recorder?.release()
                capturing = false
                workerRunning = false
                main.post { onLevel(0f) }
                if (closed) { recognizer?.release(); recognizer = null }
            }
        }
        return true
    }

    fun finish(cancel: Boolean) {
        if (cancel) discard = true
        capturing = false
    }

    fun close() {
        closed = true
        discard = true
        capturing = false
        if (!workerRunning) { recognizer?.release(); recognizer = null }
    }
}
