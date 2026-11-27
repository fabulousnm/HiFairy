package com.hifairy.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class PhoneAppSettingsActivity : Activity() {
    private val grants by lazy { PhoneAppControlStore(this) }
    private lateinit var status: TextView

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = if (PhoneAppAccessibilityService.active != null)
            "无障碍服务已连接。仅允许下方逐项选择的应用与动作。" else
            "无障碍服务未连接。请先手动开启，再选择允许的应用与动作。卓易通是否支持跨应用控制需真机验证。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 70, 28, 28)
            setBackgroundColor(Color.rgb(15, 12, 24))
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(Color.rgb(234, 218, 231)); setPadding(0, 8, 0, 14)
        }
        root.addView(text("手机应用操作", 25f))
        status = text("", 14f)
        root.addView(status)
        root.addView(Button(this).apply {
            this.text = "打开系统无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(text("只列出 Android 兼容环境中可见、可启动的应用。逐项允许后，Agent 才能对该应用启动、读取界面、点击、输入或返回。不会自动点击坐标或操作未选应用。", 14f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString() }
        val labels = listOf("launch" to "启动", "inspect" to "读取界面", "click" to "点击控件",
            "type" to "填写输入框", "back" to "返回")
        for (app in apps) {
            val pkg = app.activityInfo.packageName
            list.addView(text("${app.loadLabel(packageManager)}  ·  $pkg", 16f))
            labels.forEach { (action, label) ->
                list.addView(Switch(this).apply {
                    this.text = label
                    setTextColor(Color.rgb(219, 197, 214))
                    isChecked = grants.allowed(pkg, action)
                    setOnCheckedChangeListener { _, checked -> grants.setAllowed(pkg, action, checked) }
                })
            }
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
