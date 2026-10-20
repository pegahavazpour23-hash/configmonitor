package app.configmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Latin text (names, hosts) inside an RTL screen should still hug the start edge. */
@Composable
fun startAlign(): TextAlign =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/** Rounded surface with a hairline border; clickable when [onClick] is given. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val base = modifier.clip(shape).background(color).border(1.dp, borderColor, shape)
    Box(if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base) {
        content()
    }
}

@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Switch(
        checked = checked,
        onCheckedChange = { v ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onChange(v)
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = cs.onPrimary,
            checkedTrackColor = cs.primary,
            checkedBorderColor = cs.primary,
            uncheckedThumbColor = cs.onSurfaceVariant,
            uncheckedTrackColor = cs.surfaceVariant,
            uncheckedBorderColor = cs.outline,
        ),
    )
}

@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val outer = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(outer)
            .background(cs.surface)
            .border(1.dp, cs.outline, outer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) cs.outline else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) cs.onSurface else cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = cs.primary,
            contentColor = cs.onPrimary,
            disabledContainerColor = cs.surfaceVariant,
            disabledContentColor = cs.onSurfaceVariant,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = LocalStatusColors.current.downText)
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    ltr: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val cs = MaterialTheme.colorScheme
    var style = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface)
    if (mono) style = style.copy(fontFamily = LocalMono.current, fontSize = 13.sp)
    if (ltr) style = style.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left)
    val hintStyle = style
    val hint: (@Composable () -> Unit)? = if (placeholder != null) {
        { Text(placeholder, style = hintStyle, color = cs.onSurfaceVariant.copy(alpha = 0.7f)) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = style,
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        placeholder = hint,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.primary,
            unfocusedBorderColor = cs.outline,
            errorBorderColor = cs.error,
            focusedContainerColor = cs.surface,
            unfocusedContainerColor = cs.surface,
            errorContainerColor = cs.surface,
            cursorColor = cs.primary,
        ),
    )
}

@Composable
fun Stepper(value: Int, min: Int, max: Int, step: Int, label: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton("−", value > min) { onChange((value - step).coerceAtLeast(min)) }
        Text(
            label,
            modifier = Modifier.widthIn(min = 72.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        StepButton("+", value < max) { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

@Composable
fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMono.current, fontSize = 13.sp),
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = startAlign(),
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
        )
    }
}
