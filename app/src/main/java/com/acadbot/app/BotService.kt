package com.acadbot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Keeps the process alive (foreground notification + wake lock) while the bot runs. */
class BotService : Service() {
    companion object {
        const val ACTION_START = "com.acadbot.app.START"
        const val ACTION_STOP = "com.acadbot.app.STOP"
        private const val CHANNEL = "acadbot_run"
        private const val NOTIF_ID = 1
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            begin()
        } else {
            BotEngine.stop()
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        val n = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AcadBot:run").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val p = Prefs(this)
        BotEngine.onStopped = { stopForegroundCompat(); stopSelf() }
        BotEngine.onHeartbeat = { updateNotification() }
        BotEngine.start(this, p.email, p.password, p.blockImages, p.verbose, p.refreshMin)
    }

    private fun updateNotification() {
        val up = BotEngine.uptimeSec()
        val text = "${BotEngine.status} · ${BotEngine.orders} orders · " +
            "%d:%02d:%02d".format(up / 3600, up % 3600 / 60, up % 60)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return b.setContentTitle("Academia Bot")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(R.drawable.ic_stat, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Bot running", NotificationManager.IMPORTANCE_LOW))
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        BotEngine.onStopped = null
        BotEngine.onHeartbeat = null
        if (BotEngine.running) BotEngine.stop()
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wake = null
        super.onDestroy()
    }
}
