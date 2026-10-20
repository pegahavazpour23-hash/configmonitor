package app.configmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.configmonitor.App

/** Restarts monitoring after a reboot if it was switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && App.instance.settings.value.monitoringEnabled) {
            MonitorService.start(context)
        }
    }
}
