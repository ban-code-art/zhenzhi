package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.AiConversationEntity
import com.example.myapplication.data.local.entity.AiMessageEntity
import com.example.myapplication.data.local.entity.AiNoteProposalEntity
import com.example.myapplication.data.local.entity.AiRunEntity
import com.example.myapplication.data.local.entity.AiRunEventEntity
import com.example.myapplication.data.local.entity.AiToolExecutionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: AiConversationEntity)

    @Query("SELECT * FROM ai_conversations WHERE id = :id AND user_id = :userId LIMIT 1")
    suspend fun findConversation(userId: Long, id: String): AiConversationEntity?

    @Query("SELECT * FROM ai_conversations WHERE user_id = :userId AND workflow = :workflow ORDER BY updated_at DESC, id DESC")
    fun observeConversations(userId: Long, workflow: String): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversations WHERE user_id = :userId AND workflow = :workflow ORDER BY updated_at DESC, id DESC")
    suspend fun findConversations(userId: Long, workflow: String): List<AiConversationEntity>

    @Query("UPDATE ai_conversations SET title = :title, updated_at = :now WHERE id = :id AND user_id = :userId")
    suspend fun renameConversation(userId: Long, id: String, title: String, now: Long): Int

    @Query("UPDATE ai_conversations SET archived_at = :now, updated_at = :now WHERE id = :id AND user_id = :userId")
    suspend fun archiveConversation(userId: Long, id: String, now: Long): Int

    @Query("UPDATE ai_conversations SET archived_at = NULL, updated_at = :now WHERE id = :id AND user_id = :userId AND archived_at IS NOT NULL")
    suspend fun unarchiveConversation(userId: Long, id: String, now: Long): Int

    @Query("UPDATE ai_conversations SET last_order_key = :orderKey, updated_at = :now WHERE id = :id AND user_id = :userId")
    suspend fun touchConversation(userId: Long, id: String, orderKey: Long, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: AiMessageEntity)

    @Query("SELECT * FROM ai_messages WHERE conversation_id = :conversationId ORDER BY order_key ASC")
    suspend fun findMessages(conversationId: String): List<AiMessageEntity>

    @Query("SELECT * FROM ai_messages WHERE conversation_id = :conversationId ORDER BY order_key DESC LIMIT :limit")
    suspend fun findRecentMessages(conversationId: String, limit: Int): List<AiMessageEntity>

    @Query("SELECT * FROM ai_messages WHERE conversation_id = :conversationId ORDER BY order_key DESC LIMIT :limit OFFSET :offset")
    suspend fun pageMessages(conversationId: String, limit: Int, offset: Int): List<AiMessageEntity>

    @Query(
        "UPDATE ai_messages SET content = :content, message_json = :messageJson, status = :status, " +
            "sources_json = :sourcesJson, usage_json = :usageJson WHERE id = :messageId",
    )
    suspend fun updateMessage(
        messageId: String,
        content: String,
        messageJson: String,
        status: String,
        sourcesJson: String?,
        usageJson: String?,
    ): Int

    @Query("UPDATE ai_messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String): Int

    /**
     * 只更新消息的来源快照（W4-02：回答结束写 sources_json）。
     * 不重写 content/message_json，避免终态回写与流式 checkpoint 互相覆盖。
     */
    @Query("UPDATE ai_messages SET sources_json = :sourcesJson WHERE id = :messageId")
    suspend fun updateMessageSources(messageId: String, sourcesJson: String?): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: AiRunEntity)

    @Query("SELECT * FROM ai_runs WHERE id = :runId AND user_id = :userId LIMIT 1")
    suspend fun findRun(userId: Long, runId: String): AiRunEntity?

    @Query("SELECT * FROM ai_runs WHERE user_id = :userId AND request_key = :requestKey LIMIT 1")
    suspend fun findRunByRequestKey(userId: Long, requestKey: String): AiRunEntity?

    @Query("SELECT * FROM ai_runs WHERE conversation_id = :conversationId AND active_slot IS NOT NULL LIMIT 1")
    suspend fun findActiveRun(conversationId: String): AiRunEntity?

    @Query("SELECT * FROM ai_runs WHERE user_id = :userId AND status IN ('RUNNING', 'INTERRUPTED') ORDER BY created_at ASC, id ASC")
    suspend fun findUnfinishedRuns(userId: Long): List<AiRunEntity>

    @Query(
        "UPDATE ai_runs SET status = :status, active_slot = :activeSlot, rounds = :rounds, token_usage = :tokenUsage, " +
            "last_event_seq = :lastEventSeq, updated_at = :now WHERE id = :runId AND user_id = :userId",
    )
    suspend fun updateRunState(
        userId: Long,
        runId: String,
        status: String,
        activeSlot: Int?,
        rounds: Int,
        tokenUsage: Long,
        lastEventSeq: Long,
        now: Long,
    ): Int

    @Query("UPDATE ai_runs SET status = 'INTERRUPTED', active_slot = NULL, updated_at = :now WHERE user_id = :userId AND status = 'RUNNING'")
    suspend fun markRunningInterrupted(userId: Long, now: Long): Int

    /**
     * 活跃 run 的 profile_snapshot_json：secret GC 必须保留其 secretVersionRef，
     * 否则在途运行会在撤权/换配置后被误删密钥。
     */
    @Query("SELECT profile_snapshot_json FROM ai_runs WHERE status IN ('RUNNING', 'INTERRUPTED')")
    suspend fun activeSnapshotJsons(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRunEvent(event: AiRunEventEntity)

    @Query("SELECT * FROM ai_run_events WHERE run_id = :runId ORDER BY seq ASC")
    suspend fun findRunEvents(runId: String): List<AiRunEventEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM ai_run_events WHERE run_id = :runId")
    suspend fun maxEventSeq(runId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertToolExecution(execution: AiToolExecutionEntity)

    @Query("SELECT * FROM ai_tool_executions WHERE run_id = :runId AND tool_call_id = :toolCallId LIMIT 1")
    suspend fun findToolExecution(runId: String, toolCallId: String): AiToolExecutionEntity?

    @Query("SELECT * FROM ai_tool_executions WHERE run_id = :runId ORDER BY updated_at ASC")
    suspend fun findToolExecutions(runId: String): List<AiToolExecutionEntity>

    @Query(
        "UPDATE ai_tool_executions SET status = :status, result_json = :resultJson, side_effect_key = :sideEffectKey, " +
            "updated_at = :now WHERE run_id = :runId AND tool_call_id = :toolCallId",
    )
    suspend fun updateToolExecution(
        runId: String,
        toolCallId: String,
        status: String,
        resultJson: String?,
        sideEffectKey: String?,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProposal(proposal: AiNoteProposalEntity)

    @Query("SELECT * FROM ai_note_proposals WHERE id = :proposalId AND user_id = :userId LIMIT 1")
    suspend fun findProposal(userId: Long, proposalId: String): AiNoteProposalEntity?

    @Query("SELECT * FROM ai_note_proposals WHERE run_id = :runId ORDER BY id ASC")
    suspend fun findProposalsForRun(runId: String): List<AiNoteProposalEntity>

    @Query("SELECT * FROM ai_note_proposals WHERE user_id = :userId AND status = 'PENDING' ORDER BY id ASC")
    suspend fun findPendingProposals(userId: Long): List<AiNoteProposalEntity>

    @Query("UPDATE ai_note_proposals SET status = :status, resolved_at = :resolvedAt WHERE id = :proposalId AND user_id = :userId AND status = 'PENDING'")
    suspend fun resolveProposal(userId: Long, proposalId: String, status: String, resolvedAt: Long): Int
}
