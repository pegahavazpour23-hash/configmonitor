package app.configmonitor.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** The four switches that decide whether the alarm really reaches you with the screen off / on silent. */
object Permissions {
    enum class Item { NOTIFICATIONS, FULL_SCREEN, OVERLAY, BATTERY }

    fun granted(ctx: Context, item: Item): Boolean = when (item) {
        Item.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            }
        Item.FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= 34) {
                ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            } else {
                true
            }
        Item.OVERLAY -> Settings.canDrawOverlays(ctx)
        Item.BATTERY -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Missing permissions that matter. "Display over other apps" is optional, so it is not counted. */
    fun missing(ctx: Context): List<Item> = Item.values().filter { it != Item.OVERLAY && !granted(ctx, it) }

    fun isOptional(item: Item): Boolean = item == Item.OVERLAY

    fun settingsIntent(ctx: Context, item: Item): Intent {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (item) {
            Item.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            Item.FULL_SCREEN ->
                if (Build.VERSION.SDK_INT >= 34) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                }
            Item.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Item.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        }
    }

    /** Opens the right system screen; falls back to the app's details page on exotic ROMs. */
    fun open(ctx: Context, item: Item) {
        try {
            ctx.startActivity(settingsIntent(ctx, item))
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                )
            } catch (x: Exception) { }
        }
    }
}
