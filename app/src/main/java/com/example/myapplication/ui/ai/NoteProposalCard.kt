package com.example.myapplication.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/**
 * 会话内“笔记修改提议”卡片（W4-11 / 详细设计 §6/§17/§20）。
 *
 * 展示：目标（笔记本/标注附注 + id）、修改理由、逐段 old→new diff、baseRevision 与
 * previewHash 短指纹、状态（待确认/已应用/已拒绝/已失效）。
 *
 * 交互约束（硬性）：
 *  - 只有 PENDING 提议显示“确认修改/拒绝”；确认必须由用户点击触发；
 *  - 页面**不会**自动确认、不会自动重放：STALE 只展示失效原因并要求重新生成；
 *  - 确认由 ViewModel 用当前 AccessContext 重新授权并走同事务 CAS（见 RealAgentWorkspacePort），
 *    本卡片不持有任何写正文能力；
 *  - 来源失效/冲突时仍展示原 diff 与理由，方便用户判断，不静默丢弃。
 */
data class AgentPatchEditUi(
    val oldText: String,
    val newText: String,
) {
    /** 单行预览：过长 diff 截断展示，完整内容仍在持久提议里。 */
    fun oldPreview(maxChars: Int = 120): String = oldText.take(maxChars)
    fun newPreview(maxChars: Int = 120): String = newText.take(maxChars)
}

/** 会话内 PENDING/已失效提议的 UI 模型（不依赖 Room/服务类型）。 */
data class AgentNoteProposalUi(
    val id: String,
    val targetKindLabel: String,
    val targetId: String,
    val targetRevision: Long,
    val reason: String,
    val edits: List<AgentPatchEditUi>,
    val previewHash: String,
    /** PENDING / APPROVED / REJECTED / STALE。 */
    val status: String,
    /** STALE 原因的可读文案（revision 已变 / 本地草稿未保存 / 载荷校验失败）。 */
    val staleReason: String? = null,
    /** STALE + 本地草稿：用户草稿正文仍保留，不能丢弃。 */
    val conflictingDraftPreview: String? = null,
) {
    val isPending: Boolean get() = status == "PENDING"
    val isStale: Boolean get() = status == "STALE"

    val statusLabel: String
        get() = when (status) {
            "PENDING" -> "待确认"
            "APPROVED" -> "已应用"
            "REJECTED" -> "已拒绝"
            "STALE" -> "已失效"
            else -> status
        }

    /** 卡片标题：目标类型 + 稳定 id 短后缀（id 本身不适合整段展示）。 */
    val targetLabel: String
        get() = "$targetKindLabel ${targetId.takeLast(8)}（revision $targetRevision）"
}

/** 确认/拒绝的结果（供 ViewModel 更新卡片与提示，不用于持久化）。 */
sealed interface AgentProposalActionResult {
    data class Confirmed(val revision: Long) : AgentProposalActionResult

    /** 目标已变化/本地有草稿/载荷校验失败：提议转 STALE，正文未被覆盖。 */
    data class Stale(val reason: String, val currentRevision: Long, val draftPreview: String?) :
        AgentProposalActionResult

    data class Rejected(val code: String, val message: String) : AgentProposalActionResult

    /** 提议已被处理过（幂等/进程重启后）：展示当前状态，不重复写。 */
    data class AlreadyResolved(val status: String) : AgentProposalActionResult

    data object NotFound : AgentProposalActionResult

    /** 当前 UI 端口未接入提议服务（骨架/受限模式）。 */
    data class Unavailable(val message: String) : AgentProposalActionResult
}

@Composable
fun NoteProposalCard(
    proposal: AgentNoteProposalUi,
    busy: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "笔记修改提议",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = UiRoles.foreground,
                    )
                    Text(
                        text = proposal.targetLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(proposal.statusLabel, style = MaterialTheme.typography.labelSmall)
                    if (!proposal.isPending) {
                        // 已处理/已失效的卡片可移除展示，但不会触发任何写操作。
                        UiAction(
                            label = "移除",
                            onClick = onDismiss,
                            inheritedPane = true,
                            actionId = "ai.proposal.dismiss",
                        )
                    }
                }
            }

            Text(
                text = "理由：${proposal.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = UiRoles.foreground,
            )

            // 逐段 old→new diff：删除行标 -、新增行标 +，超过 8 段折叠为摘要。
            val shown = proposal.edits.take(MAX_DIFF_EDITS)
            shown.forEachIndexed { index, edit ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "- ${edit.oldPreview()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "+ ${edit.newPreview()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (index != shown.lastIndex) {
                        Text(
                            text = "————",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (proposal.edits.size > shown.size) {
                Text(
                    text = "…共 ${proposal.edits.size} 段修改（仅预览前 ${shown.size} 段）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            proposal.staleReason?.let { reason ->
                Text(
                    text = "已失效（STALE）：$reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            proposal.conflictingDraftPreview?.let { draft ->
                Text(
                    text = "你的本地草稿已保留，不会被覆盖：${draft.take(80)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "基于 revision ${proposal.targetRevision} · 指纹 ${proposal.previewHash.take(12)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (proposal.isPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    UiAction(
                        label = "拒绝",
                        onClick = onReject,
                        enabled = !busy,
                        inheritedPane = true,
                        actionId = "ai.proposal.reject",
                    )
                    UiAction(
                        label = "确认修改",
                        onClick = onConfirm,
                        busy = busy,
                        filled = true,
                        actionId = "ai.proposal.confirm",
                    )
                }
                Text(
                    text = "确认后才会写入正文；退出页面不会自动确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MAX_DIFF_EDITS = 8
