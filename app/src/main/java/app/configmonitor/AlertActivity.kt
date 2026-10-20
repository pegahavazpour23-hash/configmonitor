package app.configmonitor

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.configmonitor.service.MonitorRuntime
import app.configmonitor.service.MonitorService
import app.configmonitor.ui.AlertScreen
import app.configmonitor.ui.AppTheme
import app.configmonitor.util.LocaleHelper
import kotlinx.coroutines.launch

/** Full-screen alarm that shows over the lock screen and turns the screen on. */
class AlertActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.dark(transparent),
        )
        setContent {
            AppTheme(darkTheme = true, reduceMotion = App.instance.settings.value.reduceMotion) {
                AlertScreen(onAck = { MonitorService.ack(this@AlertActivity) })
            }
        }
        // close the screen as soon as the alert has been acknowledged
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MonitorRuntime.alert.collect { info ->
                    if (info == null) finish()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
