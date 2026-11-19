package com.hifairy.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Streams Fairy PCM over one reusable WebSocket connection. Callbacks may run off the UI thread. */
class FairySpeechPlayer(private val onState: (Long, Boolean, String?) -> Unit) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val pcmQueue = LinkedBlockingQueue<ByteArray>()
    private val endOfAudio = ByteArray(0)
    private var socket: WebSocket? = null
    private var connected = false
    private var endpoint = ""
    private var authToken = ""
    private var segments = ArrayDeque<String>()
    private var activeId: Long? = null
    private var sequence = 0L
    private var sampleRate = 32000
    private var track: AudioTrack? = null
    private var playbackStarted = false

    fun play(url: String, token: String, markdown: String, id: Long): Boolean {
        val parts = speechSegments(markdown)
        if (parts.isEmpty()) { onState(id, false, "回答没有可播放的文字"); return false }
        if (!url.startsWith("wss://") || token.isBlank()) { onState(id, false, "请先在设置中填写 Fairy 语音服务地址和令牌"); return false }
        synchronized(lock) {
            if (activeId != null) stopLocked()
            sequence++
            activeId = id
            segments = parts
            pcmQueue.clear()
            playbackStarted = false
            onState(id, true, null)
            if (socket != null && connected && endpoint == url && authToken == token) {
                sendNextLocked()
            } else {
                socket?.cancel()
                connected = false
                endpoint = url
                authToken = token
                socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
            }
        }
        return true
    }

    fun stop() = synchronized(lock) { stopLocked() }

    fun close() {
        synchronized(lock) { stopLocked() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun stopLocked() {
        val stoppedId = activeId
        sequence++
        activeId = null
        segments.clear()
        pcmQueue.clear()
        pcmQueue.offer(endOfAudio)
        socket?.cancel()
        socket = null
        connected = false
        try { track?.pause(); track?.flush() } catch (_: Exception) { }
        track = null
        if (stoppedId != null) onState(stoppedId, false, null)
    }

    private fun sendNextLocked() {
        if (segments.isEmpty()) {
            pcmQueue.offer(endOfAudio)
            return
        }
        val body = JSONObject().put("token", authToken).put("text", segments.removeFirst())
        socket?.send(body.toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (webSocket !== socket) return@synchronized
            connected = true
            sendNextLocked()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (webSocket !== socket || activeId == null) return@synchronized
            val data = try { JSONObject(text) } catch (_: Exception) { return@synchronized }
            when (data.optString("type")) {
                "audio" -> sampleRate = data.optInt("sample_rate", 32000)
                "done" -> sendNextLocked()
                "error" -> failLocked(data.optString("message", "语音生成失败"))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
            if (webSocket !== socket || activeId == null) return@synchronized
            if (!playbackStarted) startPlaybackLocked()
            pcmQueue.offer(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (webSocket === socket) failLocked("语音连接失败：${t.message ?: "网络异常"}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (webSocket === socket) failLocked("语音连接已关闭：$code")
        }
    }

    private fun failLocked(message: String) {
        val failedId = activeId
        stopLocked()
        if (failedId != null) onState(failedId, false, message)
    }

    private fun startPlaybackLocked() {
        playbackStarted = true
        val playbackSequence = sequence
        val rate = sampleRate
        Thread {
            var localTrack: AudioTrack? = null
            try {
                val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0) { "音频设备不支持 ${rate}Hz PCM" }
                localTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, rate / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                synchronized(lock) {
                    if (playbackSequence != sequence) { localTrack.release(); return@Thread }
                    track = localTrack
                }
                localTrack.play()
                var frames = 0L
                while (true) {
                    val pcm = pcmQueue.take()
                    if (playbackSequence != sequence || pcm === endOfAudio) break
                    var offset = 0
                    while (offset < pcm.size && playbackSequence == sequence) {
                        val written = localTrack.write(pcm, offset, pcm.size - offset)
                        if (written <= 0) error("音频播放写入失败：$written")
                        offset += written
                    }
                    frames += pcm.size / 2
                }
                val deadline = SystemClock.elapsedRealtime() + frames * 1000 / rate + 2000
                while (playbackSequence == sequence && localTrack.playbackHeadPosition.toLong() < frames && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
                synchronized(lock) {
                    if (playbackSequence == sequence) {
                        val finishedId = activeId
                        activeId = null
                        track = null
                        if (finishedId != null) onState(finishedId, false, null)
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) { if (playbackSequence == sequence) failLocked("播放失败：${error.message}") }
            } finally {
                try { localTrack?.stop(); localTrack?.release() } catch (_: Exception) { }
            }
        }.start()
    }

    private fun speechSegments(markdown: String): ArrayDeque<String> {
        val plain = markdown
            .replace("```", "")
            .replace(Regex("!?(?:\\[([^]]*)])\\([^)]*\\)"), "$1")
            .replace(Regex("[`*_#>~|]"), "")
            .replace(Regex("\\s+"), " ").trim()
        val result = ArrayDeque<String>()
        val buffer = StringBuilder()
        for (piece in plain.split(Regex("(?<=[。！？；.!?])"))) {
            if (buffer.length + piece.length > 150 && buffer.isNotEmpty()) {
                result.add(buffer.toString().trim())
                buffer.clear()
            }
            if (piece.length > 150) {
                var offset = 0
                while (offset < piece.length) {
                    val count = minOf(150, piece.codePointCount(offset, piece.length))
                    val end = piece.offsetByCodePoints(offset, count)
                    result.add(piece.substring(offset, end).trim())
                    offset = end
                }
            } else buffer.append(piece)
        }
        if (buffer.isNotEmpty()) result.add(buffer.toString().trim())
        result.removeAll { it.isBlank() }
        return result
    }
}
