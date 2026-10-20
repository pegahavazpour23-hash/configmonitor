package app.configmonitor.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.media.RingtoneManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.configmonitor.App
import app.configmonitor.R
import app.configmonitor.service.Sounds
import app.configmonitor.service.SoundPreview
import app.configmonitor.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** Copies a picked audio file into the app's private storage so it keeps working later. */
private fun copyCustomSound(ctx: Context, uri: Uri): Pair<String, String>? {
    return try {
        var name = "custom"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "audio"
        val out = File(ctx.filesDir, "custom_alarm.$ext")
        ctx.filesDir.listFiles()
            ?.filter { it.name.startsWith("custom_alarm.") && it != out }
            ?.forEach { it.delete() }
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        input.use { i ->
            out.outputStream().use { o ->
                val copied = i.copyTo(o)
                if (copied > 15_000_000L) throw IOException("file too big")
            }
        }
        out.absolutePath to name
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val inner by animateFloatAsState(if (selected) 1f else 0f, motion(200), label = "radio")
    Box(
        Modifier.size(20.dp).border(2.dp, if (selected) cs.primary else cs.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = inner
                    scaleY = inner
                }
                .clip(CircleShape)
                .background(cs.primary)
        )
    }
}

@Composable
private fun WaveBars(color: Color) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "wave")
    Row(
        Modifier.height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (i in 0 until 3) {
            val h by t.animateFloat(
                initialValue = 5f,
                targetValue = 18f,
                animationSpec = infiniteRepeatable(tween(450 + i * 120), RepeatMode.Reverse),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (reduce) 12.dp else h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun PreviewButton(playing: Boolean, description: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (playing) cs.primary else Color.Transparent)
            .border(1.dp, if (playing) cs.primary else cs.outlineVariant, CircleShape)
            .clickableBox(description, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(cs.onPrimary))
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ToneRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    playing: Boolean,
    previewLabel: String,
    onSelect: () -> Unit,
    onPreview: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioDot(selected)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) cs.primary else cs.onSurface,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        if (playing) WaveBars(cs.primary)
        if (onPreview != null) PreviewButton(playing, previewLabel, onPreview)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Suppress("DEPRECATION")
@Composable
fun SoundScreen(onBack: () -> Unit) {
    val app = App.instance
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val playing by SoundPreview.playing.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val fa = Format.isFa(ctx)
    val scope = rememberCoroutineScope()
    var volume by remember(s.alarmVolumePct) { mutableFloatStateOf(s.alarmVolumePct.toFloat()) }

    DisposableEffect(Unit) { onDispose { SoundPreview.stop() } }

    val pickSystem = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) {
                val title = try {
                    RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: ""
                } catch (e: Exception) {
                    ""
                }
                app.settings.update { it.copy(soundType = "system", soundUri = uri.toString(), soundName = title) }
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { copyCustomSound(ctx, uri) }
                if (saved != null) {
                    app.settings.update { it.copy(soundType = "custom", soundUri = saved.first, soundName = saved.second) }
                } else {
                    Toast.makeText(ctx, R.string.sound_file_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val previewVolume = s.alarmVolumePct / 100f
    val systemTitle = stringResource(R.string.sound_system)

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
            Text(stringResource(R.string.sound_title), style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionLabel(stringResource(R.string.sound_builtin))
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    Sounds.builtin.forEachIndexed { index, tone ->
                        if (index > 0) HorizontalDivider(color = cs.outline)
                        val key = "builtin:" + tone.id
                        ToneRow(
                            selected = s.soundType == "builtin" && s.soundId == tone.id,
                            title = stringResource(tone.nameRes),
                            subtitle = null,
                            playing = playing == key,
                            previewLabel = stringResource(R.string.sound_preview),
                            onSelect = { app.settings.update { it.copy(soundType = "builtin", soundId = tone.id) } },
                            onPreview = { SoundPreview.toggle(ctx, key, Sounds.builtinUri(ctx, tone.id), previewVolume) },
                        )
                    }
                }
            }

            SectionLabel(stringResource(R.string.sound_phone))
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    val systemChosen = s.soundType == "system" && s.soundUri.isNotBlank()
                    ToneRow(
                        selected = systemChosen,
                        title = systemTitle,
                        subtitle = if (systemChosen) s.soundName.ifBlank { null } else stringResource(R.string.sound_system_hint),
                        playing = playing == "system",
                        previewLabel = stringResource(R.string.sound_preview),
                        onSelect = {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, systemTitle)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                if (systemChosen) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(s.soundUri))
                            }
                            pickSystem.launch(intent)
                        },
                        onPreview = if (systemChosen) {
                            { SoundPreview.toggle(ctx, "system", Uri.parse(s.soundUri), previewVolume) }
                        } else {
                            null
                        },
                    )
                    HorizontalDivider(color = cs.outline)
                    val customChosen = s.soundType == "custom" && s.soundUri.isNotBlank()
                    ToneRow(
                        selected = customChosen,
                        title = stringResource(R.string.sound_custom),
                        subtitle = if (customChosen) s.soundName.ifBlank { null } else stringResource(R.string.sound_custom_hint),
                        playing = playing == "custom",
                        previewLabel = stringResource(R.string.sound_preview),
                        onSelect = { pickFile.launch(arrayOf("audio/*")) },
                        onPreview = if (customChosen) {
                            { SoundPreview.toggle(ctx, "custom", Uri.fromFile(File(s.soundUri)), previewVolume) }
                        } else {
                            null
                        },
                    )
                }
            }

            SectionLabel(stringResource(R.string.sound_intensity))
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.sound_volume),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            Format.num(volume.roundToInt(), fa) + if (fa) "٪" else "%",
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.primary,
                        )
                    }
                    AppSlider(
                        value = volume,
                        onValueChange = { volume = it },
                        valueRange = 10f..100f,
                        onValueChangeFinished = {
                            val pct = volume.roundToInt()
                            app.settings.update { it.copy(alarmVolumePct = pct) }
                        },
                    )
                    HorizontalDivider(color = cs.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.sound_vibrate),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        AppSwitch(checked = s.alarmVibrate, onChange = { v -> app.settings.update { it.copy(alarmVibrate = v) } })
                    }
                    HorizontalDivider(color = cs.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.sound_ramp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurface,
                            )
                            Text(
                                stringResource(R.string.sound_ramp_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        AppSwitch(checked = s.alarmRamp, onChange = { v -> app.settings.update { it.copy(alarmRamp = v) } })
                    }
                }
            }
            Text(
                stringResource(R.string.sound_silent_note),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp),
            )
        }
    }
}
