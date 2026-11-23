package com.example.myapplication.data.agent.notes

import com.example.myapplication.domain.reader.AccessContext

/**
 * 笔记修改提议的应用侧端口（W4-03/W4-05 接线 / 详细设计 §6/§13/§20；04-接口 §5）。
 *
 * 这是工具层与 UI 层**唯一**可用的写/读入口：生产实现只有 [NoteProposalService]，
 * 用来保证：
 *  - 模型只能创建“待确认提议”，没有任何直接写正文的路径；
 *  - 创建时应用侧从当前 AccessContext 读取目标正文并签发 read receipt（模型不能自填）；
 *  - previewHash 由应用侧按规范化 edits 计算；
 *  - 确认时由 UI 以**当前** AccessContext 调 [applyProposal]（同事务 CAS + 再授权），
 *    旧 run 的 sessionEpoch 不会被当成永久授权；
 *  - 拒绝与查询（含重启后会话内待审提议）不依赖 run 是否活跃。
 *
 * 抽出接口只为让工具/UI 可以在纯 JVM 单测里注入 fake，不改变生产语义。
 */
interface NoteProposalPort {
    /**
     * 在当前上下文下读取提议目标快照（正文/版本/受保护文本）；不存在或无权 → null。
     * read_note 工具用它签发回执，避免使用可能被截断的正文哈希。
     */
    suspend fun readTarget(
        context: AccessContext,
        kind: NoteProposalTargetKind,
        targetId: String,
    ): NoteProposalTarget?

    /** 创建 PENDING 提议（不写正文）。失败返回结构化 code，由工具层映射为 ToolError。 */
    suspend fun createProposal(
        context: AccessContext,
        runId: String,
        targetKind: NoteProposalTargetKind,
        targetId: String,
        baseRevision: Long,
        readReceipt: NoteReadReceipt,
        edits: List<NotePatchEdit>,
        reason: String,
    ): ProposalCreateOutcome

    /** 用户确认：当前 ctx 再授权 + 同事务 CAS 写正文/历史 + 提议 APPROVED（重复确认幂等）。 */
    suspend fun applyProposal(
        context: AccessContext,
        proposalId: String,
    ): ProposalApplyOutcome

    /** 用户拒绝：PENDING → REJECTED（不写正文）。 */
    suspend fun rejectProposal(
        context: AccessContext,
        proposalId: String,
    ): ProposalRejectOutcome

    /** 会话内仍待审的提议（进程重启后可查；不要求生成它的 run 仍活跃）。 */
    suspend fun pendingProposalsForConversation(
        context: AccessContext,
        conversationId: String,
    ): List<NoteProposalRecord>
}
