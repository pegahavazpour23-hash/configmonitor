package app.configmonitor.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import app.configmonitor.R
import app.configmonitor.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class BuiltinTone(val id: String, val resId: Int, val nameRes: Int)

/** The alarm sounds: tones bundled in the app, system ringtones, or a file the user picked. */
object Sounds {
    val builtin = listOf(
        BuiltinTone("siren", R.raw.tone_siren, R.string.tone_siren),
        BuiltinTone("pulse", R.raw.tone_pulse, R.string.tone_pulse),
        BuiltinTone("beacon", R.raw.tone_beacon, R.string.tone_beacon),
        BuiltinTone("classic", R.raw.tone_classic, R.string.tone_classic),
    )

    /** USAGE_ALARM: plays even when the phone is on silent or vibrate. */
    val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun rawUri(ctx: Context, resId: Int): Uri = Uri.parse("android.resource://${ctx.packageName}/$resId")

    fun builtinUri(ctx: Context, id: String): Uri {
        val tone = builtin.firstOrNull { it.id == id } ?: builtin.first()
        return rawUri(ctx, tone.resId)
    }

    /** The sound chosen in the settings; falls back to the phone's default alarm. */
    fun uriFor(ctx: Context, s: AppSettings): Uri? {
        when (s.soundType) {
            "system" -> if (s.soundUri.isNotBlank()) return Uri.parse(s.soundUri)
            "custom" -> if (s.soundUri.isNotBlank() && File(s.soundUri).exists()) return Uri.fromFile(File(s.soundUri))
            else -> return builtinUri(ctx, s.soundId)
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /** A prepared player, or null when the sound cannot be opened. */
    fun createPlayer(ctx: Context, uri: Uri, loop: Boolean): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(alarmAttrs)
            mp.setDataSource(ctx, uri)
            mp.isLooping = loop
            mp.prepare()
            mp
        } catch (e: Exception) {
            try { mp.release() } catch (x: Exception) { }
            null
        }
    }
}

/** Plays a sound while the user is choosing one (only one at a time). */
object SoundPreview {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    fun toggle(ctx: Context, key: String, uri: Uri?, volume: Float) {
        if (_playing.value == key) {
            stop()
            return
        }
        stop()
        if (uri == null) return
        val mp = Sounds.createPlayer(ctx.applicationContext, uri, loop = true) ?: return
        mp.setVolume(volume, volume)
        mp.start()
        player = mp
        _playing.value = key
    }

    fun stop() {
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        _playing.value = null
    }
}
