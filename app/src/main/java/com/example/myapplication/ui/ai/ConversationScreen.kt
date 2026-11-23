package com.example.myapplication.ui.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.AnchoredActionMenu
import com.example.myapplication.ui.components.AnchoredMenuChoice
import com.example.myapplication.ui.components.PopupAnchor
import com.example.myapplication.ui.components.PopupDrag
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

private data class ConversationMenu(
    val conversationId: String,
    val source: PopupAnchor,
    val openingBounds: Rect,
    val drag: PopupDrag?,
)

@Composable
fun ConversationScreen(
    state: AgentUiState,
    onBack: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (AgentConversationUi) -> Unit,
    onRenameConversation: (AgentConversationUi, String) -> Unit,
    onArchiveConversation: (AgentConversationUi) -> Unit,
    onDeleteConversation: (AgentConversationUi) -> Unit,
    onLoadMore: () -> Unit,
) {
    var menu by remember { mutableStateOf<ConversationMenu?>(null) }
    var sceneOrigin by remember { mutableStateOf(Offset.Zero) }
    var restoreAnchor by remember { mutableStateOf<PopupAnchor?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var renameError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var waitingForRename by rememberSaveable { mutableStateOf(false) }
    var renameAfter by rememberSaveable { mutableLongStateOf(0L) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val renameTarget = state.conversations.firstOrNull { it.id == renameId }
    val deleteTarget = state.conversations.firstOrNull { it.id == deleteId }
    val dirty = renameTarget != null && renameText != renameTarget.title
    val showEmptyState = state.conversations.isEmpty() && !state.conversationsHasMore &&
        !state.sessionChanged && state.error == null

    fun closeRename() {
        renameId = null
        renameError = null
        confirmDiscard = false
        waitingForRename = false
    }

    fun dismissRename() {
        if (waitingForRename) return
        if (confirmDiscard) confirmDiscard = false
        else if (dirty) confirmDiscard = true
        else closeRename()
    }

    fun submitRename(target: AgentConversationUi) {
        if (waitingForRename || state.renameUpdate?.running == true ||
            renameText.isBlank() || renameText.trim() == target.title) return
        waitingForRename = true
        renameError = null
        renameAfter = state.renameUpdate?.sequence ?: 0L
        onRenameConversation(target, renameText)
    }

    LaunchedEffect(state.renameUpdate, waitingForRename) {
        val update = state.renameUpdate
        if (waitingForRename && update == null) {
            // A recreated process can restore the draft without its in-memory request receipt.
            waitingForRename = false
            confirmDiscard = false
            return@LaunchedEffect
        }
        if (update == null) return@LaunchedEffect
        if (waitingForRename && update.conversationId == renameId && update.sequence > renameAfter && !update.running) {
            if (update.error == null) closeRename() else {
                waitingForRename = false
                renameError = update.error
                confirmDiscard = false
            }
        }
    }
    LaunchedEffect(state.sessionChanged) {
        if (state.sessionChanged) {
            menu = null
            deleteId = null
            restoreAnchor = null
            closeRename()
        }
    }
    LaunchedEffect(menu, renameId, deleteId, restoreAnchor) {
        if (menu == null && renameId == null && deleteId == null) {
            restoreAnchor?.let { if (it.bounds != null) it.focus.requestFocus() }
            restoreAnchor = null
        }
    }

    val requestBack = unifiedBackRequest(
        BackState(
            page = BackPage.CHILD,
            imeVisible = isBackImeVisible(),
            topLayer = when {
                deleteTarget != null -> BackLayer.Overlay("conversation-delete")
                confirmDiscard -> BackLayer.Overlay("conversation-rename-decision")
                renameTarget != null -> BackLayer.Editor("conversation-rename", dirty)
                menu != null -> BackLayer.Overlay("conversation-menu")
                else -> null
            },
        ),
    ) { decision ->
        when (decision) {
            is BackDecision.ProtectDraft -> dismissRename()
            is BackDecision.CloseLayer -> when {
                deleteTarget != null -> deleteId = null
                renameTarget != null -> dismissRename()
                else -> menu = null
            }
            BackDecision.PopPage -> onBack()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        SubPageScaffold(
            title = "会话",
            onBack = requestBack,
            glassBack = true,
            actionId = "ai.conversations.back",
            trailing = { backdrop ->
                if (!showEmptyState) {
                    UiAction(
                        label = "新建会话",
                        icon = UiIcons.Add,
                        onClick = onNewConversation,
                        enabled = !state.sessionChanged,
                        backdrop = backdrop,
                        actionId = "ai.conversations.new",
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { sceneOrigin = it.positionInRoot() }) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp)
                        .fillMaxSize().padding(top = GlassPageBarHeight),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.error?.let { error ->
                        item { ConversationError(error) }
                    }
                    if (showEmptyState) {
                        item { EmptyConversationHistory(onNewConversation) }
                    }
                    items(state.conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            active = conversation.id == state.activeConversationId,
                            enabled = !state.sessionChanged,
                            onOpen = { onOpenConversation(conversation) },
                            onMenu = { source, drag ->
                                val openingBounds = source.bounds?.translate(-sceneOrigin)
                                if (menu != null || openingBounds == null || state.sessionChanged) false else {
                                    menu = ConversationMenu(conversation.id, source, openingBounds, drag)
                                    true
                                }
                            },
                        )
                    }
                    if (state.conversationsHasMore) {
                        item {
                            UiAction(
                                label = "加载更多",
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                                actionId = "ai.conversations.more",
                            )
                        }
                    }
                }
                menu?.let { opened ->
                    val target = state.conversations.firstOrNull { it.id == opened.conversationId }
                    val anchorBounds = opened.source.bounds?.translate(-sceneOrigin).takeIf { target != null }
                    AnchoredActionMenu(
                        anchor = anchorBounds,
                        openFrom = opened.openingBounds,
                        choices = listOf(
                            AnchoredMenuChoice("rename", "重命名", UiIcons.Edit),
                            AnchoredMenuChoice("archive", if (target?.archived == true) "取消归档" else "归档", UiIcons.Records),
                            AnchoredMenuChoice("delete", "删除", UiIcons.Trash, destructive = true),
                        ),
                        paneTitleText = target?.let { "${it.title}的操作" } ?: "会话操作",
                        sourceAnchor = opened.source,
                        drag = opened.drag,
                        ownerMatches = { target != null && !state.sessionChanged },
                        onChoose = { choice ->
                            if (target != null && !state.sessionChanged) {
                                when (choice) {
                                    "rename" -> {
                                        renameText = target.title
                                        renameId = target.id
                                    }
                                    "archive" -> onArchiveConversation(target)
                                    "delete" -> deleteId = target.id
                                }
                            }
                        },
                        onClosed = {
                            menu = null
                            restoreAnchor = opened.source
                        },
                        tagPrefix = "conversation-menu",
                    )
                }
            }
        }

    }

    if (renameTarget != null) {
        val canSave = !waitingForRename && renameText.isNotBlank() && renameText.trim() != renameTarget.title
        UiAlertHost(
            title = if (confirmDiscard) "未保存的会话标题" else "重命名会话",
            ownerKey = "conversation-rename-${renameTarget.id}",
            onDismissRequest = { _, _ -> dismissRename() },
        ) { interaction, _, _ ->
            Text(
                text = if (confirmDiscard) "未保存的会话标题" else "重命名会话",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                textAlign = TextAlign.Center,
            )
            if (confirmDiscard) {
                Text(
                    "保存标题、放弃修改，或继续编辑。",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = {
                        renameText = it
                        renameError = null
                    },
                    label = { Text("标题") },
                    enabled = !waitingForRename,
                    singleLine = true,
                    isError = renameError != null,
                    supportingText = renameError?.let { error -> { Text(error) } },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("conversation-rename-title"),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UiAction(
                    label = "保存",
                    onClick = { submitRename(renameTarget) },
                    enabled = canSave || waitingForRename,
                    busy = waitingForRename,
                    inheritedPane = true,
                    interactionSource = interaction,
                    foreground = UiRoles.accent,
                    modifier = Modifier.fillMaxWidth(),
                    actionId = "ai.conversations.rename.save",
                )
                if (confirmDiscard) {
                    UiAction(
                        label = "放弃",
                        onClick = ::closeRename,
                        enabled = !waitingForRename,
                        inheritedPane = true,
                        interactionSource = interaction,
                        foreground = UiRoles.extended.destructive,
                        modifier = Modifier.fillMaxWidth(),
                        actionId = "ai.conversations.rename.discard",
                    )
                }
                UiAction(
                    label = if (confirmDiscard) "继续编辑" else "取消",
                    onClick = ::dismissRename,
                    enabled = !waitingForRename,
                    inheritedPane = true,
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth(),
                    actionId = "ai.conversations.rename.cancel",
                )
            }
        }
    }
    if (deleteTarget != null) {
        UiConfirmDialog(
            title = "删除会话",
            message = "删除后不可恢复，确定删除“${deleteTarget.title}”吗？",
            confirmLabel = "删除",
            dismissLabel = "取消",
            ownerKey = "conversation-delete-${deleteTarget.id}",
            canConfirm = !state.sessionChanged,
            onConfirm = {
                deleteId = null
                onDeleteConversation(deleteTarget)
            },
            onDismiss = { deleteId = null },
            actionId = "ai.conversations.delete",
        )
    }
}

@Composable
private fun EmptyConversationHistory(onNewConversation: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = UiRoles.surface(UiSurfaceRole.Card),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(60.dp),
                shape = RoundedCornerShape(18.dp),
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(UiIcons.Ai, contentDescription = null, modifier = Modifier.size(29.dp))
                }
            }
            Text(
                text = "还没有会话",
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                modifier = Modifier.semantics { heading() },
                textAlign = TextAlign.Center,
                color = UiRoles.foreground,
            )
            UiAction(
                label = "新建会话",
                onClick = onNewConversation,
                filled = true,
                actionId = "ai.conversations.new",
            )
        }
    }
}

@Composable
private fun ConversationError(message: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(UiIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = message,
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: AgentConversationUi,
    active: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onMenu: (PopupAnchor, PopupDrag?) -> Boolean,
) {
    val menuAnchor = remember(conversation.id) { PopupAnchor() }
    val colors = MaterialTheme.colorScheme
    val surface = UiRoles.surface(UiSurfaceRole.Card)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)
            .semantics { if (active) selected = true }
            .testTag("conversation-${conversation.id}"),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (active) colors.primary.copy(alpha = .58f) else colors.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (active) lerp(surface, colors.primaryContainer, .28f) else surface,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (conversation.archived) UiRoles.surface(UiSurfaceRole.Block) else colors.primaryContainer,
                contentColor = if (conversation.archived) colors.onSurfaceVariant else colors.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (conversation.archived) UiIcons.Records else UiIcons.Ai,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = conversation.title,
                    style = UiRoles.text(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)),
                    color = UiRoles.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (conversation.updatedAtLabel.isNotBlank() || conversation.archived) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (conversation.updatedAtLabel.isNotBlank()) {
                            Text(
                                text = conversation.updatedAtLabel,
                                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (conversation.archived) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.secondaryContainer,
                                contentColor = colors.onSecondaryContainer,
                            ) {
                                Text(
                                    text = "已归档",
                                    style = UiRoles.text(MaterialTheme.typography.labelSmall),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            UiAction(
                label = "更多，${conversation.title}",
                icon = UiIcons.More,
                onClick = { onMenu(menuAnchor, null) },
                enabled = enabled,
                modifier = Modifier.testTag("conversation-more-${conversation.id}")
                    .focusRequester(menuAnchor.focus)
                    .then(menuAnchor.inputModifier(enabled) { drag -> onMenu(menuAnchor, drag) }),
                visualModifier = menuAnchor.visualModifier,
                foregroundModifier = menuAnchor.foregroundModifier(),
                actionId = "ai.conversations.row.more",
            )
        }
    }
}
