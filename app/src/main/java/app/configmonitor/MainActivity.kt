package app.configmonitor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.configmonitor.data.TargetKind
import app.configmonitor.service.MonitorRuntime
import app.configmonitor.service.MonitorService
import app.configmonitor.ui.AddScreen
import app.configmonitor.ui.AddressEditorScreen
import app.configmonitor.ui.AppTheme
import app.configmonitor.ui.ConfigEditorScreen
import app.configmonitor.ui.DashboardScreen
import app.configmonitor.ui.DetailScreen
import app.configmonitor.ui.LocalReduceMotion
import app.configmonitor.ui.MainViewModel
import app.configmonitor.ui.ScanScreen
import app.configmonitor.ui.SettingsScreen
import app.configmonitor.ui.SoundScreen
import app.configmonitor.util.LocaleHelper

sealed interface Screen {
    data object Dashboard : Screen
    data object Settings : Screen
    data object Sound : Screen
    data object Scan : Screen
    data class Add(val initial: String = "") : Screen
    data class Detail(val id: String) : Screen
    data class ConfigEditor(val id: String) : Screen
    data class AddressEditor(val id: String?) : Screen
}

private fun parentOf(s: Screen): Screen = when (s) {
    Screen.Dashboard -> Screen.Dashboard
    Screen.Settings, Screen.Scan, is Screen.Add, is Screen.Detail, is Screen.AddressEditor -> Screen.Dashboard
    Screen.Sound -> Screen.Settings
    is Screen.ConfigEditor -> Screen.Detail(s.id)
}

/** How deep a screen is; used to slide forward when going deeper and backward when going up. */
private fun depthOf(s: Screen): Int = when (s) {
    Screen.Dashboard -> 0
    Screen.Settings, Screen.Scan, is Screen.Add, is Screen.Detail, is Screen.AddressEditor -> 1
    Screen.Sound, is Screen.ConfigEditor -> 2
}

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the process may have been killed while monitoring was on: bring the service back
        if (App.instance.settings.value.monitoringEnabled && !MonitorRuntime.running.value) {
            MonitorService.start(this)
        }
        setContent {
            val vm: MainViewModel = viewModel()
            AppRoot(this, vm)
        }
    }
}

@Composable
private fun AppRoot(activity: ComponentActivity, vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.theme) {
        "light" -> false
        "system" -> systemDark
        else -> true
    }

    DisposableEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }

    AppTheme(darkTheme = dark, reduceMotion = settings.reduceMotion) {
        var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
        val reduce = LocalReduceMotion.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val targets = App.instance.targets

        BackHandler(enabled = screen != Screen.Dashboard) { screen = parentOf(screen) }

        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val delta = depthOf(targetState) - depthOf(initialState)
                val sign = if (rtl) -1 else 1
                if (reduce) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (delta > 0) {
                    (slideInHorizontally(tween(320)) { sign * it / 5 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(320)) { -sign * it / 6 } + fadeOut(tween(200)))
                } else if (delta < 0) {
                    (slideInHorizontally(tween(320)) { -sign * it / 5 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(320)) { sign * it / 6 } + fadeOut(tween(200)))
                } else {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                }
            },
            label = "navigation",
        ) { current ->
            when (current) {
                Screen.Dashboard -> DashboardScreen(
                    vm = vm,
                    onAddConfig = { screen = Screen.Add() },
                    onAddAddress = { screen = Screen.AddressEditor(null) },
                    onScan = { screen = Screen.Scan },
                    onOpen = { target -> screen = Screen.Detail(target.id) },
                    onSettings = { screen = Screen.Settings },
                )
                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Dashboard },
                    onSound = { screen = Screen.Sound },
                )
                Screen.Sound -> SoundScreen(onBack = { screen = Screen.Settings })
                Screen.Scan -> ScanScreen(
                    onResult = { text -> screen = Screen.Add(text) },
                    onClose = { screen = Screen.Dashboard },
                )
                is Screen.Add -> AddScreen(
                    vm = vm,
                    initialText = current.initial,
                    onClose = { screen = Screen.Dashboard },
                    onScan = { screen = Screen.Scan },
                )
                is Screen.Detail -> DetailScreen(
                    id = current.id,
                    vm = vm,
                    onBack = { screen = Screen.Dashboard },
                    onEdit = {
                        val t = targets.get(current.id)
                        screen = if (t?.kind == TargetKind.ADDRESS) {
                            Screen.AddressEditor(current.id)
                        } else {
                            Screen.ConfigEditor(current.id)
                        }
                    },
                )
                is Screen.ConfigEditor -> {
                    val t = targets.get(current.id)
                    if (t != null) {
                        ConfigEditorScreen(t, vm) { screen = Screen.Detail(current.id) }
                    } else {
                        LaunchedEffect(Unit) { screen = Screen.Dashboard }
                    }
                }
                is Screen.AddressEditor -> {
                    val id = current.id
                    AddressEditorScreen(id?.let { targets.get(it) }, vm) {
                        screen = if (id == null) Screen.Dashboard else Screen.Detail(id)
                    }
                }
            }
        }
    }
}
