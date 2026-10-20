package app.configmonitor.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import app.configmonitor.AlertActivity
import app.configmonitor.R
import app.configmonitor.data.AlertInfo
import app.configmonitor.data.AppSettings
import app.configmonitor.util.Format
import kotlin.math.roundToInt

/**
 * The loud part. Sound and vibration use USAGE_ALARM, so they play even when the phone is on
 * silent or vibrate. Everything stops when the user touches "Got it" (see MonitorService.acknowledge).
 */
class AlarmController(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var toneLoop: Runnable? = null
    private var rampTask: Runnable? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1

    var active: Boolean = false
        private set

    fun start(info: AlertInfo, note: String?, s: AppSettings) {
        if (active) {
            update(info, note)
            return
        }
        active = true
        raiseVolume(s.alarmVolumePct)
        startSound(s)
        if (s.alarmVibrate) startVibration()
        postNotification(info, note)
        showScreen()
    }

    fun update(info: AlertInfo, note: String?) {
        if (active) postNotification(info, note)
    }

    fun stop() {
        nm.cancel(Notifications.ID_ALERT)
        if (!active) return
        active = false
        rampTask?.let { handler.removeCallbacks(it) }
        rampTask = null
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        toneLoop?.let { handler.removeCallbacks(it) }
        toneLoop = null
        try { tone?.release() } catch (e: Exception) { }
        tone = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        vibrator = null
        restoreVolume()
    }

    private fun startSound(s: AppSettings) {
        val uri = Sounds.uriFor(ctx, s)
        if (uri != null) {
            val mp = Sounds.createPlayer(ctx, uri, loop = true)
            if (mp != null) {
                try {
                    if (s.alarmRamp) {
                        mp.setVolume(RAMP_START, RAMP_START)
                        startRamp(mp)
                    }
                    mp.start()
                    player = mp
                    return
                } catch (e: Exception) {
                    try { mp.release() } catch (x: Exception) { }
                }
            }
        }
        startFallbackTone()
    }

    /** Quiet at first, full volume after about 12 seconds. */
    private fun startRamp(mp: MediaPlayer) {
        var step = 0
        val task = object : Runnable {
            override fun run() {
                step++
                val v = (RAMP_START + (1f - RAMP_START) * (step / RAMP_STEPS.toFloat())).coerceAtMost(1f)
                try {
                    mp.setVolume(v, v)
                } catch (e: Exception) {
                    return
                }
                if (step < RAMP_STEPS) handler.postDelayed(this, 500L)
            }
        }
        rampTask = task
        handler.postDelayed(task, 500L)
    }

    private fun startFallbackTone() {
        val generator = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            return
        }
        tone = generator
        val loop = object : Runnable {
            override fun run() {
                try { generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900) } catch (e: Exception) { }
                handler.postDelayed(this, 1300L)
            }
        }
        toneLoop = loop
        handler.post(loop)
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = v
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 900L, 500L), 0), attrs)
        } catch (e: Exception) {
            // vibration is optional
        }
    }

    private fun raiseVolume(pct: Int) {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = (max * pct.coerceIn(0, 100) / 100f).roundToInt().coerceIn(1, max)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: Exception) {
            savedVolume = -1
        }
    }

    private fun restoreVolume() {
        if (savedVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (e: Exception) { }
        }
        savedVolume = -1
    }

    private fun showScreen() {
        // Works when the app is visible or has the "display over other apps" permission;
        // otherwise the full-screen-intent notification below brings the screen up.
        try {
            ctx.startActivity(Intent(ctx, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    private fun postNotification(info: AlertInfo, note: String?) {
        val fa = Format.isFa(ctx)
        val title = if (info.items.size == 1) {
            ctx.getString(R.string.alert_title_one)
        } else {
            ctx.getString(R.string.alert_title_many, Format.num(info.items.size, fa))
        }
        val names = info.items.joinToString(", ") { it.name }
        val text = if (note.isNullOrBlank()) names else "$names\n$note"

        val screen = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ack = PendingIntent.getService(
            ctx, 3,
            Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_ACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Notifications.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(names)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, ctx.getString(R.string.alert_ack), ack)
            .build()
        nm.notify(Notifications.ID_ALERT, n)
    }

    private companion object {
        const val RAMP_START = 0.15f
        const val RAMP_STEPS = 24
    }
}
