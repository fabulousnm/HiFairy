package com.hifairy.ttslab

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SynthesisMetrics(val firstChunkMs: Long?, val inferenceMs: Long, val audioMs: Long, val sampleRate: Int) {
    val rtf: Double get() = if (audioMs > 0) inferenceMs.toDouble() / audioMs else 0.0
}
data class SynthesisOutput(val metrics: SynthesisMetrics, val samples: FloatArray)

class ZipVoiceBenchmark(private val assets: AssetManager) {
    private var engine: OfflineTts? = null
    private val referenceText = "主人，中午好。饭可以不吃，进度不能停。"
    private val reference by lazy { readPcm16Wave("zipvoice/fairy-reference.wav") }

    fun load(): Long {
        if (engine != null) return 0L
        val started = SystemClock.elapsedRealtime()
        val model = OfflineTtsZipVoiceModelConfig(
            tokens = "zipvoice/tokens.txt",
            encoder = "zipvoice/encoder.int8.onnx",
            decoder = "zipvoice/decoder.int8.onnx",
            vocoder = "zipvoice/vocos_24khz.onnx",
            lexicon = "zipvoice/lexicon.txt"
        )
        engine = OfflineTts(assets, OfflineTtsConfig(model = OfflineTtsModelConfig(zipvoice = model, numThreads = 2)))
        return SystemClock.elapsedRealtime() - started
    }

    fun synthesize(text: String): SynthesisOutput {
        val tts = requireNotNull(engine) { "模型尚未加载" }
        val (referenceSamples, referenceRate) = reference
        val options = GenerationConfig(
            referenceAudio = referenceSamples,
            referenceSampleRate = referenceRate,
            referenceText = referenceText,
            numSteps = 4
        )
        val start = SystemClock.elapsedRealtime()
        // This sherpa JNI build invokes the callback with a Float, while the
        // bundled Kotlin API expects FloatArray. The mismatch aborts ART.
        val result = tts.generateWithConfig(text, options)
        val elapsed = SystemClock.elapsedRealtime() - start
        require(result.sampleRate > 0 && result.samples.isNotEmpty()) { "模型未生成音频" }
        return SynthesisOutput(SynthesisMetrics(null, elapsed, result.samples.size * 1000L / result.sampleRate, result.sampleRate), result.samples)
    }

    fun close() { engine?.release(); engine = null }

    private fun readPcm16Wave(path: String): Pair<FloatArray, Int> {
        val bytes = assets.open(path).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "参考音频不是 WAV" }
        var rate = 0
        var channels = 0
        var bits = 0
        var format = 0
        var audio: FloatArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            require(size >= 0 && body + size <= bytes.size) { "参考音频数据损坏" }
            when (String(bytes, offset, 4)) {
                "fmt " -> {
                    format = buffer.getShort(body).toInt()
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    audio = FloatArray(size / 2) { index -> buffer.getShort(body + index * 2) / 32768f }
                }
            }
            offset = body + size + (size and 1)
        }
        require(format == 1 && channels == 1 && bits == 16 && rate == 24000 && audio != null) { "参考音频必须是 24kHz 单声道 PCM16" }
        return audio to rate
    }
}
