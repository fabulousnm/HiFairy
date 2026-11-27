package com.hifairy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal const val AGENT_CHANNEL = "hifairy_agent_progress"
internal const val AGENT_NOTIFICATION = 4107

/** Keeps only the active DSH conversation subscribed while a task is running. */
class AgentMonitorService : Service() {
    companion object {
        private const val SESSION = "session"
        private const val STOP = "stop"

        fun monitor(context: Context, session: String) {
            if (session.isBlank()) return
            context.startForegroundService(Intent(context, AgentMonitorService::class.java).putExtra(SESSION, session))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentMonitorService::class.java))
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(AGENT_NOTIFICATION)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var followJob: Job? = null
    private var api: AgentConnection? = null
    private var session = ""
    private var latest = ""
    private var lastUpdate = 0L
    @Volatile private var pending: AgentApproval? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(SESSION).orEmpty()
        if (intent?.getBooleanExtra(STOP, false) == true || requested.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (session == requested && followJob?.isActive == true) return START_NOT_STICKY
        followJob?.cancel()
        api?.close()
        session = requested
        latest = ""
        pending = null
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(AGENT_CHANNEL, "YOUKAI 任务进展", NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(AGENT_NOTIFICATION, buildNotification("正在连接电脑任务", null))
        followJob = scope.launch {
            val connection = AgentConnection(SecureSettings(this@AgentMonitorService))
            api = connection
            val reducer = AgentStreamReducer()
            try {
                while (isActive && session == requested) {
                    try {
                        connection.follow(requested) { frame ->
                            if (session != requested) return@follow
                            if (reducer.apply(frame)) {
                                latest = reducer.text
                                if (latest.isNotBlank()) publish(latest, pending)
                            }
                            if (frame.optString("type") == "snapshot") scope.launch {
                                try {
                                    pending = connection.approvals(requested).firstOrNull()
                                    if (pending != null) publish(latest.ifBlank { "电脑任务正在运行" }, pending, force = true)
                                } catch (_: Exception) { }
                            }
                            val event = frame.optJSONObject("event")?.optString("type")
                            when (event) {
                                "approval/asked", "approval/decided" -> scope.launch {
                                    try {
                                        pending = connection.approvals(requested).firstOrNull()
                                        publish(latest.ifBlank { "电脑任务正在运行" }, pending, force = true)
                                    } catch (_: Exception) { }
                                }
                                "tool/call" -> if (latest.isBlank()) publish("正在调用工具", pending)
                                "turn/end" -> {
                                    publish(latest.ifBlank { "任务已完成" }, null, force = true, complete = true)
                                    stopForeground(STOP_FOREGROUND_DETACH)
                                    stopSelf()
                                }
                            }
                        }
                    } catch (_: Exception) { }
                    if (isActive && session == requested) delay(1500)
                }
            } finally {
                connection.close()
                if (api === connection) api = null
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(text: String, approval: AgentApproval?, force: Boolean = false, complete: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdate < 900) return
        lastUpdate = now
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(AGENT_NOTIFICATION, buildNotification(text, approval, complete))
    }

    private fun buildNotification(text: String, approval: AgentApproval?, complete: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, AgentActivity::class.java).apply {
            putExtra("sessionId", session)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val preview = approval?.reason?.ifBlank { "${approval.toolName} 请求审批" } ?: text.takeLast(250)
        val builder = Notification.Builder(this, AGENT_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(when { approval != null -> "YOUKAI · 等待审批"; complete -> "YOUKAI · 任务已完成"; else -> "YOUKAI · 工作进展" })
            .setContentText(preview).setStyle(Notification.BigTextStyle().bigText(preview))
            .setContentIntent(open).setOnlyAlertOnce(approval == null).setOngoing(!complete)
        if (approval != null) {
            for ((allow, label) in listOf(false to "拒绝", true to "允许一次")) {
                val action = Intent(this, AgentApprovalReceiver::class.java).apply {
                    putExtra("session", session); putExtra("approval", approval.id); putExtra("allow", allow)
                }
                val pendingIntent = PendingIntent.getBroadcast(this, approval.id.hashCode() * 2 + if (allow) 1 else 0,
                    action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(Notification.Action.Builder(0, label, pendingIntent).build())
            }
        }
        return builder.build()
    }

    override fun onDestroy() {
        followJob?.cancel()
        api?.close()
        scope.cancel()
        super.onDestroy()
    }
}
