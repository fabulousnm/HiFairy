package com.hifairy.app

import org.json.JSONObject

/** Applies DSH assistant-stream deltas before the final assistant/message event. */
internal class AgentStreamReducer {
    private val blockKinds = mutableMapOf<Int, String>()
    var text = ""
        private set
    var reasoning = ""
        private set

    fun apply(frame: JSONObject): Boolean {
        if (frame.optString("type") != "assistant-stream") return false
        val stream = frame.optJSONObject("frame") ?: return false
        if (stream.optString("type") == "start") {
            blockKinds.clear()
            text = ""
            reasoning = ""
            return true
        }
        val chunk = stream.optJSONObject("chunk") ?: return false
        val index = chunk.optInt("index", -1)
        when (chunk.optString("type")) {
            "block-start" -> blockKinds[index] = chunk.optString("blockType")
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (blockKinds[index] == "reasoning") reasoning += delta else text += delta
                return delta.isNotEmpty()
            }
        }
        return false
    }
}
