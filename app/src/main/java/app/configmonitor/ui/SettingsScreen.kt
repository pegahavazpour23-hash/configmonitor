package app.configmonitor.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.configmonitor.App
import app.configmonitor.R
import app.configmonitor.core.XrayEngine
import app.configmonitor.service.Sounds
import app.configmonitor.util.Format
import app.configmonitor.util.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
private fun versionName(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

private fun permTitle(item: Permissions.Item): Int = when (item) {
    Permissions.Item.NOTIFICATIONS -> R.string.perm_notifications
    Permissions.Item.FULL_SCREEN -> R.string.perm_fullscreen
    Permissions.Item.OVERLAY -> R.string.perm_overlay
    Permissions.Item.BATTERY -> R.string.perm_battery
}

private fun permDesc(item: Permissions.Item): Int = when (item) {
    Permissions.Item.NOTIFICATIONS -> R.string.perm_notifications_desc
    Permissions.Item.FULL_SCREEN -> R.string.perm_fullscreen_desc
    Permissions.Item.OVERLAY -> R.string.perm_overlay_desc
    Permissions.Item.BATTERY -> R.string.perm_battery_desc
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingBlock(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SettingRow(title: String, desc: String? = null, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing()
    }
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = cs.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onSound: () -> Unit) {
    val app = App.instance
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sc = LocalStatusColors.current
    val fa = Format.isFa(ctx)

    var permTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++ }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Permissions.open(ctx, Permissions.Item.NOTIFICATIONS)
        permTick++
    }

    // null = still loading, "-" = core missing, otherwise the version text
    val coreStatus by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            if (XrayEngine.available) (XrayEngine.version() ?: "") else "-"
        }
    }

    val langOptions = listOf(
        "fa" to stringResource(R.string.lang_fa),
        "en" to stringResource(R.string.lang_en),
        "system" to stringResource(R.string.lang_system),
    )
    val themeOptions = listOf(
        "dark" to stringResource(R.string.theme_dark),
        "light" to stringResource(R.string.theme_light),
        "system" to stringResource(R.string.theme_system),
    )
    val soundLabel = when (s.soundType) {
        "system" -> s.soundName.ifBlank { stringResource(R.string.sound_system) }
        "custom" -> s.soundName.ifBlank { stringResource(R.string.sound_custom) }
        else -> stringResource(Sounds.builtin.firstOrNull { it.id == s.soundId }?.nameRes ?: R.string.tone_siren)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBox(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = cs.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onBackground,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------------------------------------------------------- appearance
            SectionTitle(stringResource(R.string.sec_appearance))
            SettingsPanel {
                SettingBlock(stringResource(R.string.language)) {
                    Segmented(
                        options = langOptions,
                        selected = s.language,
                        onSelect = { value ->
                            if (value != s.language) {
                                app.settings.update { it.copy(language = value) }
                                ctx.findActivity()?.recreate()
                            }
                        },
                    )
                }
                HorizontalDivider(color = cs.outline)
                SettingBlock(stringResource(R.string.theme)) {
                    Segmented(
                        options = themeOptions,
                        selected = s.theme,
                        onSelect = { value -> app.settings.update { it.copy(theme = value) } },
                    )
                }
                HorizontalDivider(color = cs.outline)
                SettingRow(
                    title = stringResource(R.string.reduce_motion),
                    desc = stringResource(R.string.reduce_motion_desc),
                ) {
                    AppSwitch(checked = s.reduceMotion, onChange = { v -> app.settings.update { it.copy(reduceMotion = v) } })
                }
            }

            // ---------------------------------------------------------- checks
            SectionTitle(stringResource(R.string.sec_checks))
            SettingsPanel {
                SettingRow(title = stringResource(R.string.interval)) {
                    Stepper(
                        value = s.intervalSec, min = 10, max = 300, step = 5,
                        label = stringResource(R.string.unit_seconds, Format.num(s.intervalSec, fa)),
                    ) { v -> app.settings.update { it.copy(intervalSec = v) } }
                }
                HorizontalDivider(color = cs.outline)
                SettingRow(
                    title = stringResource(R.string.max_failures),
                    desc = stringResource(R.string.max_failures_desc),
                ) {
                    Stepper(
                        value = s.maxFailures, min = 1, max = 30, step = 1,
                        label = Format.num(s.maxFailures, fa),
                    ) { v -> app.settings.update { it.copy(maxFailures = v) } }
                }
                HorizontalDivider(color = cs.outline)
                SettingRow(title = stringResource(R.string.tcp_timeout)) {
                    Stepper(
                        value = s.tcpTimeoutSec, min = 2, max = 15, step = 1,
                        label = stringResource(R.string.unit_seconds, Format.num(s.tcpTimeoutSec, fa)),
                    ) { v -> app.settings.update { it.copy(tcpTimeoutSec = v) } }
                }
                HorizontalDivider(color = cs.outline)
                SettingRow(
                    title = stringResource(R.string.accurate_mode),
                    desc = stringResource(R.string.accurate_desc),
                ) {
                    AppSwitch(checked = s.accurateMode, onChange = { v -> app.settings.update { it.copy(accurateMode = v) } })
                }
                val status = coreStatus
                val statusText = when (status) {
                    null -> stringResource(R.string.core_checking)
                    "-" -> stringResource(R.string.core_missing)
                    "" -> stringResource(R.string.core_ok_plain)
                    else -> stringResource(R.string.core_ok, status ?: "")
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status == "-") sc.slow else sc.up,
                )
                HorizontalDivider(color = cs.outline)
                SettingBlock(stringResource(R.string.test_url)) {
                    AppTextField(
                        value = s.testUrl,
                        onValueChange = { v -> app.settings.update { it.copy(testUrl = v) } },
                        mono = true,
                        ltr = true,
                        keyboardType = KeyboardType.Uri,
                    )
                }
            }

            // ---------------------------------------------------------- alert
            SectionTitle(stringResource(R.string.sec_alert))
            SettingsPanel {
                NavRow(stringResource(R.string.sound_title), soundLabel, onSound)
                HorizontalDivider(color = cs.outline)
                SettingRow(
                    title = stringResource(R.string.keep_cpu),
                    desc = stringResource(R.string.keep_cpu_desc),
                ) {
                    AppSwitch(checked = s.keepCpuAwake, onChange = { v -> app.settings.update { it.copy(keepCpuAwake = v) } })
                }
            }

            // ---------------------------------------------------------- permissions
            SectionTitle(stringResource(R.string.sec_permissions))
            SettingsPanel {
                Permissions.Item.values().forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = cs.outline)
                    val ok = remember(permTick) { Permissions.granted(ctx, item) }
                    val title = stringResource(permTitle(item)) +
                        if (Permissions.isOptional(item)) " " + stringResource(R.string.optional_tag) else ""
                    SettingRow(title = title, desc = stringResource(permDesc(item))) {
                        if (ok) {
                            Text(
                                stringResource(R.string.perm_granted),
                                style = MaterialTheme.typography.labelLarge,
                                color = sc.up,
                            )
                        } else {
                            TextButton(onClick = {
                                if (item == Permissions.Item.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    Permissions.open(ctx, item)
                                }
                            }) {
                                Text(
                                    stringResource(R.string.perm_fix),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.primary,
                                )
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- about
            SectionTitle(stringResource(R.string.sec_about))
            SettingsPanel {
                SettingRow(
                    title = stringResource(R.string.app_name),
                    desc = stringResource(R.string.version, Format.digits(versionName(ctx), fa)),
                ) { }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
