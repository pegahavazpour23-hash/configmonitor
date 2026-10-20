package app.configmonitor.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.configmonitor.App
import app.configmonitor.R
import app.configmonitor.data.ErrorCode
import app.configmonitor.data.EventType
import app.configmonitor.data.Health
import app.configmonitor.data.SLOW_MS
import app.configmonitor.data.TargetKind
import app.configmonitor.data.uptimePercent
import app.configmonitor.util.Format

@Composable
fun errorLabel(code: ErrorCode?): String = stringResource(
    when (code) {
        ErrorCode.DNS -> R.string.error_dns
        ErrorCode.TCP_TIMEOUT -> R.string.error_tcp_timeout
        ErrorCode.REFUSED -> R.string.error_refused
        ErrorCode.TIMEOUT -> R.string.error_timeout
        ErrorCode.TLS -> R.string.error_tls
        ErrorCode.REJECTED -> R.string.error_rejected
        ErrorCode.OTHER, null -> R.string.error_other
    }
)

@Composable
fun DetailScreen(id: String, vm: MainViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sc = LocalStatusColors.current
    val fa = Format.isFa(ctx)
    val clipboard = LocalClipboardManager.current
    val flow = remember(id) { vm.detail(id) }
    val state by flow.collectAsStateWithLifecycle()
    var askDelete by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    val detail = state
    if (detail == null) {
        // either still loading or the target was deleted
        val gone = App.instance.targets.get(id) == null
        LaunchedEffect(gone) { if (gone) onBack() }
        Box(Modifier.fillMaxSize().background(cs.background))
        return
    }

    val st = detail.status
    val manual = detail.manual
    val useManual = manual != null && (st == null || manual.at > st.lastCheckAt || !detail.running)
    val latency: Long? = if (useManual) manual?.latencyMs else st?.latencyMs
    val unsupportedReason: String? =
        if (useManual) manual?.unsupported else st?.takeIf { it.unsupported }?.unsupportedReason
    val failed = if (useManual) {
        manual?.latencyMs == null && manual?.unsupported == null
    } else {
        (st?.consecutiveFailures ?: 0) > 0
    }
    val errCode: ErrorCode? = if (useManual) manual?.error else st?.error
    val errDetail: String? = if (useManual) manual?.detail else st?.errorDetail
    val checkedAt: Long = if (useManual) (manual?.at ?: 0L) else (st?.lastCheckAt ?: 0L)

    val health = when {
        !detail.target.enabled -> Health.PAUSED
        unsupportedReason != null -> Health.UNSUPPORTED
        failed -> Health.DOWN
        latency != null -> if (latency > SLOW_MS) Health.SLOW else Health.UP
        else -> Health.UNKNOWN
    }
    val tone = when (health) {
        Health.UP -> sc.up
        Health.SLOW -> sc.slow
        Health.DOWN -> sc.down
        else -> cs.onSurfaceVariant
    }
    val statusText = stringResource(
        when (health) {
            Health.UP -> R.string.status_up
            Health.SLOW -> R.string.status_slow
            Health.DOWN -> R.string.status_down
            Health.PAUSED -> R.string.status_paused
            Health.UNSUPPORTED -> R.string.status_unsupported
            Health.UNKNOWN -> R.string.status_unknown
        }
    )

    val samples = st?.samples ?: emptyList()
    val valid = samples.filter { it >= 0 }
    val uptime = st?.uptimePercent()
    val now = System.currentTimeMillis()

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // ------------------------------------------------------------ top bar
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
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        detail.target.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ProtocolBadge(detail.badge)
                }
                Text(
                    detail.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current, fontSize = 11.sp),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = startAlign(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconBox(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), tint = cs.onSurfaceVariant)
            }
        }

        // ------------------------------------------------------------ content
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel(Modifier.fillMaxWidth(), color = if (health == Health.DOWN) sc.downContainer else cs.surface,
                borderColor = if (health == Health.DOWN) sc.downBorder else cs.outline) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PulsingDot(tone, pulsing = health == Health.DOWN)
                            Text(statusText, style = MaterialTheme.typography.labelLarge, color = tone)
                            if (checkedAt > 0L) {
                                Text(
                                    "· " + Format.ago(ctx, checkedAt, now),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                            if (latency != null) {
                                AnimatedNumber(latency.toInt(), fa, MaterialTheme.typography.displaySmall, tone)
                                Text(
                                    " ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            } else {
                                Text("—", style = MaterialTheme.typography.displaySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    if (uptime != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(stringResource(R.string.uptime_label), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            Text(Format.percent(uptime, fa), style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                        }
                    }
                }
            }

            if (health == Health.DOWN) {
                Panel(Modifier.fillMaxWidth(), color = sc.downContainer, borderColor = sc.downBorder) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.last_error, errorLabel(errCode)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = sc.downText,
                        )
                        if (!errDetail.isNullOrBlank()) {
                            Text(
                                errDetail,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current, fontSize = 11.sp),
                                color = cs.onSurfaceVariant,
                                textAlign = startAlign(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            if (health == Health.UNSUPPORTED && unsupportedReason != null) {
                StatusLine(ok = true, warn = true, text = stringResource(R.string.link_unsupported, unsupportedReason))
            }

            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.chart_title, Format.num(samples.size, fa)),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                    if (samples.isEmpty()) {
                        Text(
                            stringResource(R.string.chart_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        LatencyChart(samples, Modifier.padding(top = 8.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val avg = if (valid.isEmpty()) null else valid.average().toInt()
                StatCell(stringResource(R.string.stat_avg), avg, fa, Modifier.weight(1f))
                StatCell(stringResource(R.string.stat_best), valid.minOrNull(), fa, Modifier.weight(1f))
                StatCell(stringResource(R.string.stat_worst), valid.maxOrNull(), fa, Modifier.weight(1f))
            }

            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.events_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                    if (detail.events.isEmpty()) {
                        Text(stringResource(R.string.events_empty), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    detail.events.take(5).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatusDot(if (e.type == EventType.DOWN) sc.down else sc.up)
                            val label = when {
                                e.type == EventType.DOWN -> stringResource(R.string.event_down)
                                e.durationMs > 0L -> stringResource(R.string.event_up_after, Format.duration(ctx, e.durationMs))
                                else -> stringResource(R.string.event_up)
                            }
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                Format.eventTime(ctx, e.at, now),
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            ToggleRow(stringResource(R.string.enabled_monitor), detail.target.enabled) { v -> vm.setEnabled(id, v) }
            Spacer(Modifier.height(8.dp))
        }

        // ------------------------------------------------------------ actions
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = stringResource(if (detail.testing) R.string.action_testing else R.string.action_test),
                onClick = { vm.testNow(id) },
                enabled = !detail.testing,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineAction(stringResource(R.string.action_copy), cs.onSurface, Modifier.weight(1f)) {
                    clipboard.setText(AnnotatedString(detail.target.raw))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                if (detail.target.kind == TargetKind.CONFIG) {
                    OutlineAction(stringResource(R.string.action_qr), cs.onSurface, Modifier.weight(1f)) { showQr = true }
                }
                OutlineAction(stringResource(R.string.delete), sc.downText, Modifier.weight(1f)) { askDelete = true }
            }
        }
    }

    if (showQr) {
        QrDialog(detail.target.name, detail.target.raw) { showQr = false }
    }
    if (askDelete) {
        ConfirmDeleteDialog(
            name = detail.target.name,
            onConfirm = {
                askDelete = false
                vm.delete(id)
            },
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun StatCell(label: String, value: Int?, fa: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(
            if (value == null) "—" else Format.num(value, fa) + " ms",
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun OutlineAction(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(cs.surface)
            .border(1.dp, cs.outline, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

@Composable
private fun QrDialog(name: String, text: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bitmap = remember(text) { QrCode.bitmap(text) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.action_qr),
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(10.dp),
                )
            } else {
                Text(stringResource(R.string.qr_too_long), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
    }
}

/** Line chart of the last checks; failed checks are red dots on the baseline. The line draws itself in. */
@Composable
private fun LatencyChart(samples: List<Int>, modifier: Modifier = Modifier) {
    val sc = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(if (started) 1f else 0f, motion(900), label = "chart")
    val lineColor = sc.up
    val slowColor = sc.slow
    val failColor = sc.down
    val gridColor = cs.outline

    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        val pad = 10.dp.toPx()
        val valid = samples.filter { it >= 0 }
        val maxV = maxOf(valid.maxOrNull() ?: 0, 200).toFloat() * 1.15f
        val n = samples.size
        fun px(i: Int): Float = if (n <= 1) w else w * i / (n - 1).toFloat()
        fun py(v: Int): Float = h - pad - (v / maxV) * (h - 2 * pad)

        drawLine(gridColor, Offset(0f, h - pad), Offset(w, h - pad), strokeWidth = 1.dp.toPx())
        val ty = py(SLOW_MS.toInt())
        if (ty > pad / 2f) {
            drawLine(
                slowColor.copy(alpha = 0.55f), Offset(0f, ty), Offset(w, ty),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
        }
        clipRect(right = w * progress) {
            val path = Path()
            var open = false
            for (i in samples.indices) {
                val v = samples[i]
                if (v < 0) {
                    open = false
                } else if (!open) {
                    path.moveTo(px(i), py(v))
                    open = true
                } else {
                    path.lineTo(px(i), py(v))
                }
            }
            drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (i in samples.indices) {
                val v = samples[i]
                if (v < 0) {
                    drawCircle(failColor, 3.5.dp.toPx(), Offset(px(i), h - pad))
                } else if (i == n - 1) {
                    drawCircle(lineColor, 4.dp.toPx(), Offset(px(i), py(v)))
                }
            }
        }
    }
}
