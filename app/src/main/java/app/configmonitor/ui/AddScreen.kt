package app.configmonitor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.configmonitor.R
import app.configmonitor.core.LinkParser
import app.configmonitor.core.ParseError
import app.configmonitor.core.ParseResult
import app.configmonitor.core.Protocol
import app.configmonitor.util.Format
import kotlinx.coroutines.launch

/**
 * Add configs: paste links (any protocol), scan a QR code or read one from a picture,
 * or import a subscription link. Every link is checked and listed before anything is saved.
 */
@Composable
fun AddScreen(vm: MainViewModel, initialText: String, onClose: () -> Unit, onScan: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val fa = Format.isFa(ctx)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val startsAsUrl = remember { LinkParser.isSubscriptionUrl(initialText) }
    var mode by remember { mutableIntStateOf(if (startsAsUrl) 2 else 0) }
    var text by remember { mutableStateOf(if (startsAsUrl) "" else initialText) }
    var subUrl by remember { mutableStateOf(if (startsAsUrl) initialText.trim() else "") }
    var fetching by remember { mutableStateOf(false) }
    var fetchFailed by remember { mutableStateOf(false) }
    var galleryMiss by remember { mutableStateOf(false) }

    val fetch: () -> Unit = {
        if (!fetching && subUrl.isNotBlank()) {
            fetching = true
            fetchFailed = false
            scope.launch {
                val r = vm.fetchSubscription(subUrl.trim())
                fetching = false
                r.onSuccess { links ->
                    if (links.isEmpty()) {
                        fetchFailed = true
                    } else {
                        text = links.joinToString("\n")
                        mode = 0
                    }
                }.onFailure {
                    fetchFailed = true
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (startsAsUrl) fetch() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            decodeQrFromUri(ctx, uri) { result ->
                if (result == null) {
                    galleryMiss = true
                } else if (LinkParser.isSubscriptionUrl(result)) {
                    galleryMiss = false
                    subUrl = result.trim()
                    mode = 2
                    fetch()
                } else {
                    galleryMiss = false
                    text = if (text.isBlank()) result else text + "\n" + result
                    mode = 0
                }
            }
        }
    }

    val tokens = remember(text) {
        if (text.contains("://")) {
            text.split(Regex("\\s+")).filter { it.isNotBlank() }
        } else {
            LinkParser.extractLinks(text)
        }
    }
    val results = remember(tokens) { tokens.map { it to LinkParser.parse(it) } }
    val addable = remember(results) {
        results.filter { pair ->
            val r = pair.second
            r is ParseResult.Ok && r.config.unsupported == null
        }.map { it.first }
    }
    val invalidCount = results.size - addable.size

    EditorScaffold(
        title = stringResource(R.string.editor_add_config),
        onBack = onClose,
        bottom = {
            PrimaryButton(
                text = if (addable.size > 1) {
                    stringResource(R.string.add_n, Format.num(addable.size, fa))
                } else {
                    stringResource(R.string.add_one)
                },
                enabled = addable.isNotEmpty(),
                onClick = {
                    vm.addConfigs(addable)
                    onClose()
                },
            )
        },
        content = {
            Spacer(Modifier.height(4.dp))
            Segmented(
                options = listOf(
                    0 to stringResource(R.string.mode_link),
                    1 to stringResource(R.string.mode_qr),
                    2 to stringResource(R.string.mode_sub),
                ),
                selected = mode,
                onSelect = { mode = it },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Protocol.values().forEach { ProtocolBadge(it.badge) }
            }

            when (mode) {
                0 -> {
                    FieldLabel(stringResource(R.string.field_links))
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        mono = true,
                        ltr = true,
                        singleLine = false,
                        minLines = 5,
                        placeholder = "vless://… vmess://… trojan://… ss://…",
                    )
                    TextButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                        Text(
                            stringResource(R.string.paste),
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                        )
                    }
                }
                1 -> {
                    Spacer(Modifier.height(16.dp))
                    ActionPanel(stringResource(R.string.scan_camera), stringResource(R.string.scan_camera_hint), onScan)
                    Spacer(Modifier.height(10.dp))
                    ActionPanel(stringResource(R.string.scan_gallery), stringResource(R.string.scan_gallery_hint)) {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    if (galleryMiss) {
                        Spacer(Modifier.height(10.dp))
                        StatusLine(ok = false, text = stringResource(R.string.qr_not_found))
                    }
                }
                else -> {
                    FieldLabel(stringResource(R.string.sub_url))
                    AppTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it },
                        mono = true,
                        ltr = true,
                        placeholder = "https://…",
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (fetching) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = cs.primary, strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.sub_fetching),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    } else {
                        TextButton(onClick = fetch, enabled = subUrl.isNotBlank()) {
                            Text(
                                stringResource(R.string.sub_fetch),
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.primary,
                            )
                        }
                    }
                    if (fetchFailed) StatusLine(ok = false, text = stringResource(R.string.sub_failed))
                }
            }

            if (results.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                StatusLine(
                    ok = addable.isNotEmpty(),
                    warn = addable.isNotEmpty() && invalidCount > 0,
                    text = stringResource(
                        R.string.links_valid_count,
                        Format.num(addable.size, fa),
                        Format.num(invalidCount, fa),
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    results.take(60).forEach { (token, result) -> DetectedRow(token, result) }
                }
            }
            Spacer(Modifier.height(12.dp))
        },
    )
}

@Composable
private fun ActionPanel(title: String, hint: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetectedRow(token: String, result: ParseResult) {
    val cs = MaterialTheme.colorScheme
    val sc = LocalStatusColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(cs.surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (result) {
            is ParseResult.Ok -> {
                val c = result.config
                val unsupported = c.unsupported
                ProtocolBadge(c.protocol.badge)
                Column(Modifier.weight(1f)) {
                    Text(
                        c.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = startAlign(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (unsupported != null) stringResource(R.string.link_unsupported, unsupported) else c.summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current, fontSize = 11.sp),
                        color = if (unsupported != null) sc.slow else cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = startAlign(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Icon(
                    if (unsupported == null) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (unsupported == null) sc.up else sc.slow,
                    modifier = Modifier.size(18.dp),
                )
            }
            is ParseResult.Err -> {
                Column(Modifier.weight(1f)) {
                    Text(
                        token,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current, fontSize = 11.sp),
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = startAlign(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(errorRes(result.error)),
                        style = MaterialTheme.typography.labelSmall,
                        color = sc.downText,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = startAlign(),
                    )
                }
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = sc.downText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
