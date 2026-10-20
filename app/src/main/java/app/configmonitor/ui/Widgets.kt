package app.configmonitor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.configmonitor.util.Format

/** An animation that jumps straight to the end when "reduce motion" is on. */
@Composable
fun <T> motion(ms: Int = 300): AnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(ms)

/** Small mono tag with the protocol name (VLESS, VMESS, ...). */
@Composable
fun ProtocolBadge(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = LocalMono.current,
            fontSize = 9.sp,
            letterSpacing = 0.4.sp,
        ),
        color = cs.onSurfaceVariant,
        modifier = Modifier
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else Color.Transparent, motion(200), label = "pillBg")
    val fg by animateColorAsState(if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant, motion(200), label = "pillFg")
    val shape = RoundedCornerShape(99.dp)
    Box(
        Modifier
            .height(38.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) cs.primary.copy(alpha = 0.45f) else cs.outline, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** Status dot; when [pulsing] a soft ring spreads from it (only used for failing targets). */
@Composable
fun PulsingDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val c by animateColorAsState(color, motion(350), label = "dot")
    Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (pulsing && !reduce) PulseRing(c)
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
    }
}

@Composable
private fun PulseRing(color: Color) {
    val transition = rememberInfiniteTransition(label = "ring")
    val v by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "progress",
    )
    Box(
        Modifier
            .size(18.dp)
            .graphicsLayer {
                val s = 0.55f + 0.45f * v
                scaleX = s
                scaleY = s
                alpha = 0.55f * (1f - v)
            }
            .clip(CircleShape)
            .background(color)
    )
}

/** A number that counts up / down to its new value. */
@Composable
fun AnimatedNumber(value: Int, fa: Boolean, style: TextStyle, color: Color) {
    val shown by animateIntAsState(value, motion(500), label = "number")
    Text(Format.num(shown, fa), style = style, color = color)
}

@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = cs.onSurface,
            activeTrackColor = cs.primary,
            inactiveTrackColor = cs.outline,
            activeTickColor = cs.primary,
            inactiveTickColor = cs.outline,
        ),
    )
}

/** QR-scanner glyph (four corners and a bar), drawn by hand because the core icon set has none. */
@Composable
fun QrIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = 2f * u
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = w, cap = StrokeCap.Round)
        }
        seg(4f, 8f, 4f, 4f); seg(4f, 4f, 8f, 4f)
        seg(16f, 4f, 20f, 4f); seg(20f, 4f, 20f, 8f)
        seg(20f, 16f, 20f, 20f); seg(20f, 20f, 16f, 20f)
        seg(8f, 20f, 4f, 20f); seg(4f, 20f, 4f, 16f)
        seg(8f, 12f, 16f, 12f)
    }
}

/** Flashlight glyph: a bolt, filled when the torch is on. */
@Composable
fun TorchIcon(color: Color, on: Boolean, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val path = Path().apply {
            moveTo(13f * u, 2f * u)
            lineTo(5f * u, 13.5f * u)
            lineTo(11f * u, 13.5f * u)
            lineTo(10f * u, 22f * u)
            lineTo(19f * u, 10f * u)
            lineTo(13f * u, 10f * u)
            close()
        }
        if (on) {
            drawPath(path, color, style = Fill)
        } else {
            drawPath(path, color, style = Stroke(width = 1.8f * u))
        }
    }
}
