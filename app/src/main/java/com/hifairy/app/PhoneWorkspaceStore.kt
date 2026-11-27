package com.hifairy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.util.UUID

/** A user selected SAF tree; no raw filesystem path is accepted from the PC. */
internal class PhoneWorkspaceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("phone_workspace", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    fun mountedUri(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)

    fun mount(tree: Uri, flags: Int) {
        val access = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(access == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) { "选择器未授予读写权限" }
        val id = DocumentsContract.getTreeDocumentId(tree).lowercase()
        require(!id.contains("myharmonydevice") && !id.contains("myharmonyosdevice")) { "我的设备（鸿蒙）映射目录不可作为可写工作区" }
        require(!id.endsWith(":") && !id.endsWith(":download") && !id.contains("android/data") && !id.contains("android/obb")) { "系统敏感目录不可挂载" }
        resolver.takePersistableUriPermission(tree, access)
        val old = mountedUri()
        prefs.edit().putString("tree", tree.toString()).apply()
        if (old != null && old != tree) runCatching { resolver.releasePersistableUriPermission(old, access) }
    }

    fun unmount() {
        mountedUri()?.let { tree ->
            runCatching { resolver.releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs.edit().remove("tree").apply()
    }

    fun probe(): String {
        val tree = mountedUri() ?: return "手机工作区未挂载。请在设置中手动挂载兼容区普通测试文件夹。"
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission })
            return "手机工作区持久读写授权已失效，请重新挂载。"
        var stage = "列目录"
        return try {
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val names = mutableListOf<String>()
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && names.size < 20) names.add(cursor.getString(0))
            } ?: error("无法读取目录")
            stage = "验证越界拦截"
            check(runCatching { checkedName("../outside.txt") }.isFailure)
            stage = "创建测试文件"
            val name = checkedName("HiFairy-agent-test-${UUID.randomUUID().toString().take(8)}.txt")
            val file = DocumentsContract.createDocument(resolver, parent, "text/plain", name) ?: error("文件提供者拒绝创建")
            stage = "首次写入与读回"
            resolver.openOutputStream(file, "wt")?.use { it.write("Agent workspace initial".toByteArray()) } ?: error("无法写入")
            check(resolver.openInputStream(file)?.bufferedReader()?.use { it.readText() } == "Agent workspace initial")
            stage = "覆盖写入与读回"
            resolver.openOutputStream(file, "wt")?.use { it.write("Agent workspace updated".toByteArray()) } ?: error("无法覆盖")
            check(resolver.openInputStream(file)?.bufferedReader()?.use { it.readText() } == "Agent workspace updated")
            "手机工作区探针通过：列出 ${names.size} 个文件名（最多 20 个）；越界路径已拒绝；仅创建并覆盖 $name，读回一致。"
        } catch (error: Exception) {
            "手机工作区探针失败，阶段：$stage；${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun checkedName(name: String): String {
        require(name.startsWith("HiFairy-agent-test-") && name.endsWith(".txt") && name.length <= 64 &&
            name.all { it.isLetterOrDigit() || it == '-' || it == '.' }) { "不允许访问工作区以外的路径" }
        return name
    }
}
