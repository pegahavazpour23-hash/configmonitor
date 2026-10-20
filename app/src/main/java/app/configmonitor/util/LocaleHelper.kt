package app.configmonitor.util

import android.content.Context
import android.content.res.Configuration
import app.configmonitor.App
import java.util.Locale

/** Applies the in-app language (fa / en / system) to a Context. Works on every Android version. */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val lang = try {
            App.instance.settings.value.language
        } catch (e: Throwable) {
            "system"
        }
        if (lang == "system") return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
