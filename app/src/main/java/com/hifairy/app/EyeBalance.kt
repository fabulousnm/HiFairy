package com.hifairy.app

import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class AccountBalance(val amount: BigDecimal?, val currency: String?, val error: String? = null)

object DeepSeekBalance {
    fun fetch(apiKey: String): AccountBalance {
        if (apiKey.isBlank()) return AccountBalance(null, null, "请先在设置中配置 API Key")
        val connection = (URL("https://api.deepseek.com/user/balance").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return AccountBalance(null, null, "余额读取失败（HTTP $code）")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val infos = JSONObject(body).optJSONArray("balance_infos")
                ?: return AccountBalance(null, null, "账户未返回余额")
            val entries = (0 until infos.length()).mapNotNull { index ->
                val item = infos.optJSONObject(index) ?: return@mapNotNull null
                val currency = item.optString("currency")
                val amount = item.optString("total_balance").toBigDecimalOrNull() ?: return@mapNotNull null
                currency to amount
            }
            val selected = entries.filter { it.first == "CNY" }.ifEmpty {
                entries.firstOrNull()?.let { first -> entries.filter { it.first == first.first } } ?: emptyList()
            }
            if (selected.isEmpty()) AccountBalance(null, null, "账户未返回可用余额")
            else AccountBalance(selected.fold(BigDecimal.ZERO) { sum, entry -> sum + entry.second }, selected.first().first)
        } catch (_: Exception) {
            AccountBalance(null, null, "余额读取失败，请检查网络")
        } finally {
            connection.disconnect()
        }
    }
}

data class PeakWindow(val peak: Boolean, val nextAt: Instant)

/** DeepSeek's Beijing-time 2026 tariff calendar; unknown years return null instead of guessing holidays. */
object PeakTariff {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val holidays = listOf(
        "2026-01-01" to "2026-01-03",
        "2026-02-15" to "2026-02-23",
        "2026-04-04" to "2026-04-06",
        "2026-05-01" to "2026-05-05",
        "2026-06-19" to "2026-06-21",
        "2026-09-25" to "2026-09-27",
        "2026-10-01" to "2026-10-07"
    ).flatMap { (start, end) ->
        val first = LocalDate.parse(start)
        val last = LocalDate.parse(end)
        generateSequence(first) { day -> day.plusDays(1).takeIf { it <= last } }.toList()
    }.toSet()

    fun isPeak(at: ZonedDateTime): Boolean {
        val bj = at.withZoneSameInstant(zone)
        if (bj.year != 2026 || bj.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) || bj.toLocalDate() in holidays) return false
        val time = bj.toLocalTime()
        return (time >= LocalTime.of(9, 0) && time < LocalTime.NOON) ||
            (time >= LocalTime.of(14, 0) && time < LocalTime.of(18, 0))
    }

    fun current(now: Instant = Instant.now()): PeakWindow? {
        val bj = now.atZone(zone)
        if (bj.year != 2026) return null
        val currentPeak = isPeak(bj)
        for (offset in 0..14) {
            val day = bj.toLocalDate().plusDays(offset.toLong())
            if (day.year != 2026) return null
            for (time in listOf(LocalTime.of(9, 0), LocalTime.NOON, LocalTime.of(14, 0), LocalTime.of(18, 0))) {
                val candidate = day.atTime(time).atZone(zone)
                if (candidate.toInstant() > now && isPeak(candidate) != currentPeak) {
                    return PeakWindow(currentPeak, candidate.toInstant())
                }
            }
        }
        return null
    }
}
