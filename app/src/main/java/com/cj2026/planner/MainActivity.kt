package com.cj2026.planner

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cj2026.planner.data.PlannerStore
import com.cj2026.planner.data.ProjectData
import com.cj2026.planner.data.ProjectStore
import com.cj2026.planner.data.SuggestionApi
import com.cj2026.planner.data.SuggestionStore
import com.cj2026.planner.model.Booth
import com.cj2026.planner.model.BoothState
import com.cj2026.planner.model.BoothUi
import com.cj2026.planner.model.CustomBooth
import com.cj2026.planner.model.ExpoProject
import com.cj2026.planner.model.Hall
import com.cj2026.planner.model.PlannerSnapshot
import com.cj2026.planner.model.PlannerTab
import com.cj2026.planner.model.ProjectOverrides
import com.cj2026.planner.model.Suggestion
import com.cj2026.planner.ui.ChinaJoyTheme
import com.cj2026.planner.ui.CodeFont
import com.cj2026.planner.ui.FavoriteRed
import com.cj2026.planner.ui.RemoteLogo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChinaJoyTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val projectStore = remember { ProjectStore(context) }
    val customProjects = remember { mutableStateListOf<ExpoProject>() }
    val deletedBuiltInIds = remember { mutableStateListOf<String>() }
    val builtInOverrides = remember { mutableStateMapOf<String, ProjectOverrides>() }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var showAddProject by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var projectMenuId by remember { mutableStateOf<String?>(null) }
    var editingProject by remember { mutableStateOf<ExpoProject?>(null) }
    var deletingProject by remember { mutableStateOf<ExpoProject?>(null) }

    val allProjects = remember(
        customProjects.toList(),
        deletedBuiltInIds.toSet(),
        builtInOverrides.toMap()
    ) {
        ProjectData.projects
            .filter { it.id !in deletedBuiltInIds }
            .map { builtIn ->
                val override = builtInOverrides[builtIn.id]
                if (override == null) {
                    builtIn
                } else {
                    builtIn.copy(
                        title = override.title,
                        subtitle = override.subtitle,
                        year = override.year,
                        note = override.note
                    )
                }
            } + customProjects
    }
    val project = selectedProjectId?.let { id -> allProjects.firstOrNull { it.id == id } }
    val menuProject = projectMenuId?.let { id -> allProjects.firstOrNull { it.id == id } }

    fun persistProjects() {
        projectStore.saveProjects(
            customProjects.toList(),
            deletedBuiltInIds.toSet(),
            builtInOverrides.toMap()
        )
    }

    LaunchedEffect(Unit) {
        val state = projectStore.loadProjectState()
        customProjects.addAll(state.customProjects)
        deletedBuiltInIds.addAll(state.deletedBuiltInIds)
        builtInOverrides.putAll(state.overrides)
    }

    BackHandler(enabled = showSettings || project != null) {
        if (showSettings) showSettings = false else selectedProjectId = null
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    } else if (project == null) {
        ProjectHome(
            projects = allProjects,
            onOpen = { selectedProjectId = it },
            onLongPress = { projectMenuId = it },
            onAddProject = { showAddProject = true },
            onSettings = { showSettings = true }
        )
    } else {
        PlannerApp(project = project, onBack = { selectedProjectId = null })
    }

    if (showAddProject) {
        AddProjectDialog(
            normalize = { PlannerStore.normalizePunctuation(it) },
            onDismiss = { showAddProject = false },
            onSave = { title, year, subtitle, note ->
                val newProject = ExpoProject(
                    id = "custom-${System.currentTimeMillis()}",
                    title = title,
                    subtitle = subtitle,
                    year = year,
                    note = note,
                    halls = emptyList(),
                    booths = emptyList()
                )
                customProjects.add(newProject)
                persistProjects()
                showAddProject = false
                selectedProjectId = newProject.id
            }
        )
    }

    editingProject?.let { target ->
        AddProjectDialog(
            initial = target,
            normalize = { PlannerStore.normalizePunctuation(it) },
            onDismiss = { editingProject = null },
            onSave = { title, year, subtitle, note ->
                if (ProjectData.projects.any { it.id == target.id }) {
                    builtInOverrides[target.id] = ProjectOverrides(
                        title = title,
                        subtitle = subtitle,
                        year = year,
                        note = note
                    )
                } else {
                    val index = customProjects.indexOfFirst { it.id == target.id }
                    if (index >= 0) {
                        customProjects[index] = customProjects[index].copy(
                            title = title,
                            subtitle = subtitle,
                            year = year,
                            note = note
                        )
                    }
                }
                persistProjects()
                editingProject = null
            }
        )
    }

    if (menuProject != null) {
        val target = menuProject
        ProjectActionsDialog(
            project = target,
            onEdit = {
                projectMenuId = null
                editingProject = target
            },
            onDelete = {
                projectMenuId = null
                deletingProject = target
            },
            onDismiss = { projectMenuId = null }
        )
    }

    deletingProject?.let { target ->
        DeleteProjectConfirm(
            project = target,
            onDismiss = { deletingProject = null },
            onConfirm = {
                if (ProjectData.projects.any { it.id == target.id }) {
                    if (target.id !in deletedBuiltInIds) deletedBuiltInIds.add(target.id)
                } else {
                    val index = customProjects.indexOfFirst { it.id == target.id }
                    if (index >= 0) customProjects.removeAt(index)
                    PlannerStore(context, target.id).clear()
                }
                persistProjects()
                deletingProject = null
            }
        )
    }
}

@Composable
private fun ProjectHome(
    projects: List<ExpoProject>,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAddProject: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "展会项目",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAddProject, modifier = Modifier.pressScale()) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "添加项目")
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.pressScale()) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置")
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onOpen(project.id) },
                        onLongClick = { onLongPress(project.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("0.0.2")
    }
    val snackbar = remember { SnackbarHostState() }
    val suggestionStore = remember { SuggestionStore(context) }
    val clientId = remember { suggestionStore.clientId() }
    var showSuggestion by remember { mutableStateOf(false) }
    var showMySuggestions by remember { mutableStateOf(false) }

    if (showMySuggestions) {
        MySuggestionsScreen(onBack = { showMySuggestions = false })
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.pressScale()) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "应用版本", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "V$versionName",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = CodeFont),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale()
                        .clickable { showSuggestion = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "提交建议", style = MaterialTheme.typography.bodyLarge)
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale()
                        .clickable { showMySuggestions = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "我的建议", style = MaterialTheme.typography.bodyLarge)
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }

    if (showSuggestion) {
        SuggestionDialog(
            appVersion = versionName,
            normalize = { PlannerStore.normalizePunctuation(it) },
            onDismiss = { showSuggestion = false },
            onSubmit = { title, content ->
                val result = SuggestionApi.submit(
                    clientId = clientId,
                    title = title,
                    content = content,
                    appVersion = versionName,
                    deviceModel = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE
                )
                if (result.isSuccess) {
                    snackbar.showSnackbar("建议已提交")
                } else {
                    snackbar.showSnackbar("提交失败, 请检查网络后重试")
                }
                result.isSuccess
            }
        )
    }
}

@Composable
private fun MySuggestionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val suggestionStore = remember { SuggestionStore(context) }
    val clientId = remember { suggestionStore.clientId() }
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(clientId, refreshKey) {
        loading = true
        loadFailed = false
        SuggestionApi.list(clientId)
            .onSuccess { suggestions = it }
            .onFailure { loadFailed = true }
        loading = false
    }

    BackHandler(enabled = true) { onBack() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.pressScale()) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                }
                Text(
                    text = "我的建议",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            when {
                loading -> Text(
                    text = "加载中...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                loadFailed -> Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale()
                        .clickable { refreshKey += 1 },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = "加载失败, 点击重试",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                suggestions.isEmpty() -> Text(
                    text = "暂无建议",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(suggestions, key = { it.id }) { suggestion ->
                        SuggestionCard(suggestion)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: Suggestion) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = suggestion.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatSuggestionTime(suggestion.submittedAt),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (suggestion.reply.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "开发者回复",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = suggestion.reply,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

private fun formatSuggestionTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("")
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionDialog(
    appVersion: String,
    normalize: (String) -> String,
    onDismiss: () -> Unit,
    onSubmit: suspend (title: String, content: String) -> Boolean
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val cleanTitle = normalize(title).trim()
    val cleanContent = normalize(content).trim()
    val canSubmit = cleanTitle.isNotBlank() && cleanContent.isNotBlank() && !submitting
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "提交建议",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "V$appVersion",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = normalize(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("标题") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = normalize(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                shape = RoundedCornerShape(18.dp),
                label = { Text("内容") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
                TextButton(
                    enabled = canSubmit,
                    onClick = {
                        scope.launch {
                            submitting = true
                            val success = onSubmit(cleanTitle, cleanContent)
                            submitting = false
                            if (success) onDismiss()
                        }
                    }
                ) {
                    Text(if (submitting) "提交中..." else "提交")
                }
            }
        }
    }
}



@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ExpoProject,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .pressScale()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Capsule(text = project.year, color = MaterialTheme.colorScheme.primary)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = project.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = "进入项目",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProjectActionsDialog(
    project: ExpoProject,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .pressScale()
                        .clickable(onClick = onEdit)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "编辑")
                    Text("编辑")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .pressScale()
                        .clickable(onClick = onDelete)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "删除")
                    Text("删除", color = FavoriteRed, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteProjectConfirm(
    project: ExpoProject,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "删除项目",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "确定删除 \"${project.title}\" 吗? 删除后不可恢复.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onConfirm) {
                    Text("删除", color = FavoriteRed, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


@Composable
private fun PlannerApp(project: ExpoProject, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(project.id) { PlannerStore(context, project.id) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val states = remember(project.id) { mutableStateMapOf<String, BoothState>() }
    val customBooths = remember(project.id) { mutableStateListOf<CustomBooth>() }
    val officialBoothIds = remember(project.id) { project.booths.map { it.id }.toSet() }
    var currentHall by remember(project.id) { mutableStateOf(project.halls.firstOrNull()) }
    var currentTab by remember(project.id) { mutableStateOf(PlannerTab.Overview) }
    var query by remember(project.id) { mutableStateOf("") }
    var searching by remember(project.id) { mutableStateOf(false) }
    var showHallPicker by remember(project.id) { mutableStateOf(false) }
    var showFileMenu by remember(project.id) { mutableStateOf(false) }
    var showAddBooth by remember(project.id) { mutableStateOf(false) }

    fun currentSnapshot(): PlannerSnapshot = PlannerSnapshot(states.toMap(), customBooths.toList())

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

    LaunchedEffect(project.id) {
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

    val allHalls by remember(project.id) {
        derivedStateOf {
            val customHalls = customBooths
                .map { it.hallId }
                .distinct()
                .map { hallId -> Hall(hallId, "", "自定义展馆", 0xFF8D867A) }
            project.halls + customHalls
        }
    }
    val items by remember(project.id, currentHall, currentTab, query) {
        derivedStateOf {
            buildList(allHalls, project.booths, currentHall?.id, currentTab, query, states, customBooths)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopBar(
                hall = currentHall,
                searching = searching,
                query = query,
                onQueryChange = { query = store.normalizePunctuation(it) },
                onBackClick = onBack,
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
        HallPicker(halls = allHalls, current = currentHall, onDismiss = { showHallPicker = false }) { hall ->
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
                exportLauncher.launch("chinajoy-planner.json")
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
                if (currentHall == null) {
                    currentHall = Hall(hallId, "", "自定义展馆", 0xFF8D867A)
                }
                saveSnapshot()
                showAddBooth = false
                scope.launch { snackbar.showSnackbar("已添加 ${custom.hallId}-${custom.boothCode}") }
            }
        )
    }
}

private fun buildList(
    halls: List<Hall>,
    booths: List<Booth>,
    hallId: String?,
    tab: PlannerTab,
    query: String,
    states: Map<String, BoothState>,
    customBooths: List<CustomBooth>
): List<BoothUi> {
    val normalized = query.trim().lowercase()
    val hallById = halls.associateBy { it.id }
    val customStates = customBooths.associate { it.id to it.asState() }
    val allBooths = booths + customBooths.map { it.asBooth() }
    return allBooths
        .asSequence()
        .filter { query.isNotBlank() || hallId == null || it.hallId == hallId }
        .map { booth -> BoothUi(booth, states[booth.id] ?: customStates[booth.id] ?: BoothState()) }
        .filter { tab == PlannerTab.Overview || it.state.planned }
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
        .sortedWith(if (tab == PlannerTab.Plan) planComparator else boothNumberComparator)
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

private fun hallLabel(hall: Hall): String =
    listOf(hall.id, hall.title).filter { it.isNotBlank() }.joinToString(" ")

@Composable
private fun TopBar(
    hall: Hall?,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onHallClick: () -> Unit,
    onBackClick: (() -> Unit)? = null,
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
                IconButton(onClick = onCloseSearch, modifier = Modifier.pressScale()) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭搜索")
                }
            } else {
                if (onBackClick != null) {
                    IconButton(onClick = onBackClick, modifier = Modifier.pressScale()) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                }
                Capsule(
                    text = hall?.let(::hallLabel) ?: "未设置展馆",
                    color = hall?.let { Color(it.accent) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    onClick = onHallClick
                )
                IconButton(onClick = onAddClick, modifier = Modifier.pressScale()) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "添加展台")
                }
                IconButton(onClick = onSearchClick, modifier = Modifier.pressScale()) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = "搜索")
                }
                IconButton(onClick = onFileClick, modifier = Modifier.pressScale()) {
                    Icon(painterResource(R.drawable.ic_file), contentDescription = "导入导出")
                }
            }
        }
    }
}

@Composable
private fun BottomTabs(currentTab: PlannerTab, onSelect: (PlannerTab) -> Unit) {
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
            PlannerTab.values().forEach { tab ->
                val selected = tab == currentTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .pressScale()
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
    tab: PlannerTab,
    items: List<BoothUi>,
    onTogglePlan: (Booth) -> Unit,
    onSave: (Booth, String, String) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (tab == PlannerTab.Plan) "收藏展台后会出现在这里" else "没有匹配的展台",
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
                modifier = Modifier.then(
                    if (tab == PlannerTab.Plan) {
                        Modifier.animateItemPlacement().dragReorder(index, items.size, onMove)
                    } else {
                        Modifier
                    }
                ),
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

private fun Modifier.pressScale(): Modifier = composed {
    val context = LocalContext.current
    var pressed by remember { mutableStateOf(false) }
    val reduceMotion = remember { reduceMotionEnabled(context) }
    val animationSpec = if (reduceMotion) tween<Float>(0) else spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = animationSpec,
        label = "pressScale"
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                try {
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                } finally {
                    pressed = false
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

private fun reduceMotionEnabled(context: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

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
    val context = LocalContext.current
    val reduceMotion = remember { reduceMotionEnabled(context) }
    val heartColor by animateColorAsState(
        targetValue = if (item.state.planned) FavoriteRed else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = if (reduceMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "heartColor"
    )
    val heartScale = remember(item.booth.id) { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(item.state.planned) {
        if (reduceMotion) {
            heartScale.snapTo(1f)
            return@LaunchedEffect
        }
        if (item.state.planned) {
            heartScale.snapTo(1f)
            heartScale.animateTo(
                targetValue = 1.12f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessHigh
                )
            )
            heartScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessHigh
                )
            )
        } else {
            heartScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(216.dp)
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                IconButton(
                    onClick = {
                    if (editing) onSave(name, note)
                    editing = !editing
                    },
                    modifier = Modifier.pressScale()
                ) {
                    Icon(painterResource(if (editing) R.drawable.ic_save else R.drawable.ic_edit), contentDescription = if (editing) "保存" else "编辑")
                }
                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTogglePlan()
                    },
                    modifier = Modifier.pressScale(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (item.state.planned) FavoriteRed.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        painterResource(if (item.state.planned) R.drawable.ic_heart_filled else R.drawable.ic_heart),
                        contentDescription = if (item.state.planned) "取消收藏" else "收藏",
                        tint = heartColor,
                        modifier = Modifier.graphicsLayer {
                            scaleX = heartScale.value
                            scaleY = heartScale.value
                        }
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
            .then(if (onClick != null) Modifier.pressScale().clickable(onClick = onClick) else Modifier)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HallPicker(
    halls: List<Hall>,
    current: Hall?,
    onDismiss: () -> Unit,
    onPick: (Hall) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "选择展馆",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (halls.isEmpty()) {
                Text(
                    text = "暂无展馆",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                halls.forEach { hall ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (hall == current) Color(hall.accent).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                            .pressScale()
                            .clickable { onPick(hall) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(hallLabel(hall), fontWeight = FontWeight.SemiBold)
                        Text(hall.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProjectDialog(
    initial: ExpoProject? = null,
    normalize: (String) -> String,
    onDismiss: () -> Unit,
    onSave: (title: String, year: String, subtitle: String, note: String) -> Unit
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var year by remember(initial?.id) { mutableStateOf(initial?.year ?: "") }
    var subtitle by remember(initial?.id) { mutableStateOf(initial?.subtitle ?: "") }
    var note by remember(initial?.id) { mutableStateOf(initial?.note ?: "") }
    val cleanTitle = normalize(title).trim()
    val cleanYear = normalize(year).trim()
    val cleanSubtitle = normalize(subtitle).trim()
    val cleanNote = normalize(note)
    val focusManager = LocalFocusManager.current

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (initial == null) "添加项目" else "编辑项目",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = normalize(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("名称") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            OutlinedTextField(
                value = year,
                onValueChange = { year = normalize(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("时间") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            OutlinedTextField(
                value = subtitle,
                onValueChange = { subtitle = normalize(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("地点") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = normalize(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                shape = RoundedCornerShape(18.dp),
                label = { Text("备注") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    enabled = cleanTitle.isNotBlank(),
                    onClick = { onSave(cleanTitle, cleanYear, cleanSubtitle, cleanNote) }
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBoothDialog(
    currentHall: Hall?,
    normalize: (String) -> String,
    onDismiss: () -> Unit,
    onSave: (hallId: String, boothCode: String, name: String, note: String, planned: Boolean) -> Unit
) {
    var hallId by remember(currentHall?.id ?: "") { mutableStateOf(currentHall?.id ?: "") }
    var boothCode by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var planned by remember { mutableStateOf(false) }
    val cleanHallId = normalize(hallId).trim().uppercase()
    val cleanBoothCode = normalize(boothCode).trim().uppercase()
    val cleanName = normalize(name).trim()
    val cleanNote = normalize(note)
    val canSave = cleanHallId.isNotBlank() && cleanBoothCode.isNotBlank() && cleanName.isNotBlank()
    val focusManager = LocalFocusManager.current

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "添加展台",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = hallId,
                onValueChange = { hallId = normalize(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("展馆") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            OutlinedTextField(
                value = boothCode,
                onValueChange = { boothCode = normalize(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("展台号") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = normalize(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("展台名称") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = normalize(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                shape = RoundedCornerShape(18.dp),
                label = { Text("备注") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .pressScale()
                    .clickable { planned = !planned }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Checkbox(checked = planned, onCheckedChange = { planned = it })
                Text("加入计划", fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    enabled = canSave,
                    onClick = { onSave(cleanHallId, cleanBoothCode, cleanName, cleanNote, planned) }
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileMenu(onDismiss: () -> Unit, onImport: () -> Unit, onExport: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "数据文件",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .pressScale()
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
                    .pressScale()
                    .clickable(onClick = onExport)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(painterResource(R.drawable.ic_export), contentDescription = "导出")
                Text("导出 JSON")
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}
