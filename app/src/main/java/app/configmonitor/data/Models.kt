package app.configmonitor.data

import java.util.UUID

enum class TargetKind { CONFIG, ADDRESS }

/** One monitored thing: a share link (vless/vmess/trojan/ss/socks/hy2/wireguard) or a plain address. */
data class Target(
    val id: String = UUID.randomUUID().toString(),
    val kind: TargetKind,
    val name: String,
    val raw: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class ErrorCode { DNS, TCP_TIMEOUT, REFUSED, TIMEOUT, TLS, REJECTED, OTHER }

/** Live state of a target, produced by the monitor service. Never persisted. */
data class TargetStatus(
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastCheckAt: Long = 0L,
    val error: ErrorCode? = null,
    val errorDetail: String? = null,
    val alerted: Boolean = false,
    val checking: Boolean = false,
    val unsupported: Boolean = false,
    val unsupportedReason: String? = null,
    val firstFailureAt: Long = 0L,
    val checks: Int = 0,
    val okChecks: Int = 0,
    /** latency in ms per check, -1 = failed, newest last (at most 40) */
    val samples: List<Int> = emptyList(),
)

fun TargetStatus.uptimePercent(): Double? = if (checks == 0) null else okChecks * 100.0 / checks

enum class Health { UNKNOWN, UP, SLOW, DOWN, PAUSED, UNSUPPORTED }

const val SLOW_MS = 800L

fun healthOf(t: Target, st: TargetStatus?, running: Boolean): Health {
    if (!t.enabled) return Health.PAUSED
    if (!running || st == null || st.lastCheckAt == 0L) return Health.UNKNOWN
    if (st.unsupported) return Health.UNSUPPORTED
    if (st.consecutiveFailures > 0) return Health.DOWN
    val ms = st.latencyMs ?: return Health.UNKNOWN
    return if (ms > SLOW_MS) Health.SLOW else Health.UP
}

data class AppSettings(
    val language: String = "fa",          // fa | en | system
    val theme: String = "dark",           // dark | light | system
    val intervalSec: Int = 20,
    val maxFailures: Int = 5,             // alert when consecutive failures > maxFailures
    val tcpTimeoutSec: Int = 4,
    val accurateMode: Boolean = true,     // real test through the xray core
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val soundType: String = "builtin",    // builtin | system | custom
    val soundId: String = "siren",        // id of the built-in tone
    val soundUri: String = "",            // system ringtone uri or path of the copied custom file
    val soundName: String = "",           // display name of the system / custom sound
    val alarmVolumePct: Int = 100,
    val alarmVibrate: Boolean = true,
    val alarmRamp: Boolean = false,       // start quietly and get louder
    val keepCpuAwake: Boolean = true,
    val reduceMotion: Boolean = false,
    val sortMode: String = "manual",      // manual | status | latency | name
    val monitoringEnabled: Boolean = false,
)

data class AlertItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val kind: TargetKind,
    val since: Long,
)

data class AlertInfo(
    val items: List<AlertItem>,
    val raisedAt: Long,
)

enum class ReferenceNote { NONE, REFERENCE_OK, PHONE_INTERNET }

data class ReferenceResult(val note: ReferenceNote, val name: String?)

/**
 * Uses the address targets as a reference: if at least one answers, the phone has internet and the
 * problem is the config/server; if none answers, the phone's own internet is probably down.
 */
fun referenceNote(targets: List<Target>, statuses: Map<String, TargetStatus>): ReferenceResult {
    val refs = targets.filter { it.kind == TargetKind.ADDRESS && it.enabled }
    val checked = refs.filter {
        val st = statuses[it.id]
        st != null && st.lastCheckAt > 0L && !st.unsupported
    }
    if (checked.isEmpty()) return ReferenceResult(ReferenceNote.NONE, null)
    val up = checked.firstOrNull { (statuses[it.id]?.consecutiveFailures ?: 0) == 0 }
    return if (up != null) {
        ReferenceResult(ReferenceNote.REFERENCE_OK, up.name)
    } else {
        ReferenceResult(ReferenceNote.PHONE_INTERNET, null)
    }
}

enum class EventType { DOWN, UP }

/** A line of the history: a target went down (alert raised) or came back. */
data class MonitorEvent(
    val targetId: String,
    val name: String,
    val type: EventType,
    val at: Long,
    val durationMs: Long = 0L,
)
