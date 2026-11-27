package com.hifairy.app

import android.content.Context

internal class PhoneAppControlStore(context: Context) {
    private val context = context
    private val prefs = context.getSharedPreferences("phone_app_control", Context.MODE_PRIVATE)

    fun allowed(packageName: String, action: String): Boolean =
        packageName.isNotBlank() && action in prefs.getStringSet("app:$packageName", emptySet()).orEmpty()

    fun actions(packageName: String): Set<String> = prefs.getStringSet("app:$packageName", emptySet()).orEmpty()

    fun setAllowed(packageName: String, action: String, enabled: Boolean) {
        require(action in ACTIONS && packageName.matches(Regex("[A-Za-z0-9_.]+")))
        val next = actions(packageName).toMutableSet()
        if (enabled) next += action else next -= action
        prefs.edit().putStringSet("app:$packageName", next).apply()
    }

    fun catalog(): String {
        val items = prefs.all.keys.filter { it.startsWith("app:") }.mapNotNull { key ->
            val pkg = key.removePrefix("app:")
            val actions = actions(pkg)
            if (actions.isEmpty()) null else {
                val label = runCatching { context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                "$label ($pkg)：${actions.sorted().joinToString(", ")}"
            }
        }
        return if (items.isEmpty()) "尚未在 HiFairy 设置中允许任何手机应用或动作。" else
            "已允许的手机应用与动作：\n${items.joinToString("\n")}"
    }

    companion object {
        val ACTIONS = setOf("launch", "inspect", "click", "type", "back")
    }
}
