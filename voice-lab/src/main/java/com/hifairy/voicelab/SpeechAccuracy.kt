package com.hifairy.voicelab

object SpeechAccuracy {
    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    fun characterErrorRate(reference: String, hypothesis: String): Double {
        val expected = normalize(reference)
        val actual = normalize(hypothesis)
        if (expected.isEmpty()) return if (actual.isEmpty()) 0.0 else 1.0
        var previous = IntArray(actual.length + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(actual.length + 1)
            current[0] = i + 1
            for (j in actual.indices) {
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    previous[j] + if (expected[i] == actual[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[actual.length].toDouble() / expected.length
    }
}
