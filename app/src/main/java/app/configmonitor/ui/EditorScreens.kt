package app.configmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.configmonitor.R
import app.configmonitor.core.AddressParser
import app.configmonitor.core.LinkParser
import app.configmonitor.core.ParseError
import app.configmonitor.core.ParseResult
import app.configmonitor.core.ProxyConfig
import app.configmonitor.data.Target

@Composable
fun IconBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(cs.surface)
            .border(1.dp, cs.outline, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
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
            Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            content = content,
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = bottom,
        )
    }
}

@Composable
fun StatusLine(ok: Boolean, text: String, warn: Boolean = false) {
    val sc = LocalStatusColors.current
    val color = when {
        ok && warn -> sc.slow
        ok -> sc.up
        else -> sc.downText
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok && !warn) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            AppSwitch(checked = checked, onChange = onChange)
        }
    }
}

@Composable
fun ConfirmDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
        title = { Text(stringResource(R.string.delete_confirm_title, name), color = cs.onSurface) },
        text = { Text(stringResource(R.string.delete_confirm_body), color = cs.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = LocalStatusColors.current.downText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = cs.onSurface)
            }
        },
    )
}

fun errorRes(e: ParseError): Int = when (e) {
    ParseError.EMPTY -> R.string.err_empty
    ParseError.UNKNOWN_SCHEME -> R.string.err_unknown_scheme
    ParseError.CREDENTIAL -> R.string.err_credential
    ParseError.HOST -> R.string.err_host
    ParseError.PORT -> R.string.err_port
    ParseError.FORMAT -> R.string.err_format
}

@Composable
fun InfoGrid(c: ProxyConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCell(stringResource(R.string.info_host), c.host, Modifier.weight(1f))
            InfoCell(stringResource(R.string.info_port), c.port.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCell(stringResource(R.string.info_protocol), c.protocol.badge, Modifier.weight(1f))
            InfoCell(stringResource(R.string.info_transport), c.security + " · " + c.network, Modifier.weight(1f))
        }
    }
}

/** Edit the name and the link of an existing config. (New configs are added from the Add screen.) */
@Composable
fun ConfigEditorScreen(editing: Target, vm: MainViewModel, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf(editing.name) }
    var link by remember { mutableStateOf(editing.raw) }
    var enabled by remember { mutableStateOf(editing.enabled) }

    val result = remember(link) { LinkParser.parse(link) }
    val ok = (result as? ParseResult.Ok)?.config

    EditorScaffold(
        title = stringResource(R.string.editor_edit_config),
        onBack = onClose,
        bottom = {
            PrimaryButton(
                text = stringResource(R.string.save),
                enabled = ok != null,
                onClick = {
                    vm.saveConfig(editing.id, name, link, enabled)
                    onClose()
                },
            )
        },
        content = {
            FieldLabel(stringResource(R.string.field_name))
            AppTextField(value = name, onValueChange = { name = it })
            FieldLabel(stringResource(R.string.field_link))
            AppTextField(
                value = link,
                onValueChange = { link = it },
                mono = true,
                ltr = true,
                singleLine = false,
                minLines = 4,
                isError = link.isNotBlank() && ok == null,
            )
            TextButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }) {
                Text(stringResource(R.string.paste), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
            if (link.isNotBlank()) {
                if (ok == null) {
                    val e = (result as? ParseResult.Err)?.error ?: ParseError.FORMAT
                    StatusLine(ok = false, text = stringResource(errorRes(e)))
                } else if (ok.unsupported != null) {
                    StatusLine(ok = true, warn = true, text = stringResource(R.string.link_unsupported, ok.unsupported))
                } else {
                    StatusLine(ok = true, text = stringResource(R.string.link_valid))
                }
            }
            if (ok != null) {
                Spacer(Modifier.height(14.dp))
                InfoGrid(ok)
            }
            Spacer(Modifier.height(18.dp))
            ToggleRow(stringResource(R.string.enabled_config), enabled) { enabled = it }
            Spacer(Modifier.height(12.dp))
        },
    )
}

/** Add / edit a plain address such as google.com or 1.1.1.1:443. */
@Composable
fun AddressEditorScreen(editing: Target?, vm: MainViewModel, onClose: () -> Unit) {
    val isEdit = editing != null
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var address by remember { mutableStateOf(editing?.raw ?: "") }
    var enabled by remember { mutableStateOf(editing?.enabled ?: true) }
    var askDelete by remember { mutableStateOf(false) }

    val parsed = remember(address) { AddressParser.parse(address) }
    val blank = address.isBlank()

    EditorScaffold(
        title = stringResource(if (isEdit) R.string.editor_edit_address else R.string.editor_add_address),
        onBack = onClose,
        bottom = {
            PrimaryButton(
                text = stringResource(if (isEdit) R.string.save else R.string.add_address),
                enabled = parsed != null,
                onClick = {
                    vm.saveAddress(editing?.id, name, address, enabled)
                    onClose()
                },
            )
            if (isEdit) DangerButton(stringResource(R.string.delete_address)) { askDelete = true }
        },
        content = {
            FieldLabel(stringResource(R.string.field_name))
            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.field_name_optional),
            )
            FieldLabel(stringResource(R.string.field_address))
            AppTextField(
                value = address,
                onValueChange = { address = it },
                mono = true,
                ltr = true,
                placeholder = stringResource(R.string.field_address_hint),
                isError = !blank && parsed == null,
                keyboardType = KeyboardType.Uri,
            )
            if (!blank) {
                Spacer(Modifier.height(10.dp))
                StatusLine(
                    ok = parsed != null,
                    text = stringResource(if (parsed != null) R.string.address_valid else R.string.err_address),
                )
            }
            if (parsed != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCell(stringResource(R.string.info_host), parsed.first, Modifier.weight(1f))
                    InfoCell(stringResource(R.string.info_port), parsed.second.toString(), Modifier.weight(1f))
                }
            }
            if (isEdit) {
                Spacer(Modifier.height(18.dp))
                ToggleRow(stringResource(R.string.enabled_address), enabled) { enabled = it }
            }
            Spacer(Modifier.height(12.dp))
        },
    )

    if (askDelete && editing != null) {
        ConfirmDeleteDialog(
            name = editing.name,
            onConfirm = {
                vm.delete(editing.id)
                askDelete = false
                onClose()
            },
            onDismiss = { askDelete = false },
        )
    }
}
