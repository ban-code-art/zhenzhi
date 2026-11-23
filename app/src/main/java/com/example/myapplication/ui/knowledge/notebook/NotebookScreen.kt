package com.example.myapplication.ui.knowledge.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.AnchoredActionMenu
import com.example.myapplication.ui.components.AnchoredMenuChoice
import com.example.myapplication.ui.components.PopupAnchor
import com.example.myapplication.ui.components.PopupDrag
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiActionGroup
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.knowledge.review.ReviewQuickAddAction
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import java.util.UUID

@Composable
private fun NotebookReviewExcerpt(
    notebookId: String,
    revision: Long,
    body: String,
    onDismiss: () -> Unit,
) {
    var selection by remember(notebookId, revision) { mutableStateOf(TextFieldValue(body)) }
    var prompt by rememberSaveable(notebookId, revision) { mutableStateOf("") }
    val excerpt = remember(selection.selection, body) {
        val start = selection.selection.min
        val end = selection.selection.max
        if (end - start in 1..8000 && end <= body.length) body.substring(start, end).trim() else ""
    }
    val operation = rememberSaveable(excerpt) { UUID.randomUUID().toString() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择笔记节选") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("长按并调整选区，选中要回顾的已保存正文。")
                OutlinedTextField(
                    value = selection,
                    onValueChange = { selection = it },
                    readOnly = true,
                    label = { Text("已保存的正文") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("notebook-review-selection"),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(500) },
                    label = { Text("回想提示（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (selection.selection.length > 8000) Text("请选择不超过 8000 字的节选。")
            }
        },
        confirmButton = {
            Column {
                ReviewQuickAddAction(
                    sourceKey = Triple(notebookId, revision, excerpt),
                    tag = "review-quick-add-notebook",
                    enabled = excerpt.isNotBlank(),
                ) { identity ->
                    quickNotebookExcerpt(identity, notebookId, revision, excerpt, prompt, operation)
                }
            }
        },
        dismissButton = { UiAction(label = "关闭", onClick = onDismiss) },
    )
}

private data class OpenNotebookMenu(
    val openingBounds: Rect,
    val drag: PopupDrag?,
)

@Composable
fun NotebookScreen(documentId: String, onBack: () -> Unit) {
    val adaptive = rememberAdaptiveWindowInfo()
    val context = LocalContext.current
    val factory = remember(documentId) { NotebookViewModelFactory(context, documentId) }
    val viewModel: NotebookViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val savedNotebookId = state.notebookId?.takeIf {
        !state.isDirty && state.conflict == null && state.revision > 0 && state.content.text.isNotBlank()
    }
    var pickingExcerpt by rememberSaveable(documentId, savedNotebookId, state.revision) { mutableStateOf(false) }
    if (pickingExcerpt && savedNotebookId != null) {
        NotebookReviewExcerpt(savedNotebookId, state.revision, state.content.text) { pickingExcerpt = false }
    }

    val pageBackground = UiRoles.surface(UiSurfaceRole.Page)
    val cardBackground = UiRoles.surface(UiSurfaceRole.Card)
    val foreground = UiRoles.foreground
    val secondary = UiRoles.color(UiColorRole.OnSurfaceVariant)
    val accent = UiRoles.accent
    val backdrop = rememberUiBackdrop()
    val foldOverflow = adaptive.preferStackedControls || adaptive.fontScale >= 1.5f
    val moreAnchor = remember(documentId) { PopupAnchor() }
    var sceneOrigin by remember { mutableStateOf(Offset.Zero) }
    var moreMenu by remember(documentId) { mutableStateOf<OpenNotebookMenu?>(null) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val headerPaddingTop = with(density) { if (headerHeightPx > 0) headerHeightPx.toDp() else 72.dp }
    val keyboard = LocalSoftwareKeyboardController.current

    fun menuBounds(bounds: Rect?): Rect? =
        bounds?.takeIf { it.width > 0 && it.height > 0 }?.translate(-sceneOrigin)

    fun openMoreMenu(drag: PopupDrag?): Boolean {
        val bounds = menuBounds(moreAnchor.bounds) ?: return false
        if (moreMenu != null) return false
        keyboard?.hide()
        moreMenu = OpenNotebookMenu(bounds, drag)
        return true
    }

    LaunchedEffect(foldOverflow) {
        if (!foldOverflow) moreMenu = null
    }

    val overlay = when {
        pickingExcerpt && savedNotebookId != null -> BackLayer.Overlay("notebook-excerpt")
        moreMenu != null -> BackLayer.Overlay("notebook-more")
        else -> null
    }
    val requestBack = unifiedBackRequest(BackState(BackPage.CHILD, isBackImeVisible(), overlay)) { decision ->
        when (decision) {
            BackDecision.PopPage -> viewModel.flushBeforeExit(onBack)
            is BackDecision.CloseLayer -> when (decision.layer.key) {
                "notebook-excerpt" -> pickingExcerpt = false
                "notebook-more" -> moreMenu = null
                else -> Unit
            }
            else -> Unit
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(pageBackground)
            .onGloballyPositioned { sceneOrigin = it.positionInRoot() },
    ) {
        UiGlassBackdrop(
            backdrop = backdrop,
            modifier = Modifier.fillMaxSize().then(
                if (moreMenu == null) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() },
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = headerPaddingTop)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null -> Column(
                        Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "无法打开笔记",
                            style = UiRoles.text(MaterialTheme.typography.titleLarge),
                            fontWeight = FontWeight.Bold,
                            color = foreground,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error.orEmpty(),
                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                            color = secondary,
                        )
                        Spacer(Modifier.height(16.dp))
                        UiAction(
                            label = "返回阅读",
                            onClick = requestBack,
                            filled = true,
                            actionId = "notebook.error.back",
                        )
                    }

                    else -> Column(
                        Modifier
                            .widthIn(max = adaptive.contentMaxWidth)
                            .fillMaxSize()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        MarkdownToolbar(
                            onHeading = { viewModel.insertMarkdown("## ") },
                            onBold = { viewModel.insertMarkdown("**", "**") },
                            onList = { viewModel.insertMarkdown("- ") },
                            onQuote = { viewModel.insertMarkdown("> ") },
                            onItalic = { viewModel.insertMarkdown("_", "_") },
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(cardBackground, RoundedCornerShape(14.dp)),
                        ) {
                            BasicTextField(
                                value = state.content,
                                onValueChange = viewModel::onValueChange,
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                textStyle = UiRoles.text(
                                    TextStyle(fontSize = 17.sp, lineHeight = 27.sp, color = foreground),
                                ),
                                cursorBrush = SolidColor(accent),
                                decorationBox = { innerTextField ->
                                    if (state.content.text.isEmpty()) {
                                        Text(
                                            "记录本章节的理解、易错点或临床思考…",
                                            style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                                            color = secondary,
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "每 800ms 自动保存 · 最近 50 个版本 · 当前 revision ${state.revision}",
                                modifier = Modifier.weight(1f),
                                style = UiRoles.text(MaterialTheme.typography.labelSmall),
                                color = secondary,
                            )
                            UiAction(
                                label = "选节选",
                                enabled = savedNotebookId != null,
                                onClick = {
                                    keyboard?.hide()
                                    pickingExcerpt = true
                                },
                                actionId = "notebook.reviewExcerpt",
                                modifier = Modifier.testTag("notebook-review-excerpt"),
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height }
                .background(
                    Brush.verticalGradient(
                        listOf(pageBackground, pageBackground, pageBackground.copy(alpha = .92f), Color.Transparent),
                    ),
                )
                .statusBarsPadding()
                .heightIn(min = 72.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UiAction(
                label = "返回",
                icon = UiIcons.Back,
                onClick = requestBack,
                backdrop = backdrop,
                actionId = "notebook.back",
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    state.title,
                    style = UiRoles.text(UiRoles.pageTitle),
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.statusText,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            UiActionGroup(backdrop = backdrop) {
                if (!foldOverflow) {
                    UiAction(
                        label = "材料",
                        onClick = { viewModel.showMaterials(true) },
                        enabled = state.materials.isNotEmpty(),
                        actionId = "notebook.materials",
                    )
                    UiAction(
                        label = "历史",
                        onClick = { viewModel.showVersions(true) },
                        enabled = state.notebookId != null,
                        actionId = "notebook.versions",
                    )
                }
                UiAction(
                    label = "保存",
                    onClick = viewModel::saveNow,
                    enabled = state.isDirty,
                    foreground = if (state.isDirty) accent else null,
                    actionId = "notebook.save",
                )
                if (foldOverflow) {
                    val latestOpenMore by rememberUpdatedState<(PopupDrag?) -> Boolean> { drag -> openMoreMenu(drag) }
                    UiAction(
                        label = "更多",
                        icon = UiIcons.More,
                        onClick = { openMoreMenu(null) },
                        modifier = Modifier
                            .testTag("notebook-more")
                            .focusRequester(moreAnchor.focus)
                            .then(moreAnchor.inputModifier { drag -> latestOpenMore(drag) }),
                        actionId = "notebook.more",
                        visualModifier = moreAnchor.visualModifier,
                        foregroundModifier = moreAnchor.foregroundModifier(),
                    )
                }
            }
        }

        moreMenu?.let { menu ->
            val anchor = if (foldOverflow) menuBounds(moreAnchor.bounds) else null
            AnchoredActionMenu(
                anchor = anchor,
                openFrom = menu.openingBounds,
                sourceAnchor = moreAnchor,
                drag = menu.drag,
                choices = listOf(
                    AnchoredMenuChoice(
                        value = "materials",
                        label = "材料",
                        enabled = state.materials.isNotEmpty(),
                        testTag = "notebook-menu-materials",
                    ),
                    AnchoredMenuChoice(
                        value = "versions",
                        label = "历史",
                        enabled = state.notebookId != null,
                        testTag = "notebook-menu-versions",
                    ),
                ),
                paneTitleText = "笔记本操作",
                onChoose = { value ->
                    when (value) {
                        "materials" -> viewModel.showMaterials(true)
                        "versions" -> viewModel.showVersions(true)
                    }
                },
                onClosed = {
                    moreMenu = null
                    if (anchor != null) moreAnchor.focus.requestFocus()
                },
                backdrop = backdrop,
                tagPrefix = "notebook-more-menu",
            )
        }
    }

    if (state.showVersions) {
        UiSheet(
            title = "历史版本",
            onDismiss = { viewModel.showVersions(false) },
            actions = {
                UiAction(
                    label = "关闭",
                    onClick = { viewModel.showVersions(false) },
                    actionId = "notebook.versions.close",
                )
            },
        ) {
            if (state.versions.isEmpty()) {
                Text(
                    "暂无历史版本",
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = secondary,
                )
            } else {
                state.versions.forEach { version ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "revision ${version.revision}",
                                style = UiRoles.text(MaterialTheme.typography.labelLarge),
                                color = foreground,
                            )
                            Text(
                                version.bodyMarkdown.replace("\n", " ").take(90).ifBlank { "空白笔记" },
                                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                color = secondary,
                                maxLines = 2,
                            )
                        }
                        UiAction(
                            label = "恢复",
                            onClick = {
                                viewModel.restoreVersion(version)
                                viewModel.showVersions(false)
                            },
                            actionId = "notebook.versions.restore",
                        )
                    }
                }
            }
        }
    }

    if (state.showMaterials) {
        UiSheet(
            title = "插入原文材料",
            onDismiss = { viewModel.showMaterials(false) },
            actions = {
                UiAction(
                    label = "关闭",
                    onClick = { viewModel.showMaterials(false) },
                    actionId = "notebook.materials.close",
                )
            },
        ) {
            Text(
                "选择高亮或附注，将可长期回链的来源引用插入当前光标。",
                color = secondary,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
            )
            Spacer(Modifier.height(8.dp))
            if (state.materials.isEmpty()) {
                Text(
                    "暂无可用标注",
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = secondary,
                )
            } else {
                state.materials.forEach { annotation ->
                    val quote = annotation.quote ?: annotation.noteText ?: return@forEach
                    Surface(
                        onClick = { viewModel.insertMaterial(annotation) },
                        shape = RoundedCornerShape(14.dp),
                        color = UiRoles.surface(UiSurfaceRole.Block),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            quote.replace("\n", " ").take(160),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                            color = foreground,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    state.conflict?.let { conflict ->
        UiAlertHost(
            title = "笔记内容冲突",
            ownerKey = "notebook-conflict-${conflict.currentRevision}",
            onDismissRequest = { _, _ -> viewModel.dismissConflict() },
        ) { interaction, _, _ ->
            Text(
                "笔记内容冲突",
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                color = foreground,
                textAlign = TextAlign.Center,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "另一个编辑器已保存 revision ${conflict.currentRevision}。你的草稿仍然保留，请选择处理方式。",
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = secondary,
                )
                Text(
                    "服务端版本",
                    style = UiRoles.text(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                )
                Text(
                    conflict.currentBody.take(500),
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = secondary,
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiAction(
                    label = "保留我的并重试",
                    onClick = viewModel::resolveConflictKeepLocal,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = accent,
                    actionId = "notebook.conflict.keepLocal",
                    modifier = Modifier.fillMaxWidth(),
                    visualModifier = Modifier.fillMaxWidth(),
                )
                UiAction(
                    label = "采用服务端",
                    onClick = viewModel::resolveConflictUseServer,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.extended.destructive,
                    actionId = "notebook.conflict.useServer",
                    modifier = Modifier.fillMaxWidth(),
                    visualModifier = Modifier.fillMaxWidth(),
                )
                UiAction(
                    label = "稍后处理",
                    onClick = viewModel::dismissConflict,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    actionId = "notebook.conflict.later",
                    modifier = Modifier.fillMaxWidth(),
                    visualModifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(
    onHeading: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onList: () -> Unit,
    onQuote: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        UiActionGroup {
            NotebookTool("H2", "notebook.tool.h2", onHeading)
            NotebookTool("粗体", "notebook.tool.bold", onBold)
            NotebookTool("斜体", "notebook.tool.italic", onItalic)
            NotebookTool("列表", "notebook.tool.list", onList)
            NotebookTool("引用", "notebook.tool.quote", onQuote)
        }
    }
}

@Composable
private fun NotebookTool(label: String, actionId: String, onClick: () -> Unit) {
    UiAction(label = label, onClick = onClick, actionId = actionId)
}
