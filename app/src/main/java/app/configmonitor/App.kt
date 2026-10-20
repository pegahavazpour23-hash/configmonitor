package app.configmonitor

import android.app.Application
import app.configmonitor.core.XrayEngine
import app.configmonitor.data.EventRepository
import app.configmonitor.data.SettingsRepository
import app.configmonitor.data.TargetRepository
import app.configmonitor.service.Notifications

class App : Application() {
    lateinit var targets: TargetRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var events: EventRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        targets = TargetRepository(this)
        events = EventRepository(this)
        Notifications.createChannels(this)
        XrayEngine.init(filesDir.absolutePath)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
