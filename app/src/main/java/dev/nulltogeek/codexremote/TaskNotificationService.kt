package dev.nulltogeek.codexremote

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** A separate read-only subscription. It never sends prompts or dispatches queues. */
class TaskNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpc = RpcClient()
    private var monitor: Job? = null
    private lateinit var store: LocalStore

    override fun onCreate() { super.onCreate(); store = LocalStore(this); createChannels(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { store.taskNotifications(false); stopSelf(); return START_NOT_STICKY }
        if (!store.taskNotifications || !canNotify(this) || store.session().isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 0, Intent(this, TaskNotificationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Task notifications enabled")
            .setContentText("Watching your computer for completed tasks")
            .setContentIntent(home).setOngoing(true).setSilent(true)
            .addAction(0, "Turn off", stop).build()
        try { startForeground(MONITOR_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        if (monitor?.isActive != true) monitor = scope.launch { watch() }
        return START_NOT_STICKY
    }
    private suspend fun watch() = coroutineScope {
        val endpoint = store.endpoint; val username = store.username; val session = store.session(); val identity = store.sessionIdentity
        val names = store.cache().array("chats").mapNotNull { value ->
            (value as? kotlinx.serialization.json.JsonObject)?.let { it.text("id") to parseChat(it).title }
        }.toMap().toMutableMap()
        val tracker = CompletionTracker(store.notifiedTasks(endpoint, username)) { store.notifiedTasks(endpoint, username, it) }
        val consumer = launch {
            for (event in rpc.events) {
                if (!store.taskNotifications) { stopSelf(); break }
                val params = event.obj("params")
                when (event.text("method")) {
                    "thread/started" -> { val chat = parseChat(params.obj("thread")); names[chat.id] = chat.title }
                    "thread/name/updated" -> names[params.text("threadId")] = params.text("threadName").ifEmpty { params.text("name") }
                    "thread/deleted" -> names.remove(params.text("threadId"))
                }
                if (event.text("method") != "turn/completed") continue
                if (!enabled(endpoint, username, identity)) { stopSelf(); break }
                // A storage failure stops monitoring instead of creating repeated alerts.
                try { tracker.accept(event)?.let { postCompletion(it, names[it.threadId], endpoint, username) } }
                catch (_: Exception) { stopSelf(); break }
            }
        }
        var retry = 1000L
        try {
            while (isActive && enabled(endpoint, username, identity)) {
                try {
                    rpc.configure(endpoint, session); rpc.connect(); retry = 1000L
                    while (isActive && enabled(endpoint, username, identity) && rpc.status.value == "Connected") delay(1000)
                    if (rpc.status.value == "Session expired") break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is SessionExpiredException) break
                }
                rpc.close()
                if (!enabled(endpoint, username, identity)) break
                delay(retry); retry = (retry * 2).coerceAtMost(30000)
            }
        } finally { consumer.cancel(); rpc.close(); stopSelf() }
    }
    private fun enabled(endpoint: String, username: String, identity: String): Boolean =
        store.taskNotifications && canNotify(this) && identity.isNotEmpty() && store.sessionValid &&
            store.endpoint == endpoint && store.username == username && store.sessionIdentity == identity

    private fun postCompletion(completion: TaskCompletion, title: String?, endpoint: String, username: String) {
        if (!canNotify(this)) return
        val open = Intent(this, MainActivity::class.java).setAction("task:${completion.notificationKey(endpoint, username)}")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CHAT_ID, completion.threadId).putExtra(HOST, endpoint).putExtra(USER, username)
        val tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Task completed").setContentText(title?.takeIf { it.isNotBlank() } ?: "Your Codex task is ready")
            .setContentIntent(tap).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        getSystemService(NotificationManager::class.java).notify(completion.notificationKey(endpoint, username), COMPLETION_ID, notification)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); rpc.close(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }

    companion object {
        private const val STOP = "dev.nulltogeek.codexremote.STOP_TASK_NOTIFICATIONS"
        private const val MONITOR_ID = 201
        private const val COMPLETION_ID = 202
        private const val MONITOR_CHANNEL = "task_monitor"
        private const val COMPLETION_CHANNEL = "task_completions"
        const val CHAT_ID = "notification_chat"
        const val HOST = "notification_host"
        const val USER = "notification_user"
        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL, "Task monitoring", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(COMPLETION_CHANNEL, "Completed tasks", NotificationManager.IMPORTANCE_DEFAULT))
        }
        fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(COMPLETION_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun start(context: Context): Boolean = try {
            createChannels(context)
            if (!canNotify(context)) false else { ContextCompat.startForegroundService(context, Intent(context, TaskNotificationService::class.java)); true }
        } catch (_: RuntimeException) { false }
        // stopService matches the component; it does not compare listener identity.
        @android.annotation.SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) { context.stopService(Intent(context, TaskNotificationService::class.java)) }
    }
}
