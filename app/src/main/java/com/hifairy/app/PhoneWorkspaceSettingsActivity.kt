package com.hifairy.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class PhoneWorkspaceSettingsActivity : Activity() {
    private val store by lazy { PhoneWorkspaceStore(this) }
    private lateinit var status: TextView

    @Deprecated("System folder picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 71 || resultCode != RESULT_OK || data?.data == null) return
        status.text = try {
            store.mount(data.data!!, data.flags)
            "已挂载。目录标识：${DocumentsContract.getTreeDocumentId(data.data!!)}\n请先点击本机探针确认此目录可写，再回到 Agent 会话。"
        } catch (error: Exception) { "挂载失败：${error.message}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 80, 28, 28)
            setBackgroundColor(Color.rgb(8, 8, 22))
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(Color.rgb(226, 221, 239)); setPadding(0, 0, 0, 20)
        }
        root.addView(text("手机工作区", 25f))
        root.addView(text("仅挂载卓易通兼容区内的普通文件夹。“我的设备（鸿蒙）”映射目录和系统敏感目录不可挂载。Agent 可在此目录内列出、读取和修改文本文件，并新建目录；删除仅支持单个文件。请只挂载你愿意让 Agent 操作的目录。", 15f))
        root.addView(Button(this).apply {
            this.text = "手动选择工作区"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }, 71)
            }
        })
        root.addView(Button(this).apply {
            this.text = "运行本机读写探针"
            setOnClickListener {
                status.text = "正在验证…"
                Thread { val result = store.probe(); runOnUiThread { status.text = result } }.start()
            }
        })
        root.addView(Button(this).apply {
            this.text = "解除挂载（保留测试文件）"
            setOnClickListener { store.unmount(); status.text = "已解除挂载。" }
        })
        status = text("当前挂载：${store.mountedUri()?.let { DocumentsContract.getTreeDocumentId(it) } ?: "无"}", 15f)
        root.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
