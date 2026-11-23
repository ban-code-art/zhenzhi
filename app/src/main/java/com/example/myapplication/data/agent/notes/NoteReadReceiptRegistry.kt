package com.example.myapplication.data.agent.notes

/**
 * 单次 run 内的 read_note 回执登记表（详细设计 §13/§20；04-接口 §12）。
 *
 * 约束：
 *  - 回执只在本 run 有效（receipt 绑定 runId + target + revision + 正文哈希 + 事件序号），
 *    下一个 run 必须重新 read_note，不能复用上一轮回执写提议；
 *  - 模型只能拿到应用签发的 receiptId，不能自填 hash/revision；
 *  - 表按 run 新建、按 run 丢弃（[ContainerAgentRuntimeFactory] 每次 create 新建一个），
 *    单 run 最多保存 [MAX_RECEIPTS_PER_RUN] 条，超出丢弃最旧，避免长会话内存增长。
 */
class NoteReadReceiptRegistry(
    private val maxReceiptsPerRun: Int = MAX_RECEIPTS_PER_RUN,
) {
    private val receiptsByRun = HashMap<String, LinkedHashMap<String, NoteReadReceipt>>()

    @Synchronized
    fun issue(receipt: NoteReadReceipt) {
        val perRun = receiptsByRun.getOrPut(receipt.runId) { LinkedHashMap() }
        perRun[receipt.receiptId] = receipt
        while (perRun.size > maxReceiptsPerRun) {
            val oldest = perRun.keys.firstOrNull() ?: break
            perRun.remove(oldest)
        }
    }

    @Synchronized
    fun find(runId: String, receiptId: String): NoteReadReceipt? =
        receiptsByRun[runId]?.get(receiptId)

    /** 目标最近一次回执（供诊断/测试）；不用于放宽“必须显式传 receiptId”。 */
    @Synchronized
    fun latestFor(runId: String, kind: NoteProposalTargetKind, targetId: String): NoteReadReceipt? =
        receiptsByRun[runId]?.values
            ?.lastOrNull { it.targetKind == kind && it.targetId == targetId }

    @Synchronized
    fun clear(runId: String) {
        receiptsByRun.remove(runId)
    }

    @Synchronized
    fun size(runId: String): Int = receiptsByRun[runId]?.size ?: 0

    companion object {
        const val MAX_RECEIPTS_PER_RUN = 64
    }
}
