package com.hifairy.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PendingAttachment(val uri: Uri, val name: String, val state: String = "上传中", val fileId: String = "")

class DeepSeekFileUploader(private val resolver: ContentResolver) {
    fun name(uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.take(120)?.ifBlank { null } ?: "附件"

    fun upload(uri: Uri, name: String, apiKey: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        val purpose = when (extension) {
            "jpg", "jpeg", "png", "gif", "webp", "txt", "md", "csv", "json", "xlsx", "xls", "epub", "mobi", "kt", "java", "py", "js", "ts", "html", "css", "xml" -> "user_data"
            "pdf", "doc", "docx", "ppt", "pptx" -> "rich_layout_recognition"
            else -> throw IllegalArgumentException("不支持的附件格式：$extension")
        }
        val limit = when {
            extension in setOf("jpg", "jpeg", "png", "gif", "webp") -> 64L * 1024 * 1024
            purpose == "rich_layout_recognition" -> 100L * 1024 * 1024
            else -> 32L * 1024 * 1024
        }
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > limit) throw IllegalArgumentException("附件超过大小限制")
        }
        val boundary = "HiFairy${System.nanoTime()}"
        val connection = (URL("https://api.deepseek.com/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 180000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { output ->
                fun write(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                write("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n$purpose\r\n")
                val safeName = name.replace(Regex("[\\r\\n\\\"]"), "_")
                write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                resolver.openInputStream(uri)?.use { input -> input.copyTo(output, 64 * 1024) }
                    ?: throw IllegalStateException("无法读取附件")
                write("\r\n--$boundary--\r\n")
            }
            val response = if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() }
                else throw IllegalStateException("上传失败 HTTP ${connection.responseCode}：${connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(180).orEmpty()}")
            return JSONObject(response).getString("id").also { require(it.startsWith("file-api-")) { "上传响应缺少文件 ID" } }
        } finally { connection.disconnect() }
    }
}
