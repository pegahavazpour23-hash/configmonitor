package app.configmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.configmonitor.App
import app.configmonitor.R
import app.configmonitor.core.AddressParser
import app.configmonitor.core.CheckOutcome
import app.configmonitor.core.Checker
import app.configmonitor.core.LinkParser
import app.configmonitor.core.ParseResult
import app.configmonitor.core.ProxyConfig
import app.configmonitor.core.XrayEngine
import app.configmonitor.data.AppSettings
import app.configmonitor.data.ErrorCode
import app.configmonitor.data.Health
import app.configmonitor.data.MonitorEvent
import app.configmonitor.data.Target
import app.configmonitor.data.TargetKind
import app.configmonitor.data.TargetStatus
import app.configmonitor.data.healthOf
import app.configmonitor.data.uptimePercent
import app.configmonitor.service.MonitorRuntime
import app.configmonitor.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RowUi(
    val target: Target,
    val status: TargetStatus?,
    val health: Health,
    val subtitle: String,
    val badge: String,
)

data class DashboardUi(
    val configs: List<RowUi> = emptyList(),
    val addresses: List<RowUi> = emptyList(),
    val enabled: Int = 0,
    val up: Int = 0,
    val slow: Int = 0,
    val down: Int = 0,
    val paused: Int = 0,
    val running: Boolean = false,
    val lastCheckAt: Long = 0L,
    val coreMissing: Boolean = false,
    val uptime: Double? = null,
)

/** Result of the "test now" button (kept apart from the monitor so it never raises an alarm). */
data class ManualResult(
    val at: Long,
    val latencyMs: Long?,
    val error: ErrorCode?,
    val detail: String?,
    val unsupported: String?,
)

data class TestState(
    val results: Map<String, ManualResult> = emptyMap(),
    val running: Set<String> = emptySet(),
)

data class DetailUi(
    val target: Target,
    val status: TargetStatus?,
    val health: Health,
    val config: ProxyConfig?,
    val subtitle: String,
    val badge: String,
    val events: List<MonitorEvent>,
    val manual: ManualResult?,
    val testing: Boolean,
    val running: Boolean,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val checker = Checker()
    private val coreAvailable = MutableStateFlow(true)
    private val tests = MutableStateFlow(TestState())
    private var lastDeleted: Pair<Int, Target>? = null
    private val deletedFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Emits the name of a target that was just deleted (the screen offers "undo"). */
    val deleted: SharedFlow<String> = deletedFlow.asSharedFlow()

    init {
        // loading the native library can take a moment, keep it off the main thread
        viewModelScope.launch(Dispatchers.IO) { coreAvailable.value = XrayEngine.available }
    }

    val settings: StateFlow<AppSettings> = app.settings.flow

    val ui: StateFlow<DashboardUi> = combine(
        app.targets.targets,
        MonitorRuntime.statuses,
        MonitorRuntime.running,
        app.settings.flow,
        coreAvailable,
    ) { targets, statuses, running, s, core ->
        val rows = targets.map { t ->
            val st = statuses[t.id]
            RowUi(t, st, healthOf(t, st, running), subtitleOf(t), badgeOf(t))
        }
        val enabledRows = rows.filter { it.target.enabled }
        val uptimes = enabledRows.mapNotNull { it.status?.uptimePercent() }
        DashboardUi(
            configs = rows.filter { it.target.kind == TargetKind.CONFIG },
            addresses = rows.filter { it.target.kind == TargetKind.ADDRESS },
            enabled = enabledRows.size,
            up = enabledRows.count { it.health == Health.UP },
            slow = enabledRows.count { it.health == Health.SLOW },
            down = enabledRows.count { it.health == Health.DOWN },
            paused = rows.count { it.health == Health.PAUSED },
            running = running,
            lastCheckAt = statuses.values.maxOfOrNull { it.lastCheckAt } ?: 0L,
            coreMissing = s.accurateMode && !core,
            uptime = if (uptimes.isEmpty()) null else uptimes.average(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    /** Everything the detail screen needs for one target; null when the target no longer exists. */
    fun detail(id: String): StateFlow<DetailUi?> = combine(
        app.targets.targets,
        MonitorRuntime.statuses,
        MonitorRuntime.running,
        app.events.events,
        tests,
    ) { targets, statuses, running, events, ts ->
        val t = targets.firstOrNull { it.id == id }
        if (t == null) {
            null
        } else {
            val st = statuses[id]
            DetailUi(
                target = t,
                status = st,
                health = healthOf(t, st, running),
                config = if (t.kind == TargetKind.CONFIG) parsed(t) else null,
                subtitle = subtitleOf(t),
                badge = badgeOf(t),
                events = events.filter { it.targetId == id }.asReversed().take(8),
                manual = ts.results[id],
                testing = id in ts.running,
                running = running,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun parsed(t: Target): ProxyConfig? = (LinkParser.parse(t.raw) as? ParseResult.Ok)?.config

    private fun subtitleOf(t: Target): String = when (t.kind) {
        TargetKind.CONFIG -> parsed(t)?.summary ?: getApplication<Application>().getString(R.string.invalid_link)
        TargetKind.ADDRESS -> AddressParser.parse(t.raw)?.let { "${it.first}:${it.second}" } ?: t.raw
    }

    private fun badgeOf(t: Target): String = when (t.kind) {
        TargetKind.CONFIG -> parsed(t)?.protocol?.badge ?: "?"
        TargetKind.ADDRESS -> "TCP"
    }

    // ---------------------------------------------------------------- actions

    /** Adds every valid, not-yet-known link. [name] is only used when exactly one link is given. Returns how many were added. */
    fun addConfigs(links: List<String>, name: String = ""): Int {
        val known = app.targets.targets.value.map { it.raw }.toSet()
        val items = ArrayList<Target>()
        for (link in links) {
            val parsed = LinkParser.parse(link) as? ParseResult.Ok ?: continue
            if (parsed.config.unsupported != null) continue
            val raw = link.trim()
            if (raw in known || items.any { it.raw == raw }) continue
            val label = if (links.size == 1 && name.isNotBlank()) name.trim() else parsed.config.displayName
            items.add(Target(kind = TargetKind.CONFIG, name = label, raw = raw))
        }
        if (items.isNotEmpty()) app.targets.addAll(items)
        return items.size
    }

    fun saveConfig(id: String, name: String, link: String, enabled: Boolean) {
        val parsed = LinkParser.parse(link) as? ParseResult.Ok ?: return
        app.targets.update(id) {
            it.copy(name = name.trim().ifBlank { parsed.config.displayName }, raw = link.trim(), enabled = enabled)
        }
    }

    fun saveAddress(id: String?, name: String, address: String, enabled: Boolean) {
        val parsed = AddressParser.parse(address) ?: return
        val label = name.trim().ifBlank { parsed.first }
        if (id == null) {
            app.targets.addAll(
                listOf(Target(kind = TargetKind.ADDRESS, name = label, raw = address.trim(), enabled = enabled))
            )
        } else {
            app.targets.update(id) { it.copy(name = label, raw = address.trim(), enabled = enabled) }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        app.targets.update(id) { it.copy(enabled = enabled) }
    }

    fun setAllEnabled(enabled: Boolean) {
        app.targets.setAllEnabled(enabled)
    }

    fun delete(id: String) {
        val list = app.targets.targets.value
        val index = list.indexOfFirst { it.id == id }
        val target = list.getOrNull(index) ?: return
        lastDeleted = index to target
        app.targets.remove(id)
        deletedFlow.tryEmit(target.name)
    }

    fun undoDelete() {
        val saved = lastDeleted ?: return
        lastDeleted = null
        app.targets.insert(saved.first, saved.second)
    }

    fun setSort(mode: String) {
        app.settings.update { it.copy(sortMode = mode) }
    }

    fun setMonitoring(on: Boolean) {
        val ctx = getApplication<Application>()
        if (on) MonitorService.start(ctx) else MonitorService.stop(ctx)
    }

    suspend fun fetchSubscription(url: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { LinkParser.fetchSubscription(url) }
    }

    /** One immediate check of a single target; the result is only shown on the detail screen. */
    fun testNow(id: String) {
        val t = app.targets.get(id) ?: return
        if (id in tests.value.running) return
        tests.update { it.copy(running = it.running + id) }
        viewModelScope.launch {
            val outcome = checker.check(t, app.settings.value)
            val now = System.currentTimeMillis()
            val result = when (outcome) {
                is CheckOutcome.Success -> ManualResult(now, outcome.latencyMs, null, null, null)
                is CheckOutcome.Failure -> ManualResult(now, null, outcome.code, outcome.detail, null)
                is CheckOutcome.Unsupported -> ManualResult(now, null, null, null, outcome.reason)
            }
            tests.update { it.copy(results = it.results + (id to result), running = it.running - id) }
        }
    }
}
