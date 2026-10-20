package app.configmonitor.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            language = prefs.getString("language", d.language) ?: d.language,
            theme = prefs.getString("theme", d.theme) ?: d.theme,
            intervalSec = prefs.getInt("intervalSec", d.intervalSec),
            maxFailures = prefs.getInt("maxFailures", d.maxFailures),
            tcpTimeoutSec = prefs.getInt("tcpTimeoutSec", d.tcpTimeoutSec),
            accurateMode = prefs.getBoolean("accurateMode", d.accurateMode),
            testUrl = prefs.getString("testUrl", d.testUrl) ?: d.testUrl,
            soundType = prefs.getString("soundType", d.soundType) ?: d.soundType,
            soundId = prefs.getString("soundId", d.soundId) ?: d.soundId,
            soundUri = prefs.getString("soundUri", d.soundUri) ?: d.soundUri,
            soundName = prefs.getString("soundName", d.soundName) ?: d.soundName,
            alarmVolumePct = prefs.getInt("alarmVolumePct", d.alarmVolumePct),
            alarmVibrate = prefs.getBoolean("alarmVibrate", d.alarmVibrate),
            alarmRamp = prefs.getBoolean("alarmRamp", d.alarmRamp),
            keepCpuAwake = prefs.getBoolean("keepCpuAwake", d.keepCpuAwake),
            reduceMotion = prefs.getBoolean("reduceMotion", d.reduceMotion),
            sortMode = prefs.getString("sortMode", d.sortMode) ?: d.sortMode,
            monitoringEnabled = prefs.getBoolean("monitoringEnabled", d.monitoringEnabled),
        )
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        state.value = next
        prefs.edit()
            .putString("language", next.language)
            .putString("theme", next.theme)
            .putInt("intervalSec", next.intervalSec)
            .putInt("maxFailures", next.maxFailures)
            .putInt("tcpTimeoutSec", next.tcpTimeoutSec)
            .putBoolean("accurateMode", next.accurateMode)
            .putString("testUrl", next.testUrl)
            .putString("soundType", next.soundType)
            .putString("soundId", next.soundId)
            .putString("soundUri", next.soundUri)
            .putString("soundName", next.soundName)
            .putInt("alarmVolumePct", next.alarmVolumePct)
            .putBoolean("alarmVibrate", next.alarmVibrate)
            .putBoolean("alarmRamp", next.alarmRamp)
            .putBoolean("keepCpuAwake", next.keepCpuAwake)
            .putBoolean("reduceMotion", next.reduceMotion)
            .putString("sortMode", next.sortMode)
            .putBoolean("monitoringEnabled", next.monitoringEnabled)
            .apply()
    }
}

/** Stores the list of targets in a small JSON file in the app's private storage. */
class TargetRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "targets.json")
    private val state = MutableStateFlow(readFromDisk())

    val targets: StateFlow<List<Target>> = state.asStateFlow()

    fun get(id: String): Target? = state.value.firstOrNull { it.id == id }

    @Synchronized
    fun addAll(items: List<Target>) {
        commit(state.value + items)
    }

    @Synchronized
    fun insert(index: Int, item: Target) {
        val list = state.value.toMutableList()
        list.add(index.coerceIn(0, list.size), item)
        commit(list)
    }

    @Synchronized
    fun update(id: String, transform: (Target) -> Target) {
        commit(state.value.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun setAllEnabled(enabled: Boolean) {
        commit(state.value.map { it.copy(enabled = enabled) })
    }

    @Synchronized
    fun remove(id: String) {
        commit(state.value.filter { it.id != id })
    }

    private fun commit(list: List<Target>) {
        state.value = list
        try {
            val arr = JSONArray()
            for (t in list) {
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("kind", t.kind.name)
                        .put("name", t.name)
                        .put("raw", t.raw)
                        .put("enabled", t.enabled)
                        .put("createdAt", t.createdAt)
                )
            }
            val text = arr.toString()
            val tmp = File(file.parentFile, "targets.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            // keep the in-memory state even if the disk write fails
        }
    }

    private fun readFromDisk(): List<Target> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val out = ArrayList<Target>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Target(
                        id = o.getString("id"),
                        kind = TargetKind.valueOf(o.getString("kind")),
                        name = o.getString("name"),
                        raw = o.getString("raw"),
                        enabled = o.optBoolean("enabled", true),
                        createdAt = o.optLong("createdAt", 0L),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** The history of "went down" / "came back" events (last 300 are kept). */
class EventRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "events.json")
    private val state = MutableStateFlow(readFromDisk())

    val events: StateFlow<List<MonitorEvent>> = state.asStateFlow()

    @Synchronized
    fun add(event: MonitorEvent) {
        commit((state.value + event).takeLast(300))
    }

    @Synchronized
    fun clearFor(targetId: String) {
        commit(state.value.filter { it.targetId != targetId })
    }

    private fun commit(list: List<MonitorEvent>) {
        state.value = list
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject()
                        .put("id", e.targetId)
                        .put("name", e.name)
                        .put("type", e.type.name)
                        .put("at", e.at)
                        .put("dur", e.durationMs)
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // history is optional
        }
    }

    private fun readFromDisk(): List<MonitorEvent> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val out = ArrayList<MonitorEvent>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    MonitorEvent(
                        targetId = o.getString("id"),
                        name = o.getString("name"),
                        type = EventType.valueOf(o.getString("type")),
                        at = o.getLong("at"),
                        durationMs = o.optLong("dur", 0L),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
