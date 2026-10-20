package app.configmonitor.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.configmonitor.R
import app.configmonitor.data.Health
import app.configmonitor.data.Target
import app.configmonitor.data.TargetKind
import app.configmonitor.util.Format
import kotlinx.coroutines.delay

enum class RowFilter { ALL, DOWN, SLOW, PAUSED }

private fun statusRank(h: Health): Int = when (h) {
    Health.DOWN -> 0
    Health.SLOW -> 1
    Health.UNSUPPORTED -> 2
    Health.UNKNOWN -> 3
    Health.UP -> 4
    Health.PAUSED -> 5
}

@Composable
fun DashboardScreen(
    vm: MainViewModel,
    onAddConfig: () -> Unit,
    onAddAddress: () -> Unit,
    onScan: () -> Unit,
    onOpen: (Target) -> Unit,
    onSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val fa = Format.isFa(ctx)
    val reduce = LocalReduceMotion.current
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }

    var tab by remember { mutableStateOf(TargetKind.CONFIG) }
    var filter by remember { mutableStateOf(RowFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // re-check permissions every time the user comes back from a system settings screen
    var permTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++ }
    val missing = remember(permTick) { Permissions.missing(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.setMonitoring(true)
    }
    val onToggle: (Boolean) -> Unit = { on ->
        if (!on) {
            vm.setMonitoring(false)
        } else if (Build.VERSION.SDK_INT >= 33 && !Permissions.granted(ctx, Permissions.Item.NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setMonitoring(true)
        }
    }

    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(Unit) {
        vm.deleted.collect { name ->
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                message = ctx.getString(R.string.deleted_named, name),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) vm.undoDelete()
        }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }

    val allRows = if (tab == TargetKind.CONFIG) ui.configs else ui.addresses
    val rows = remember(allRows, filter, query, settings.sortMode) {
        val q = query.trim()
        val filtered = allRows.filter { row ->
            val passFilter = when (filter) {
                RowFilter.ALL -> true
                RowFilter.DOWN -> row.health == Health.DOWN
                RowFilter.SLOW -> row.health == Health.SLOW
                RowFilter.PAUSED -> row.health == Health.PAUSED
            }
            passFilter && (q.isEmpty() ||
                row.target.name.contains(q, ignoreCase = true) ||
                row.subtitle.contains(q, ignoreCase = true))
        }
        when (settings.sortMode) {
            "status" -> filtered.sortedBy { statusRank(it.health) }
            "latency" -> filtered.sortedBy { it.status?.latencyMs ?: Long.MAX_VALUE }
            "name" -> filtered.sortedBy { it.target.name.lowercase() }
            else -> filtered
        }
    }
    val nDown = allRows.count { it.health == Health.DOWN }
    val nSlow = allRows.count { it.health == Health.SLOW }
    val nPaused = allRows.count { it.health == Health.PAUSED }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Header(
                    lastCheckAt = ui.lastCheckAt,
                    running = ui.running,
                    sortMode = settings.sortMode,
                    menuOpen = menuOpen,
                    onMenu = { menuOpen = it },
                    onSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    },
                    onSort = { vm.setSort(it) },
                    onPauseAll = { vm.setAllEnabled(false) },
                    onEnableAll = { vm.setAllEnabled(true) },
                    onSettings = onSettings,
                )
            }
            item(key = "hero") { Hero(ui, onToggle) }
            if (missing.isNotEmpty()) {
                item(key = "perm") {
                    InfoBanner(
                        text = stringResource(R.string.perms_banner, Format.num(missing.size, fa)),
                        actionLabel = stringResource(R.string.perms_banner_action),
                        onAction = onSettings,
                    )
                }
            }
            if (ui.coreMissing) {
                item(key = "core") {
                    InfoBanner(text = stringResource(R.string.core_missing_banner), actionLabel = null, onAction = null)
                }
            }
            item(key = "tabs") {
                Segmented(
                    options = listOf(
                        TargetKind.CONFIG to (stringResource(R.string.tab_configs) + " · " + Format.num(ui.configs.size, fa)),
                        TargetKind.ADDRESS to (stringResource(R.string.tab_addresses) + " · " + Format.num(ui.addresses.size, fa)),
                    ),
                    selected = tab,
                    onSelect = {
                        tab = it
                        filter = RowFilter.ALL
                    },
                )
            }
            item(key = "search") {
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = if (reduce) fadeIn() else fadeIn() + expandVertically(),
                    exit = if (reduce) fadeOut() else fadeOut() + shrinkVertically(),
                ) {
                    AppTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.focusRequester(searchFocus),
                        placeholder = stringResource(R.string.search_hint),
                    )
                }
            }
            item(key = "chips") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(
                        stringResource(R.string.filter_all) + " " + Format.num(allRows.size, fa),
                        filter == RowFilter.ALL,
                    ) { filter = RowFilter.ALL }
                    FilterPill(
                        stringResource(R.string.filter_down) + " " + Format.num(nDown, fa),
                        filter == RowFilter.DOWN,
                    ) { filter = RowFilter.DOWN }
                    FilterPill(
                        stringResource(R.string.filter_slow) + " " + Format.num(nSlow, fa),
                        filter == RowFilter.SLOW,
                    ) { filter = RowFilter.SLOW }
                    FilterPill(
                        stringResource(R.string.filter_paused) + " " + Format.num(nPaused, fa),
                        filter == RowFilter.PAUSED,
                    ) { filter = RowFilter.PAUSED }
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyState(tab, filtered = allRows.isNotEmpty()) }
            }
            items(rows, key = { it.target.id }) { row ->
                val itemModifier = if (reduce) {
                    Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                } else {
                    Modifier.animateItem()
                }
                SwipeRow(modifier = itemModifier, onDelete = { vm.delete(row.target.id) }) {
                    TargetRow(
                        row = row,
                        fa = fa,
                        onOpen = { onOpen(row.target) },
                        onToggle = { v -> vm.setEnabled(row.target.id, v) },
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(cs.background.copy(alpha = 0.94f))
        ) {
            SnackbarHost(snackbar, Modifier.padding(horizontal = 20.dp))
            BottomBar(
                addLabel = stringResource(if (tab == TargetKind.CONFIG) R.string.add_config else R.string.add_address),
                onAdd = { if (tab == TargetKind.CONFIG) onAddConfig() else onAddAddress() },
                onScan = onScan,
            )
        }
    }
}

@Composable
private fun BottomBar(addLabel: String, onAdd: () -> Unit, onScan: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onAdd,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(addLabel, style = MaterialTheme.typography.titleMedium)
        }
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cs.primaryContainer)
                .border(1.dp, cs.primary.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .clickableBox(stringResource(R.string.scan_qr), onScan),
            contentAlignment = Alignment.Center,
        ) {
            QrIcon(cs.primary, 26.dp)
        }
    }
}

@Composable
private fun Header(
    lastCheckAt: Long,
    running: Boolean,
    sortMode: String,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onSort: (String) -> Unit,
    onPauseAll: () -> Unit,
    onEnableAll: () -> Unit,
    onSettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onBackground,
                textAlign = startAlign(),
                modifier = Modifier.fillMaxWidth(),
            )
            LastCheckText(lastCheckAt, running)
        }
        IconBox(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search), tint = cs.onSurfaceVariant)
        }
        Box {
            IconBox(onClick = { onMenu(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more), tint = cs.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                listOf(
                    "manual" to R.string.sort_manual,
                    "status" to R.string.sort_status,
                    "latency" to R.string.sort_latency,
                    "name" to R.string.sort_name,
                ).forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = {
                            onSort(mode)
                            onMenu(false)
                        },
                        trailingIcon = {
                            if (sortMode == mode) Icon(Icons.Default.Check, contentDescription = null)
                        },
                    )
                }
                HorizontalDivider(color = cs.outline)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pause_all)) },
                    onClick = {
                        onPauseAll()
                        onMenu(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.enable_all)) },
                    onClick = {
                        onEnableAll()
                        onMenu(false)
                    },
                )
            }
        }
        IconBox(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings), tint = cs.onSurfaceVariant)
        }
    }
}

/** Ticks once a second on its own, so the rest of the dashboard does not recompose. */
@Composable
private fun LastCheckText(lastCheckAt: Long, running: Boolean) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    val text = if (running) {
        stringResource(R.string.last_check, Format.ago(ctx, lastCheckAt, now))
    } else {
        stringResource(R.string.monitoring_off_hint)
    }
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulsingDot(if (running) LocalStatusColors.current.up else cs.onSurfaceVariant.copy(alpha = 0.5f), running)
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Hero(ui: DashboardUi, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sc = LocalStatusColors.current
    val fa = Format.isFa(LocalContext.current)
    val healthy = ui.up + ui.slow
    val total = ui.enabled.coerceAtLeast(1).toFloat()
    val upFrac by animateFloatAsState(healthy / total, motion(700), label = "upFrac")
    val downFrac by animateFloatAsState(ui.down / total, motion(700), label = "downFrac")
    val trackColor = cs.outline
    val upColor = sc.up
    val downColor = sc.down

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 8.dp.toPx()
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        val topLeft = Offset(stroke / 2f, stroke / 2f)
                        drawArc(trackColor, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                        if (upFrac > 0.001f) {
                            drawArc(
                                upColor, -90f, 360f * upFrac - 4f, false, topLeft, arcSize,
                                style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                        }
                        if (downFrac > 0.001f) {
                            drawArc(
                                downColor, -90f + 360f * upFrac + 4f, 360f * downFrac - 8f, false, topLeft, arcSize,
                                style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                    AnimatedNumber(healthy, fa, MaterialTheme.typography.headlineMedium, cs.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.overall_status),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.healthy_summary, Format.num(healthy, fa), Format.num(ui.enabled, fa)),
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatePill(ui)
                        val u = ui.uptime
                        if (u != null) {
                            Pill(
                                stringResource(R.string.uptime_pill, Format.percent(u, fa)),
                                cs.surfaceVariant,
                                cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = cs.outline, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.monitoring_active),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AppSwitch(checked = ui.running, onChange = onToggle)
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

@Composable
private fun StatePill(ui: DashboardUi) {
    val cs = MaterialTheme.colorScheme
    val fa = Format.isFa(LocalContext.current)
    when {
        !ui.running || ui.enabled == 0 ->
            Pill(stringResource(R.string.pill_idle), cs.surfaceVariant, cs.onSurfaceVariant)
        ui.down > 0 ->
            Pill(stringResource(R.string.pill_down, Format.num(ui.down, fa)), cs.errorContainer, cs.onErrorContainer)
        ui.up + ui.slow == ui.enabled ->
            Pill(stringResource(R.string.pill_all_ok), cs.primaryContainer, cs.onPrimaryContainer)
        else ->
            Pill(stringResource(R.string.pill_checking), cs.surfaceVariant, cs.onSurfaceVariant)
    }
}

@Composable
private fun InfoBanner(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val sc = LocalStatusColors.current
    Panel(Modifier.fillMaxWidth(), borderColor = sc.slow.copy(alpha = 0.5f)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = sc.slow, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = cs.primary)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(kind: TargetKind, filtered: Boolean) {
    val cs = MaterialTheme.colorScheme
    val title = when {
        filtered -> R.string.no_results_title
        kind == TargetKind.CONFIG -> R.string.empty_configs_title
        else -> R.string.empty_addresses_title
    }
    val body = when {
        filtered -> R.string.no_results_body
        kind == TargetKind.CONFIG -> R.string.empty_configs_body
        else -> R.string.empty_addresses_body
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Swipe a row towards the end side to delete it (an "undo" bar appears). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(modifier: Modifier, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val sc = LocalStatusColors.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(sc.downContainer)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = sc.downText)
                }
            }
        },
        content = { content() },
    )
}

@Composable
private fun TargetRow(row: RowUi, fa: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sc = LocalStatusColors.current
    val down = row.health == Health.DOWN
    val paused = row.health == Health.PAUSED
    val dot = when (row.health) {
        Health.UP -> sc.up
        Health.SLOW -> sc.slow
        Health.DOWN -> sc.down
        else -> cs.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Panel(
        Modifier.fillMaxWidth(),
        color = if (down) sc.downContainer else cs.surface,
        borderColor = if (down) sc.downBorder else cs.outline,
        onClick = onOpen,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulsingDot(dot, pulsing = down)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        row.target.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (paused) cs.onSurfaceVariant else cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ProtocolBadge(row.badge)
                }
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMono.current, fontSize = 11.sp),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = startAlign(),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            Trailing(row, fa)
            AppSwitch(checked = row.target.enabled, onChange = onToggle)
        }
    }
}

@Composable
private fun Trailing(row: RowUi, fa: Boolean) {
    val cs = MaterialTheme.colorScheme
    val sc = LocalStatusColors.current
    val st = row.status
    when (row.health) {
        Health.UP, Health.SLOW -> {
            val color = if (row.health == Health.UP) sc.up else sc.slow
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber((st?.latencyMs ?: 0L).toInt(), fa, MaterialTheme.typography.titleSmall, color)
                Text(" ms", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        Health.DOWN -> {
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.state_down), style = MaterialTheme.typography.titleSmall, color = sc.downText)
                Text(
                    stringResource(R.string.state_failures, Format.num(st?.consecutiveFailures ?: 0, fa)),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Health.PAUSED -> {
            Text(stringResource(R.string.state_paused), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Health.UNSUPPORTED -> {
            Text(stringResource(R.string.state_unsupported), style = MaterialTheme.typography.labelMedium, color = sc.slow)
        }
        Health.UNKNOWN -> {
            val text = if (st?.checking == true) stringResource(R.string.state_waiting) else "—"
            Text(text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}
