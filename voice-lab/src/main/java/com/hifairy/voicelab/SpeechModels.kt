package com.hifairy.voicelab

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig

enum class SpeechModel(val label: String, val detail: String) {
    ZIPFORMER("Zipformer CTC", "25MB · 真流式模型"),
    PARAFORMER("Paraformer small", "79MB · 短句完整识别"),
    SENSEVOICE("SenseVoiceSmall", "239MB · 短句完整识别")
}

data class SpeechResult(val text: String, val loadMillis: Long, val decodeMillis: Long)

object SpeechModels {
    fun decode(assets: AssetManager, model: SpeechModel, samples: FloatArray): SpeechResult {
        val start = SystemClock.elapsedRealtime()
        return when (model) {
            SpeechModel.ZIPFORMER -> {
                val config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        zipformer2Ctc = OnlineZipformer2CtcModelConfig("models/zipformer/model.int8.onnx"),
                        tokens = "models/zipformer/tokens.txt",
                        numThreads = 2
                    ), enableEndpoint = false
                )
                val recognizer = OnlineRecognizer(assets, config)
                try {
                    val loaded = SystemClock.elapsedRealtime()
                    val stream = recognizer.createStream()
                    try {
                        for (offset in samples.indices step 1600) {
                            val end = minOf(offset + 1600, samples.size)
                            stream.acceptWaveform(samples.copyOfRange(offset, end), 16000)
                            while (recognizer.isReady(stream)) recognizer.decode(stream)
                        }
                        stream.inputFinished()
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        SpeechResult(recognizer.getResult(stream).text, loaded - start, SystemClock.elapsedRealtime() - loaded)
                    } finally { stream.release() }
                } finally { recognizer.release() }
            }
            SpeechModel.PARAFORMER, SpeechModel.SENSEVOICE -> {
                val modelConfig = if (model == SpeechModel.PARAFORMER) OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig("models/paraformer/model.int8.onnx"),
                    tokens = "models/paraformer/tokens.txt", modelType = "paraformer", numThreads = 2
                ) else OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig("models/sensevoice/model.int8.onnx", "zh", true),
                    tokens = "models/sensevoice/tokens.txt", numThreads = 2
                )
                val recognizer = OfflineRecognizer(assets, OfflineRecognizerConfig(modelConfig = modelConfig))
                try {
                    val loaded = SystemClock.elapsedRealtime()
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, 16000)
                        recognizer.decode(stream)
                        SpeechResult(recognizer.getResult(stream).text, loaded - start, SystemClock.elapsedRealtime() - loaded)
                    } finally { stream.release() }
                } finally { recognizer.release() }
            }
        }
    }
}
