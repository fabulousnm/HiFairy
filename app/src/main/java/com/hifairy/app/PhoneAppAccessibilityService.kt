package com.hifairy.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses only the active app's accessibility tree and the user-selected package/action grants. */
class PhoneAppAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var active: PhoneAppAccessibilityService? = null
            private set

        fun run(action: String, packageName: String, target: String = "", value: String = ""): String {
            val service = active ?: return "无障碍服务未启用；请先在设置中启用 HiFairy 应用操作服务。"
            val latch = CountDownLatch(1)
            var result = "应用操作未执行"
            Handler(Looper.getMainLooper()).post {
                try { result = service.perform(action, packageName, target, value) }
                catch (error: Exception) { result = "应用操作失败：${error.javaClass.simpleName}: ${error.message}" }
                finally { latch.countDown() }
            }
            return if (latch.await(5, TimeUnit.SECONDS)) result else "应用操作超时"
        }
    }

    override fun onServiceConnected() { active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    private fun perform(action: String, packageName: String, target: String, value: String): String {
        val grants = PhoneAppControlStore(this)
        if (!grants.allowed(packageName, action)) return "未在设置中允许 $packageName 的 $action 操作"
        if (action == "launch") {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return "无法在卓易通中启动该应用；请确认它可由 Android 兼容环境访问"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return "已请求启动 $packageName；请读取当前界面确认"
        }
        val root = rootInActiveWindow ?: return "当前界面没有可读取的无障碍节点"
        if (root.packageName?.toString() != packageName) return "当前前台应用不是已选的 $packageName"
        if (action == "back") return if (performGlobalAction(GLOBAL_ACTION_BACK)) "已返回上一级" else "系统拒绝返回操作"
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || nodes.size >= 250) return
            nodes += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        if (action == "inspect") {
            val lines = nodes.mapNotNull { node ->
                val label = node.text?.toString()?.ifBlank { null } ?: node.contentDescription?.toString()?.ifBlank { null }
                label?.take(120)?.let { "${if (node.isClickable) "[可点击]" else "[文本]"} $it" }
            }.take(80)
            return "当前 $packageName 界面：\n${lines.joinToString("\n")}".take(12000)
        }
        if (action == "click") {
            require(target.isNotBlank() && target.length <= 120) { "请指定要点击的可见文字" }
            val node = nodes.firstOrNull { it.text?.toString() == target || it.contentDescription?.toString() == target }
                ?: return "未找到文字为「$target」的控件"
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            return if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) "已点击「$target」" else "控件不支持点击"
        }
        if (action == "type") {
            require(value.length <= 2000) { "输入文字过长" }
            val node = nodes.firstOrNull { it.isFocused && it.isEditable }
                ?: return "当前前台应用没有已聚焦的可编辑输入框"
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "已向当前输入框填写文字" else "输入框拒绝设置文本"
        }
        return "不支持的应用操作"
    }
}
