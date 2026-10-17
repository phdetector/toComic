package com.tocomic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tocomic.app.data.ChinaJoyData
import com.tocomic.app.data.TripStore
import com.tocomic.app.model.Booth
import com.tocomic.app.model.BoothState
import com.tocomic.app.model.BoothUi
import com.tocomic.app.model.CustomBooth
import com.tocomic.app.model.Hall
import com.tocomic.app.model.TripSnapshot
import com.tocomic.app.model.TripTab
import com.tocomic.app.ui.ToComicTheme
import com.tocomic.app.ui.CodeFont
import com.tocomic.app.ui.RemoteLogo
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToComicTheme {
                TripAppScreen()
            }
        }
    }
}

@Composable
private fun TripAppScreen() {
    val context = LocalContext.current
    val store = remember { TripStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val states = remember { mutableStateMapOf<String, BoothState>() }
    val customBooths = remember { mutableStateListOf<CustomBooth>() }
    val officialBoothIds = remember { ChinaJoyData.booths.map { it.id }.toSet() }
    var currentHall by remember { mutableStateOf(ChinaJoyData.halls.first()) }
    var currentTab by remember { mutableStateOf(TripTab.Overview) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showHallPicker by remember { mutableStateOf(false) }
    var showFileMenu by remember { mutableStateOf(false) }
    var showAddBooth by remember { mutableStateOf(false) }

    fun currentSnapshot(): TripSnapshot = TripSnapshot(states.toMap(), customBooths.toList())

    fun saveSnapshot() {
        store.saveSnapshot(currentSnapshot())
    }

    fun upsertCustomBooth(booth: CustomBooth) {
        val index = customBooths.indexOfFirst { it.id == booth.id }
        if (index >= 0) customBooths[index] = booth else customBooths.add(booth)
    }

    fun syncCustomBoothState(id: String, state: BoothState) {
        val index = customBooths.indexOfFirst { it.id == id }
        if (index >= 0) {
            val booth = customBooths[index]
            customBooths[index] = booth.copy(
                note = state.note,
                planned = state.planned,
                planOrder = state.planOrder
            )
        }
    }

    fun updateState(id: String, transform: (BoothState) -> BoothState) {
        val next = transform(states[id] ?: BoothState())
        states[id] = next
        syncCustomBoothState(id, next)
        saveSnapshot()
    }

    LaunchedEffect(Unit) {
        val snapshot = store.loadSnapshot()
        states.putAll(snapshot.states)
        customBooths.addAll(snapshot.customBooths)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            store.exportTo(uri, currentSnapshot())
            snackbar.showSnackbar("已导出 JSON")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val imported = store.importFrom(uri)
            val importedCustomIds = imported.customBooths.map { it.id }.toSet()
            val mergedCustomBooths = customBooths.filter { it.id !in importedCustomIds } + imported.customBooths
            customBooths.clear()
            customBooths.addAll(mergedCustomBooths)

            val validStateIds = officialBoothIds + customBooths.map { it.id }
            imported.states.forEach { (id, state) ->
                if (id in validStateIds) {
                    states[id] = state
                    syncCustomBoothState(id, state)
                }
            }
            saveSnapshot()
            snackbar.showSnackbar("已导入 ${imported.states.size + imported.customBooths.size} 条记录")
        }
    }

    val customBoothSnapshot = customBooths.toList()
    val items = remember(states.toMap(), customBoothSnapshot, currentHall, currentTab, query) {
        buildList(currentHall.id, currentTab, query, states, customBoothSnapshot)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopBar(
                hall = currentHall,
                searching = searching,
                query = query,
                onQueryChange = { query = store.normalizePunctuation(it) },
                onHallClick = { showHallPicker = true },
                onAddClick = { showAddBooth = true },
                onSearchClick = { searching = true },
                onCloseSearch = { searching = false; query = "" },
                onFileClick = { showFileMenu = true }
            )
        },
        bottomBar = {
            BottomTabs(currentTab = currentTab, onSelect = { currentTab = it })
        }
    ) { padding ->
        BoothList(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            tab = currentTab,
            items = items,
            onTogglePlan = { booth ->
                updateState(booth.id) { state ->
                    if (state.planned) state.copy(planned = false, planOrder = Int.MAX_VALUE)
                    else state.copy(planned = true, planOrder = nextPlanOrder(states))
                }
            },
            onSave = { booth, name, note ->
                val cleanName = store.normalizePunctuation(name).trim()
                val cleanNote = store.normalizePunctuation(note)
                val customIndex = customBooths.indexOfFirst { it.id == booth.id }
                if (customIndex >= 0) {
                    val custom = customBooths[customIndex]
                    customBooths[customIndex] = custom.copy(
                        name = cleanName.ifBlank { custom.name },
                        note = cleanNote
                    )
                    updateState(booth.id) { state -> state.copy(customName = null, note = cleanNote) }
                } else {
                    updateState(booth.id) { state ->
                        state.copy(
                            customName = cleanName.takeIf { it.isNotBlank() && it != booth.name },
                            note = cleanNote
                        )
                    }
                }
            },
            onMove = { from, to ->
                val plan = items.toMutableList()
                val moved = plan.removeAt(from)
                plan.add(to, moved)
                plan.forEachIndexed { index, ui ->
                    val next = (states[ui.booth.id] ?: BoothState()).copy(planned = true, planOrder = index)
                    states[ui.booth.id] = next
                    syncCustomBoothState(ui.booth.id, next)
                }
                saveSnapshot()
            }
        )
    }

    if (showHallPicker) {
        HallPicker(current = currentHall, onDismiss = { showHallPicker = false }) { hall ->
            currentHall = hall
            showHallPicker = false
        }
    }
    if (showFileMenu) {
        FileMenu(
            onDismiss = { showFileMenu = false },
            onImport = {
                showFileMenu = false
                importLauncher.launch(arrayOf("application/json", "text/plain"))
            },
            onExport = {
                showFileMenu = false
                exportLauncher.launch("tocomic-preview.json")
            }
        )
    }
    if (showAddBooth) {
        AddBoothDialog(
            currentHall = currentHall,
            normalize = store::normalizePunctuation,
            onDismiss = { showAddBooth = false },
            onSave = { hallId, boothCode, name, note, planned ->
                val order = if (planned) nextPlanOrder(states) else Int.MAX_VALUE
                val custom = CustomBooth(
                    id = store.customBoothId(hallId, boothCode, name),
                    hallId = hallId,
                    boothCode = boothCode,
                    name = name,
                    note = note,
                    planned = planned,
                    planOrder = order
                )
                upsertCustomBooth(custom)
                states[custom.id] = custom.asState()
                saveSnapshot()
                showAddBooth = false
                scope.launch { snackbar.showSnackbar("已添加 ${custom.hallId}-${custom.boothCode}") }
            }
        )
    }
}

private fun buildList(
    hallId: String,
    tab: TripTab,
    query: String,
    states: Map<String, BoothState>,
    customBooths: List<CustomBooth>
): List<BoothUi> {
    val normalized = query.trim().lowercase()
    val hallById = ChinaJoyData.halls.associateBy { it.id }
    val customStates = customBooths.associate { it.id to it.asState() }
    val allBooths = ChinaJoyData.booths + customBooths.map { it.asBooth() }
    return allBooths
        .asSequence()
        .filter { query.isNotBlank() || it.hallId == hallId }
        .map { booth -> BoothUi(booth, states[booth.id] ?: customStates[booth.id] ?: BoothState()) }
        .filter { tab == TripTab.Overview || it.state.planned }
        .filter { ui ->
            val hall = hallById[ui.booth.hallId]
            normalized.isBlank() || listOf(
                ui.displayCode,
                ui.booth.hallId,
                ui.booth.boothCode,
                ui.displayName,
                hall?.title.orEmpty(),
                hall?.subtitle.orEmpty(),
                ui.state.note
            )
                .any { it.lowercase().contains(normalized) }
        }
        .sortedWith(if (tab == TripTab.Plan) planComparator else boothNumberComparator)
        .toList()
}

private val planComparator = Comparator<BoothUi> { left, right ->
    val order = left.state.planOrder.compareTo(right.state.planOrder)
    if (order != 0) order else boothNumberComparator.compare(left, right)
}

private val boothNumberComparator = Comparator<BoothUi> { left, right ->
    val hall = naturalCompare(left.booth.hallId, right.booth.hallId)
    if (hall != 0) hall else {
        val code = naturalCompare(left.booth.boothCode, right.booth.boothCode)
        if (code != 0) code else left.displayName.compareTo(right.displayName, ignoreCase = true)
    }
}

private val naturalTokenRegex = Regex("\\d+|\\D+")

private fun naturalCompare(left: String, right: String): Int {
    val leftTokens = naturalTokenRegex.findAll(left).map { it.value }.toList()
    val rightTokens = naturalTokenRegex.findAll(right).map { it.value }.toList()
    val size = minOf(leftTokens.size, rightTokens.size)
    for (index in 0 until size) {
        val leftToken = leftTokens[index]
        val rightToken = rightTokens[index]
        val comparison = if (leftToken.all { it.isDigit() } && rightToken.all { it.isDigit() }) {
            compareNumericToken(leftToken, rightToken)
        } else {
            leftToken.compareTo(rightToken, ignoreCase = true)
        }
        if (comparison != 0) return comparison
    }
    return leftTokens.size.compareTo(rightTokens.size)
}

private fun compareNumericToken(left: String, right: String): Int {
    val leftNumber = left.trimStart('0').ifEmpty { "0" }
    val rightNumber = right.trimStart('0').ifEmpty { "0" }
    val length = leftNumber.length.compareTo(rightNumber.length)
    if (length != 0) return length
    val value = leftNumber.compareTo(rightNumber)
    if (value != 0) return value
    return left.length.compareTo(right.length)
}

private fun nextPlanOrder(states: Map<String, BoothState>): Int =
    (states.values.filter { it.planned && it.planOrder != Int.MAX_VALUE }.maxOfOrNull { it.planOrder } ?: -1) + 1

@Composable
private fun TopBar(
    hall: Hall,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onHallClick: () -> Unit,
    onAddClick: () -> Unit,
    onSearchClick: () -> Unit,
    onCloseSearch: () -> Unit,
    onFileClick: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    placeholder = { Text("搜索展馆号, 名称, 备注") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                IconButton(onClick = onCloseSearch) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭搜索")
                }
            } else {
                Capsule(
                    text = "${hall.id} ${hall.title}",
                    color = Color(hall.accent),
                    modifier = Modifier.weight(1f),
                    onClick = onHallClick
                )
                IconButton(onClick = onAddClick) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "添加展台")
                }
                IconButton(onClick = onSearchClick) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = "搜索")
                }
                IconButton(onClick = onFileClick) {
                    Icon(painterResource(R.drawable.ic_file), contentDescription = "导入导出")
                }
            }
        }
    }
}

@Composable
private fun BottomTabs(currentTab: TripTab, onSelect: (TripTab) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TripTab.values().forEach { tab ->
                val selected = tab == currentTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoothList(
    modifier: Modifier,
    tab: TripTab,
    items: List<BoothUi>,
    onTogglePlan: (Booth) -> Unit,
    onSave: (Booth, String, String) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (tab == TripTab.Plan) "收藏展台后会出现在这里" else "没有匹配的展台",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.booth.id }) { index, item ->
            BoothCard(
                modifier = Modifier
                    .animateItemPlacement()
                    .then(if (tab == TripTab.Plan) Modifier.dragReorder(index, items.size, onMove) else Modifier),
                item = item,
                onTogglePlan = { onTogglePlan(item.booth) },
                onSave = { name, note -> onSave(item.booth, name, note) }
            )
        }
    }
}

private fun Modifier.dragReorder(index: Int, count: Int, onMove: (Int, Int) -> Unit): Modifier = pointerInput(index, count) {
    var current = index
    var accumulatedY = 0f
    detectDragGesturesAfterLongPress(
        onDrag = { change, dragAmount ->
            change.consume()
            accumulatedY += dragAmount.y
            if (accumulatedY > 34 && current < count - 1) {
                onMove(current, current + 1)
                current += 1
                accumulatedY -= 34
            } else if (accumulatedY < -34 && current > 0) {
                onMove(current, current - 1)
                current -= 1
                accumulatedY += 34
            }
        }
    )
}

@Composable
private fun BoothCard(
    modifier: Modifier,
    item: BoothUi,
    onTogglePlan: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var editing by remember(item.booth.id) { mutableStateOf(false) }
    var name by remember(item.displayName) { mutableStateOf(item.displayName) }
    var note by remember(item.state.note) { mutableStateOf(item.state.note) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(216.dp)
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Capsule(text = item.displayCode, color = MaterialTheme.colorScheme.primary, onClick = null)
                if (editing) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                } else {
                    Text(
                        text = item.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = {
                    if (editing) onSave(name, note)
                    editing = !editing
                }) {
                    Icon(painterResource(if (editing) R.drawable.ic_save else R.drawable.ic_edit), contentDescription = if (editing) "保存" else "编辑")
                }
                FilledTonalIconButton(
                    onClick = onTogglePlan,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (item.state.planned) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        painterResource(R.drawable.ic_heart),
                        contentDescription = "收藏",
                        tint = if (item.state.planned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RemoteLogo(url = item.booth.logoUrl, label = item.displayName)
                if (editing) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("备注") }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = item.state.note.ifBlank { "备注" },
                            color = if (item.state.note.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Capsule(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(color.copy(alpha = 0.16f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = CodeFont,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HallPicker(current: Hall, onDismiss: () -> Unit, onPick: (Hall) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择展馆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChinaJoyData.halls.forEach { hall ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (hall == current) Color(hall.accent).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(hall) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${hall.id} ${hall.title}", fontWeight = FontWeight.SemiBold)
                        Text(hall.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun AddBoothDialog(
    currentHall: Hall,
    normalize: (String) -> String,
    onDismiss: () -> Unit,
    onSave: (hallId: String, boothCode: String, name: String, note: String, planned: Boolean) -> Unit
) {
    var hallId by remember(currentHall.id) { mutableStateOf(currentHall.id) }
    var boothCode by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var planned by remember { mutableStateOf(false) }
    val cleanHallId = normalize(hallId).trim().uppercase()
    val cleanBoothCode = normalize(boothCode).trim().uppercase()
    val cleanName = normalize(name).trim()
    val cleanNote = normalize(note)
    val canSave = cleanHallId.isNotBlank() && cleanBoothCode.isNotBlank() && cleanName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加展台") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = hallId,
                    onValueChange = { hallId = normalize(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("展馆") }
                )
                OutlinedTextField(
                    value = boothCode,
                    onValueChange = { boothCode = normalize(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("展台号") }
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = normalize(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("展台名称") }
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = normalize(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp),
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("备注") }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { planned = !planned }
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(checked = planned, onCheckedChange = { planned = it })
                    Text("加入计划", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(cleanHallId, cleanBoothCode, cleanName, cleanNote, planned) }
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FileMenu(onDismiss: () -> Unit, onImport: () -> Unit, onExport: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onImport)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_import), contentDescription = "导入")
                    Text("导入 JSON")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onExport)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_export), contentDescription = "导出")
                    Text("导出 JSON")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
