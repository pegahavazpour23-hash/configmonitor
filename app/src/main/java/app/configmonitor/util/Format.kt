package app.configmonitor.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import app.configmonitor.R
import java.util.Calendar
import java.util.Locale

object Format {
    fun isFa(ctx: Context): Boolean = ctx.resources.configuration.locales[0].language == "fa"

    /** Converts 0-9 to Persian digits when [fa] is true. */
    fun digits(s: String, fa: Boolean): String {
        if (!fa) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c in '0'..'9') '۰' + (c - '0') else c)
        }
        return sb.toString()
    }

    fun num(n: Number, fa: Boolean): String = digits(n.toString(), fa)

    fun percent(v: Double, fa: Boolean): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (fa) digits(s.replace('.', '٫'), true) + "٪" else "$s%"
    }

    fun ago(ctx: Context, atMs: Long, nowMs: Long): String {
        if (atMs <= 0L) return ctx.getString(R.string.never)
        val fa = isFa(ctx)
        val sec = ((nowMs - atMs) / 1000).coerceAtLeast(0L)
        return if (sec < 60) {
            ctx.getString(R.string.ago_seconds, num(sec, fa))
        } else {
            ctx.getString(R.string.ago_minutes, num(sec / 60, fa))
        }
    }

    fun duration(ctx: Context, ms: Long): String {
        val fa = isFa(ctx)
        val min = ms / 60_000L
        return when {
            min < 1 -> ctx.getString(R.string.less_than_minute)
            min < 120 -> ctx.getString(R.string.duration_minutes, num(min, fa))
            else -> ctx.getString(R.string.duration_hours, num(min / 60, fa))
        }
    }

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** "02:19", "yesterday 02:19" or "10/17 02:19". */
    fun eventTime(ctx: Context, atMs: Long, nowMs: Long): String {
        val fa = isFa(ctx)
        val c = Calendar.getInstance()
        c.timeInMillis = atMs
        val time = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val days = Math.round((startOfDay(nowMs) - startOfDay(atMs)) / 86_400_000.0).toInt()
        val prefix = when {
            days <= 0 -> ""
            days == 1 -> ctx.getString(R.string.yesterday) + " "
            else -> String.format(Locale.US, "%02d/%02d ", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }
        return digits(prefix + time, fa)
    }
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
