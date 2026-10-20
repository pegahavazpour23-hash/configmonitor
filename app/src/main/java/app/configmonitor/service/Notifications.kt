package app.configmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.configmonitor.MainActivity
import app.configmonitor.R
import app.configmonitor.util.LocaleHelper

object Notifications {
    const val CH_MONITOR = "monitor"
    const val CH_ALERT = "alert"
    const val CH_INFO = "info"
    const val ID_ONGOING = 1001
    const val ID_ALERT = 1002

    fun createChannels(ctx: Context) {
        val c = LocaleHelper.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val monitor = NotificationChannel(
            CH_MONITOR, c.getString(R.string.notif_channel_monitor), NotificationManager.IMPORTANCE_LOW
        )
        monitor.setShowBadge(false)
        nm.createNotificationChannel(monitor)

        // The alarm sound and vibration are played by AlarmController (USAGE_ALARM), so the channel itself is silent.
        val alert = NotificationChannel(
            CH_ALERT, c.getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_HIGH
        )
        alert.setSound(null, null)
        alert.enableVibration(false)
        alert.setBypassDnd(true)
        alert.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alert)

        val info = NotificationChannel(
            CH_INFO, c.getString(R.string.notif_channel_info), NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(info)
    }

    fun ongoing(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(ctx.getString(R.string.notif_monitor_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.notif_monitor_stop), stop)
            .build()
    }

    fun recovered(ctx: Context, name: String, key: Int) {
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(ctx.getString(R.string.notif_recovered, name))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(3000 + (key and 0xFFFF), n)
    }
}
