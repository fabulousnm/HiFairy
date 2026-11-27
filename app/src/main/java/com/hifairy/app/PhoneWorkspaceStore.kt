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
            var count = 0
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) count++
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
            "手机工作区探针通过：目录共有 $count 个条目；越界路径已拒绝；仅创建并覆盖 $name，读回一致。可调用 hifairy_phone_workspace_files 分页查看全部文件名。"
        } catch (error: Exception) {
            "手机工作区探针失败，阶段：$stage；${error.javaClass.simpleName}: ${error.message}"
        }
    }

    fun listPage(offset: Int): String {
        val tree = authorizedTree() ?: return "手机工作区未挂载或授权已失效。"
        require(offset >= 0 && offset <= 1000000) { "无效分页位置" }
        val id = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val page = mutableListOf<String>()
        var count = 0
        var pageChars = 0
        var pageFull = false
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (count >= offset && page.size < 80 && !pageFull) {
                    val name = cursor.getString(0)
                    if (page.isEmpty() || pageChars + name.length + 1 <= 12000) {
                        page.add(name)
                        pageChars += name.length + 1
                    } else pageFull = true
                }
                count++
            }
        } ?: error("无法列目录")
        val next = if (offset + page.size < count) offset + page.size else null
        return "手机工作区文件名：总数 $count；本页从 $offset 开始，共 ${page.size} 项。\n${page.joinToString("\n")}\n${if (next == null) "已到末页" else "下一页 offset=$next"}"
    }

    fun testWrite(name: String, content: String): String {
        val tree = authorizedTree() ?: return "手机工作区未挂载或授权已失效。"
        checkedName(name)
        require(content.length <= 2000) { "测试文本过长" }
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        var documentId: String? = null
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(0) == name) {
                require(cursor.getString(2) == "text/plain") { "目标不是测试文本文件" }
                documentId = cursor.getString(1)
                break
            }
        } ?: error("无法列目录")
        val id = documentId ?: return "指定测试文件不存在：$name。请先运行固定探针创建文件。"
        val file = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val before = resolver.openInputStream(file)?.use { stream ->
            val buffer = ByteArray(8193)
            var used = 0
            while (used < buffer.size) {
                val count = stream.read(buffer, used, buffer.size - used)
                if (count < 0) break
                used += count
            }
            require(used <= 8192) { "测试文件超过 8 KiB，拒绝覆盖" }
            String(buffer, 0, used, Charsets.UTF_8)
        } ?: error("无法读取测试文件")
        require(before.startsWith("Agent workspace ") || before.startsWith("HiFairy agent test ")) {
            "测试文件内容不符合本应用标记，拒绝覆盖"
        }
        val value = "HiFairy agent test $content"
        resolver.openOutputStream(file, "wt")?.use { it.write(value.toByteArray(Charsets.UTF_8)) } ?: error("无法覆盖")
        check(resolver.openInputStream(file)?.bufferedReader()?.use { it.readText() } == value) { "覆盖后读回不一致" }
        return "指定文件覆盖并读回成功：$name；原内容长度 ${before.length}，新内容长度 ${value.length}。"
    }

    private fun authorizedTree(): Uri? = mountedUri()?.takeIf { tree ->
        resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }

    private fun checkedName(name: String): String {
        require(name.startsWith("HiFairy-agent-test-") && name.endsWith(".txt") && name.length <= 64 &&
            name.all { it.isLetterOrDigit() || it == '-' || it == '.' }) { "不允许访问工作区以外的路径" }
        return name
    }
}
