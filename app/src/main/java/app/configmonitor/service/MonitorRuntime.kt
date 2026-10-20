package app.configmonitor.service

import app.configmonitor.data.AlertInfo
import app.configmonitor.data.TargetStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory state shared between the monitor service and the UI (same process). */
object MonitorRuntime {
    private val _statuses = MutableStateFlow<Map<String, TargetStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, TargetStatus>> = _statuses.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _alert = MutableStateFlow<AlertInfo?>(null)
    val alert: StateFlow<AlertInfo?> = _alert.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun statusOf(id: String): TargetStatus = _statuses.value[id] ?: TargetStatus()

    fun setStatus(id: String, status: TargetStatus) {
        _statuses.update { it + (id to status) }
    }

    fun removeStatus(id: String) {
        _statuses.update { it - id }
    }

    fun clearStatuses() {
        _statuses.value = emptyMap()
    }

    fun setAlert(info: AlertInfo?) {
        _alert.value = info
    }
}
