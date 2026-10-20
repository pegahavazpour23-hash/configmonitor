package app.configmonitor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.configmonitor.App
import app.configmonitor.R
import app.configmonitor.data.AlertItem
import app.configmonitor.data.ReferenceNote
import app.configmonitor.data.referenceNote
import app.configmonitor.service.MonitorRuntime
import app.configmonitor.util.Format
import kotlinx.coroutines.delay

// The alert screen always uses its own dark-red palette, whatever theme the app is set to.
private val AlertBg = Color(0xFF14090B)
private val AlertText = Color(0xFFF6E9EB)
private val AlertMuted = Color(0xFFC9A5AA)
private val AlertDim = Color(0xFF9C7A80)
private val AlertAccent = Color(0xFFFF5C6A)
private val AlertSoft = Color(0xFFFF8A94)
private val AlertCard = Color(0xFF201014)
private val AlertBorder = Color(0xFF3A1A20)
private val AlertOk = Color(0xFF2DD4A0)

@Composable
fun AlertScreen(onAck: () -> Unit) {
    val app = App.instance
    val alert by MonitorRuntime.alert.collectAsStateWithLifecycle()
    val statuses by MonitorRuntime.statuses.collectAsStateWithLifecycle()
    val targets by app.targets.targets.collectAsStateWithLifecycle()
    val fa = Format.isFa(LocalContext.current)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }

    val items = alert?.items ?: emptyList()
    val ref = referenceNote(targets, statuses)
    val worst = items.maxOfOrNull { statuses[it.id]?.consecutiveFailures ?: 0 } ?: 0

    Column(
        Modifier
            .fillMaxSize()
            .background(AlertBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Rings()
        Spacer(Modifier.height(4.dp))
        Text(
            if (items.size <= 1) {
                stringResource(R.string.alert_title_one)
            } else {
                stringResource(R.string.alert_title_many, Format.num(items.size, fa))
            },
            style = MaterialTheme.typography.headlineMedium,
            color = AlertText,
            textAlign = TextAlign.Center,
        )
        if (worst > 0) {
            Text(
                stringResource(R.string.alert_subtitle, Format.num(worst, fa)),
                style = MaterialTheme.typography.bodyMedium,
                color = AlertMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { item ->
                val st = statuses[item.id]
                val recovered = st != null && st.lastCheckAt > 0L && st.consecutiveFailures == 0
                AlertRow(item, recovered, now)
            }
            val note = when (ref.note) {
                ReferenceNote.NONE -> null
                ReferenceNote.REFERENCE_OK -> stringResource(R.string.alert_ref_ok, ref.name ?: "")
                ReferenceNote.PHONE_INTERNET -> stringResource(R.string.alert_ref_down)
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlertMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        Text(
            stringResource(R.string.alert_hint),
            style = MaterialTheme.typography.bodySmall,
            color = AlertDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        )
        Button(
            onClick = onAck,
            modifier = Modifier.fillMaxWidth().height(68.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AlertText, contentColor = AlertBg),
        ) {
            Text(stringResource(R.string.alert_ack), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Rings() {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "scale",
    )
    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radii = listOf(124.dp, 90.dp, 60.dp)
            val alphas = listOf(0.14f, 0.26f, 0.45f)
            radii.forEachIndexed { i, r ->
                val scale = if (i == 0) pulse else 1f
                drawCircle(
                    color = AlertAccent.copy(alpha = alphas[i]),
                    radius = r.toPx() * scale,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(AlertAccent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = AlertBg, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun AlertRow(item: AlertItem, recovered: Boolean, now: Long) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AlertCard)
            .border(1.dp, AlertBorder, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(if (recovered) AlertOk else AlertAccent)
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                color = AlertText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = startAlign(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current, fontSize = 11.sp),
                color = AlertDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = startAlign(),
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            )
        }
        Text(
            if (recovered) stringResource(R.string.alert_recovered) else Format.duration(ctx, now - item.since),
            style = MaterialTheme.typography.labelMedium,
            color = if (recovered) AlertOk else AlertSoft,
        )
    }
}
