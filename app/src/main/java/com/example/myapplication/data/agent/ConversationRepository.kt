package com.example.myapplication.data.agent

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.AiRunDao
import com.example.myapplication.data.local.entity.AiConversationEntity
import com.example.myapplication.data.local.entity.AiMessageEntity
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.ai.ChatMessageCodec
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * 会话与消息持久（W4-04 / 详细设计 §5、§20；05 契约 ai_conversations/ai_messages）。
 *
 * - typed 历史权威：`message_json` 用 [ChatMessageCodec] 完整保存 parts/toolCalls/toolCallId，
 *   `content` 只作列表可读预览；assistant 工具调用组与 tool-result 组成对落库，裁剪不拆组；
 * - 分页：最近 50 条 + 向上取更早（order_key 单调递增，唯一索引防重复）；
 * - 会话操作带 user 条件；删除会话级联消息，但只允许本人。
 */
class ConversationRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao: AiRunDao get() = database.aiRunDao()

    fun observeConversations(userId: Long, workflow: String = WORKFLOW_CHAT): Flow<List<AiConversationEntity>> =
        dao.observeConversations(userId, workflow)

    suspend fun findConversation(userId: Long, conversationId: String): AiConversationEntity? =
        dao.findConversation(userId, conversationId)

    suspend fun listConversations(
        userId: Long,
        workflow: String = WORKFLOW_CHAT,
        includeArchived: Boolean = false,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<AiConversationEntity> =
        dao.findConversations(userId, workflow)
            .asSequence()
            .filter { includeArchived || it.archivedAt == null }
            .drop(offset)
            .take(limit)
            .toList()

    /** 创建会话；documentId 只引用当前 user 可读来源（由调用方先经 DocumentAccess 校验）。 */
    suspend fun createConversation(
        userId: Long,
        title: String,
        workflow: String = WORKFLOW_CHAT,
        documentId: String? = null,
        scopeJson: String = "{}",
        conversationId: String = "conv-${UUID.randomUUID()}",
    ): AiConversationEntity {
        val now = clock()
        val conversation = AiConversationEntity(
            id = conversationId,
            userId = userId,
            documentId = documentId,
            title = title.trim().ifBlank { DEFAULT_TITLE },
            workflow = workflow,
            scopeJson = scopeJson,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertConversation(conversation)
        return conversation
    }

    suspend fun rename(userId: Long, conversationId: String, title: String): Boolean {
        if (title.isBlank()) return false
        return dao.renameConversation(userId, conversationId, title.trim(), clock()) > 0
    }

    suspend fun archive(userId: Long, conversationId: String): Boolean =
        dao.archiveConversation(userId, conversationId, clock()) > 0

    suspend fun unarchive(userId: Long, conversationId: String): Boolean =
        dao.unarchiveConversation(userId, conversationId, clock()) > 0

    /**
     * 删除会话及其消息。v5 AiRunDao 未提供 delete（W4-01 只补 quiz），
     * 这里在事务内用参数化 SQL；只按 user 限定，不做物理跨用户删除。
     */
    suspend fun delete(userId: Long, conversationId: String): Boolean = database.withTransaction {
        val exists = dao.findConversation(userId, conversationId) ?: return@withTransaction false
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM ai_conversations WHERE id = ? AND user_id = ?",
            arrayOf(exists.id, userId),
        )
        true
    }

    /** 追加消息（order_key = last_order_key + 1，同事务更新会话时间）。 */
    suspend fun appendMessage(
        userId: Long,
        conversationId: String,
        message: ChatMessage,
        status: String = STATUS_COMPLETE,
        sourcesJson: String? = null,
        usageJson: String? = null,
    ): AiMessageEntity = database.withTransaction {
        val conversation = dao.findConversation(userId, conversationId)
            ?: throw IllegalArgumentException("conversation not found: $conversationId")
        val orderKey = conversation.lastOrderKey + 1
        val now = clock()
        val entity = AiMessageEntity(
            id = "msg-${UUID.randomUUID()}",
            conversationId = conversationId,
            role = message.role,
            content = message.contentPreview,
            messageJson = ChatMessageCodec.encode(message),
            status = status,
            orderKey = orderKey,
            sourcesJson = sourcesJson,
            usageJson = usageJson,
            createdAt = now,
        )
        dao.insertMessage(entity)
        dao.touchConversation(userId, conversationId, orderKey, now)
        entity
    }

    suspend fun updateMessage(
        messageId: String,
        message: ChatMessage,
        status: String,
        sourcesJson: String? = null,
        usageJson: String? = null,
    ): Boolean = dao.updateMessage(
        messageId = messageId,
        content = message.contentPreview,
        messageJson = ChatMessageCodec.encode(message),
        status = status,
        sourcesJson = sourcesJson,
        usageJson = usageJson,
    ) > 0

    /**
     * 只写 assistant 消息的来源快照（W4-02：`ai_messages.sources_json`）。
     * 与 [updateMessage] 分开，避免终态回写覆盖已 checkpoint 的正文与 typed 历史。
     */
    suspend fun updateMessageSources(messageId: String, sourcesJson: String): Boolean =
        dao.updateMessageSources(messageId, sourcesJson) > 0

    suspend fun messages(conversationId: String): List<AiMessageEntity> = dao.findMessages(conversationId)

    /** 最近 [limit] 条，按时间正序返回（供上下文组装）。 */
    suspend fun recentMessages(conversationId: String, limit: Int = PAGE_SIZE): List<AiMessageEntity> =
        dao.findRecentMessages(conversationId, limit).asReversed()

    /** 向上分页：offset 从最新消息开始向后翻。 */
    suspend fun pageMessages(conversationId: String, limit: Int = PAGE_SIZE, offset: Int = 0): List<AiMessageEntity> =
        dao.pageMessages(conversationId, limit, offset).asReversed()

    /** 解码 typed 历史；损坏/未知版本返回 null（不能把坏 JSON 当可执行历史）。 */
    fun decode(message: AiMessageEntity): ChatMessage? =
        runCatching { ChatMessageCodec.decode(message.messageJson) }.getOrNull()

    companion object {
        const val WORKFLOW_CHAT = "CHAT"
        const val WORKFLOW_QUIZ = "QUIZ"
        const val PAGE_SIZE = 50
        const val DEFAULT_TITLE = "新会话"

        /** 消息状态：流式草稿 / 完整 / 中断。 */
        const val STATUS_STREAMING = "STREAMING"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
    }
}
