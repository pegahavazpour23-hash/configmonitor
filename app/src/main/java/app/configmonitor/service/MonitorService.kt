package app.configmonitor.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.configmonitor.App
import app.configmonitor.R
import app.configmonitor.core.AddressParser
import app.configmonitor.core.CheckOutcome
import app.configmonitor.core.Checker
import app.configmonitor.core.LinkParser
import app.configmonitor.core.ParseResult
import app.configmonitor.data.AlertInfo
import app.configmonitor.data.AlertItem
import app.configmonitor.data.AppSettings
import app.configmonitor.data.EventType
import app.configmonitor.data.MonitorEvent
import app.configmonitor.data.ReferenceNote
import app.configmonitor.data.Target
import app.configmonitor.data.TargetKind
import app.configmonitor.data.TargetStatus
import app.configmonitor.data.referenceNote
import app.configmonitor.util.Format
import app.configmonitor.util.LocaleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that checks every enabled target on its own timer (default every 20 s),
 * counts consecutive failures per target and raises the alarm when the count exceeds the limit.
 * It never creates a VPN or a tunnel.
 */
class MonitorService : Service() {

    // everything below runs on the main thread; the actual network work happens inside Checker
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loops = HashMap<String, Job>()
    private val signatures = HashMap<String, String>()
    private val acknowledged = HashSet<String>()
    private val alertedAt = HashMap<String, Long>()
    private val checker = Checker()

    private lateinit var app: App
    private lateinit var alarm: AlarmController
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitoring = false
    private var lastNotificationText = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as App
        alarm = AlarmController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACK -> {
                acknowledge()
                if (!monitoring) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> {
                beginMonitoring()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun beginMonitoring() {
        postForeground(lastNotificationText.ifEmpty { getString(R.string.notif_monitor_starting) })
        if (monitoring) return
        monitoring = true
        MonitorRuntime.setRunning(true)
        app.settings.update { it.copy(monitoringEnabled = true) }

        scope.launch { app.targets.targets.collect { reconcile(it) } }
        scope.launch {
            app.settings.flow.map { it.keepCpuAwake }.distinctUntilChanged().collect { applyWakeLock(it) }
        }
        scope.launch {
            combine(app.targets.targets, MonitorRuntime.statuses) { t, s -> t to s }
                .collect { (t, s) -> refreshOngoing(t, s) }
        }
    }

    private fun stopMonitoring() {
        app.settings.update { it.copy(monitoringEnabled = false) }
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        monitoring = false
        for (job in loops.values) job.cancel()
        loops.clear()
        signatures.clear()
        acknowledged.clear()
        alertedAt.clear()
        alarm.stop()
        MonitorRuntime.setAlert(null)
        MonitorRuntime.clearStatuses()
        MonitorRuntime.setRunning(false)
        applyWakeLock(false)
    }

    private fun postForeground(text: String) {
        val n = Notifications.ongoing(this, text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID_ONGOING, n)
            }
        } catch (e: Exception) {
            // e.g. the system refused a foreground start; monitoring still runs while the process lives
        }
    }

    private fun applyWakeLock(enable: Boolean) {
        if (enable && monitoring) {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "configmonitor:monitor")
                lock.setReferenceCounted(false)
                lock.acquire()
                wakeLock = lock
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    // ---------------------------------------------------------------- checking

    /** Starts / stops / restarts the per-target loops so they match the stored list. */
    private fun reconcile(all: List<Target>) {
        val live = all.filter { it.enabled }
        val liveIds = live.map { it.id }.toSet()
        for (id in loops.keys.toList()) {
            if (id !in liveIds) stopLoop(id)
        }
        for (t in live) {
            val signature = "${t.kind}|${t.raw}"
            if (loops[t.id] == null || signatures[t.id] != signature) startLoop(t, signature)
        }
        refreshAlert(all)
    }

    private fun stopLoop(id: String) {
        loops.remove(id)?.cancel()
        signatures.remove(id)
        acknowledged.remove(id)
        alertedAt.remove(id)
        MonitorRuntime.removeStatus(id)
    }

    private fun startLoop(t: Target, signature: String) {
        loops.remove(t.id)?.cancel()
        signatures[t.id] = signature
        acknowledged.remove(t.id)
        alertedAt.remove(t.id)
        MonitorRuntime.setStatus(t.id, TargetStatus())

        loops[t.id] = scope.launch {
            delay(200L + (t.id.hashCode() and 0x7FFFFFFF) % 1500)   // spread the first checks out a little
            while (isActive) {
                val settings = app.settings.value
                val started = SystemClock.elapsedRealtime()
                try {
                    MonitorRuntime.setStatus(t.id, MonitorRuntime.statusOf(t.id).copy(checking = true))
                    val outcome = checker.check(t, settings)
                    onOutcome(t.id, settings, outcome)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // never let one bad iteration kill the loop
                }
                val spent = SystemClock.elapsedRealtime() - started
                delay(maxOf(1_000L, settings.intervalSec * 1_000L - spent))
            }
        }
    }

    private fun onOutcome(id: String, s: AppSettings, outcome: CheckOutcome) {
        val target = app.targets.get(id) ?: return
        val prev = MonitorRuntime.statusOf(id)
        val now = System.currentTimeMillis()
        when (outcome) {
            is CheckOutcome.Success -> {
                MonitorRuntime.setStatus(
                    id,
                    TargetStatus(
                        latencyMs = outcome.latencyMs,
                        lastCheckAt = now,
                        checks = prev.checks + 1,
                        okChecks = prev.okChecks + 1,
                        samples = (prev.samples + outcome.latencyMs.toInt()).takeLast(40),
                    )
                )
                if (prev.alerted) onRecovered(target, now - prev.firstFailureAt)
            }
            is CheckOutcome.Unsupported -> {
                MonitorRuntime.setStatus(
                    id,
                    TargetStatus(lastCheckAt = now, unsupported = true, unsupportedReason = outcome.reason)
                )
            }
            is CheckOutcome.Failure -> {
                val count = prev.consecutiveFailures + 1
                val raise = count > s.maxFailures && !prev.alerted
                MonitorRuntime.setStatus(
                    id,
                    TargetStatus(
                        consecutiveFailures = count,
                        lastCheckAt = now,
                        error = outcome.code,
                        errorDetail = outcome.detail,
                        alerted = prev.alerted || raise,
                        firstFailureAt = if (prev.consecutiveFailures == 0) now else prev.firstFailureAt,
                        checks = prev.checks + 1,
                        okChecks = prev.okChecks,
                        samples = (prev.samples + (-1)).takeLast(40),
                    )
                )
                if (raise) {
                    alertedAt[id] = now
                    app.events.add(MonitorEvent(id, target.name, EventType.DOWN, now))
                    raiseAlert(app.targets.targets.value)
                }
            }
        }
    }

    // ---------------------------------------------------------------- alerts

    private fun subtitleOf(t: Target): String = when (t.kind) {
        TargetKind.CONFIG -> (LinkParser.parse(t.raw) as? ParseResult.Ok)?.config?.summary ?: ""
        TargetKind.ADDRESS -> AddressParser.parse(t.raw)?.let { "${it.first}:${it.second}" } ?: t.raw
    }

    private fun buildAlertInfo(all: List<Target>): AlertInfo? {
        val items = all.filter { it.enabled }.mapNotNull { t ->
            val st = MonitorRuntime.statusOf(t.id)
            if (st.alerted && t.id !in acknowledged) {
                AlertItem(t.id, t.name, subtitleOf(t), t.kind, alertedAt[t.id] ?: System.currentTimeMillis())
            } else {
                null
            }
        }
        if (items.isEmpty()) return null
        return AlertInfo(items, System.currentTimeMillis())
    }

    private fun noteText(all: List<Target>): String? {
        val r = referenceNote(all, MonitorRuntime.statuses.value)
        return when (r.note) {
            ReferenceNote.NONE -> null
            ReferenceNote.REFERENCE_OK -> getString(R.string.alert_ref_ok, r.name ?: "")
            ReferenceNote.PHONE_INTERNET -> getString(R.string.alert_ref_down)
        }
    }

    private fun raiseAlert(all: List<Target>) {
        val info = buildAlertInfo(all) ?: return
        MonitorRuntime.setAlert(info)
        alarm.start(info, noteText(all), app.settings.value)
    }

    /** The list changed (target edited / removed): keep the alert in sync, stop it if nothing is left. */
    private fun refreshAlert(all: List<Target>) {
        if (MonitorRuntime.alert.value == null) return
        val info = buildAlertInfo(all)
        if (info == null) {
            alarm.stop()
            MonitorRuntime.setAlert(null)
        } else {
            MonitorRuntime.setAlert(info)
            alarm.update(info, noteText(all))
        }
    }

    private fun onRecovered(t: Target, downMs: Long) {
        acknowledged.remove(t.id)
        alertedAt.remove(t.id)
        app.events.add(MonitorEvent(t.id, t.name, EventType.UP, System.currentTimeMillis(), downMs.coerceAtLeast(0L)))
        Notifications.recovered(this, t.name, t.id.hashCode())
    }

    /** "Got it": stop sound + vibration + notification. Items stay silent until they recover and fail again. */
    private fun acknowledge() {
        for (t in app.targets.targets.value) {
            if (MonitorRuntime.statusOf(t.id).alerted) acknowledged.add(t.id)
        }
        alarm.stop()
        MonitorRuntime.setAlert(null)
    }

    private fun refreshOngoing(targets: List<Target>, statuses: Map<String, TargetStatus>) {
        val live = targets.filter { it.enabled }
        val healthy = live.count { t ->
            val st = statuses[t.id]
            st != null && st.lastCheckAt > 0L && st.consecutiveFailures == 0 && !st.unsupported
        }
        val fa = Format.isFa(this)
        val text = getString(R.string.notif_monitor_text, Format.num(healthy, fa), Format.num(live.size, fa))
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_ONGOING, Notifications.ongoing(this, text))
    }

    companion object {
        const val ACTION_STOP = "app.configmonitor.STOP"
        const val ACTION_ACK = "app.configmonitor.ACK"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                // background start not allowed right now
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) { }
        }

        fun ack(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_ACK))
            } catch (e: Exception) { }
        }
    }
}
