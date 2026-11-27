package com.hifairy.workspacelab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.UUID

private const val PREFS = "workspace_permission_test"
private const val TREE = "tree_uri"
private const val FILE = "test_file_uri"
private const val PICK_FOLDER = 51

class WorkspaceLabActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var testButton: Button
    @Deprecated("System folder picker result is required for this isolated test app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FOLDER) return
        val tree = data?.data
        if (resultCode != RESULT_OK || tree == null) {
            show("未选择文件夹；现有挂载未改变。")
            return
        }
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                throw IllegalStateException("文件选择器没有同时授予读取与写入")
            contentResolver.takePersistableUriPermission(tree, flags)
            val old = prefs.getString(TREE, null)
            prefs.edit().putString(TREE, tree.toString()).remove(FILE).apply()
            if (old != null && old != tree.toString()) {
                try { contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) } catch (_: Exception) { }
            }
            show("已挂载文件夹。授权：读取 ✓，写入 ✓，持久化 ✓。\n点击测试按钮，在该文件夹内创建并覆盖写入测试文件。")
        } catch (error: Exception) {
            show("挂载失败：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 42, 30, 30)
            setBackgroundColor(Color.rgb(7, 8, 24))
        }
        fun label(value: String, size: Float, color: Int): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, 0, 0, 22)
        }
        root.addView(label("H.D.D Ⅲ // 手机工作区测试", 22f, Color.rgb(230, 240, 255)))
        root.addView(label("请用系统文件选择器手动选择一个专门用于测试的文件夹。测试只会创建并覆盖本应用生成的文本文件，不会修改其他文件。", 15f, Color.rgb(170, 185, 222)))
        root.addView(Button(this).apply {
            text = "手动挂载测试文件夹"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }, PICK_FOLDER)
            }
        })
        testButton = Button(this).apply {
            text = "创建、覆盖写入并读回校验"
            setOnClickListener { testMountedFolder() }
        }
        root.addView(testButton)
        root.addView(Button(this).apply {
            text = "检查重启后授权"
            setOnClickListener { checkPersistedGrant() }
        })
        root.addView(Button(this).apply {
            text = "解除挂载（保留测试文件）"
            setOnClickListener { unmount() }
        })
        status = label("", 14f, Color.rgb(177, 209, 255))
        val scroll = ScrollView(this).apply { addView(status) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        checkPersistedGrant()
    }

    private fun show(value: String) { status.text = value }

    private fun grant(tree: Uri): Boolean = contentResolver.persistedUriPermissions.any {
        it.uri == tree && it.isReadPermission && it.isWritePermission
    }

    private fun checkPersistedGrant() {
        val tree = prefs.getString(TREE, null)?.let(Uri::parse)
        show(if (tree == null) "尚未挂载文件夹。" else
            "已保存挂载：${tree.authority}\n持久读取与写入授权：${if (grant(tree)) "有效 ✓" else "无效 ✕"}\n测试文件：${prefs.getString(FILE, null)?.substringAfterLast('/') ?: "尚未创建"}")
    }

    private fun testMountedFolder() {
        val tree = prefs.getString(TREE, null)?.let(Uri::parse)
        if (tree == null) { show("请先手动挂载文件夹。"); return }
        if (!grant(tree)) { show("持久读写授权不存在。请重新挂载该文件夹。"); return }
        testButton.isEnabled = false
        show("正在测试挂载目录内的文件创建、覆盖和读回…")
        Thread {
            val result = try {
                val existing = prefs.getString(FILE, null)?.let(Uri::parse)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val file = existing ?: DocumentsContract.createDocument(contentResolver, parent, "text/plain",
                    "HiFairy-test-${UUID.randomUUID().toString().take(8)}.txt")
                    ?: throw IllegalStateException("文件提供者拒绝创建测试文件")
                prefs.edit().putString(FILE, file.toString()).apply()
                val first = "HiFairy test initial ${LocalDateTime.now()}"
                val second = "HiFairy test overwritten ${LocalDateTime.now()}"
                write(file, first)
                val firstRead = read(file)
                if (firstRead != first) throw IllegalStateException("首次写入后读回不一致：$firstRead")
                write(file, second)
                val secondRead = read(file)
                if (secondRead != second) throw IllegalStateException("覆盖写入后读回不一致：$secondRead")
                "测试通过 ✓\n文件：${file.lastPathSegment}\n持久授权：有效 ✓\n创建：成功 ✓\n首次写入与读回：一致 ✓\n覆盖写入与读回：一致 ✓\n请到鸿蒙文件管理中确认文件内容，再关闭并重开本测试包：先点击‘检查重启后授权’，再点击测试按钮，验证重启后仍可覆盖写入。"
            } catch (error: Exception) {
                "测试失败：${error.javaClass.simpleName}: ${error.message}\n请截图此页；若在 MyHarmonyDevice 文件夹中失败，也请注明。"
            }
            runOnUiThread { testButton.isEnabled = true; show(result) }
        }.start()
    }

    private fun write(file: Uri, value: String) {
        contentResolver.openOutputStream(file, "wt")?.use { it.write(value.toByteArray(StandardCharsets.UTF_8)) }
            ?: throw IllegalStateException("文件提供者没有打开写入流")
    }

    private fun read(file: Uri): String = contentResolver.openInputStream(file)?.use {
        String(it.readBytes(), StandardCharsets.UTF_8)
    } ?: throw IllegalStateException("文件提供者没有打开读取流")

    private fun unmount() {
        prefs.getString(TREE, null)?.let { saved ->
            try {
                contentResolver.releasePersistableUriPermission(Uri.parse(saved),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        prefs.edit().remove(TREE).remove(FILE).apply()
        show("挂载已解除。测试文件保留在原文件夹，需由你自行决定是否删除。")
    }
}
