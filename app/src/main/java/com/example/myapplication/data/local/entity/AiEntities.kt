package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * schema v5：AI 配置、运行与消息。
 * 字段与默认值以《数据库迁移与查询契约》§3 v4→v5 DDL 为准。
 */
@Entity(
    tableName = "ai_profiles",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["user_id", "purpose", "updated_at", "id"], name = "idx_ai_profile_owner")],
)
data class AiProfileEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "purpose") val purpose: String,
    @ColumnInfo(name = "protocol") val protocol: String,
    @ColumnInfo(name = "endpoint") val endpoint: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "secret_ref") val secretRef: String? = null,
    @ColumnInfo(name = "params_json", defaultValue = "'{}'") val paramsJson: String = "{}",
    @ColumnInfo(name = "capabilities_json", defaultValue = "'{}'") val capabilitiesJson: String = "{}",
    @ColumnInfo(name = "capability_profile_hash", defaultValue = "''") val capabilityProfileHash: String = "",
    @ColumnInfo(name = "enabled", defaultValue = "0") val enabled: Boolean = false,
    @ColumnInfo(name = "config_revision", defaultValue = "1") val configRevision: Long = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "ai_user_settings",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AiProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_chat_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AiProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_embedding_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AiProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_rerank_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AiProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_search_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["active_chat_profile_id"], name = "idx_ai_settings_chat"),
        Index(value = ["active_embedding_profile_id"], name = "idx_ai_settings_embed"),
        Index(value = ["active_rerank_profile_id"], name = "idx_ai_settings_rerank"),
        Index(value = ["active_search_profile_id"], name = "idx_ai_settings_search"),
    ],
)
data class AiUserSettingsEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "active_chat_profile_id") val activeChatProfileId: String? = null,
    @ColumnInfo(name = "active_embedding_profile_id") val activeEmbeddingProfileId: String? = null,
    @ColumnInfo(name = "active_rerank_profile_id") val activeRerankProfileId: String? = null,
    @ColumnInfo(name = "active_search_profile_id") val activeSearchProfileId: String? = null,
    @ColumnInfo(name = "network_search_enabled", defaultValue = "0") val networkSearchEnabled: Boolean = false,
    @ColumnInfo(name = "budget_json", defaultValue = "'{}'") val budgetJson: String = "{}",
    @ColumnInfo(name = "settings_revision", defaultValue = "1") val settingsRevision: Long = 1,
)

@Entity(
    tableName = "ai_consents",
    primaryKeys = ["user_id", "purpose", "endpoint_hash"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AiConsentEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "purpose") val purpose: String,
    @ColumnInfo(name = "endpoint_hash") val endpointHash: String,
    @ColumnInfo(name = "allowed", defaultValue = "0") val allowed: Boolean = false,
    @ColumnInfo(name = "scope_json") val scopeJson: String,
    @ColumnInfo(name = "policy_revision") val policyRevision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "ai_conversations",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReaderDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["user_id", "workflow", "updated_at", "id"], name = "idx_ai_conversation_recent"),
        Index(value = ["document_id"], name = "idx_ai_conversation_document"),
    ],
)
data class AiConversationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "workflow", defaultValue = "'CHAT'") val workflow: String = "CHAT",
    @ColumnInfo(name = "scope_json") val scopeJson: String,
    @ColumnInfo(name = "summary_json") val summaryJson: String? = null,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "last_order_key", defaultValue = "0") val lastOrderKey: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "ai_messages",
    foreignKeys = [
        ForeignKey(
            entity = AiConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id", "order_key"], unique = true, name = "idx_ai_message_order")],
)
data class AiMessageEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "message_json", defaultValue = "'{}'") val messageJson: String = "{}",
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "order_key") val orderKey: Long,
    @ColumnInfo(name = "sources_json") val sourcesJson: String? = null,
    @ColumnInfo(name = "usage_json") val usageJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "ai_runs",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AiConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "active_slot"], unique = true, name = "idx_ai_run_slot"),
        Index(value = ["user_id", "request_key"], unique = true, name = "idx_ai_run_request"),
        Index(value = ["user_id", "status", "id"], name = "idx_ai_run_owner"),
    ],
)
data class AiRunEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "request_key") val requestKey: String,
    @ColumnInfo(name = "config_revision") val configRevision: Long,
    @ColumnInfo(name = "profile_snapshot_json", defaultValue = "'{}'") val profileSnapshotJson: String = "{}",
    @ColumnInfo(name = "scope_snapshot_json") val scopeSnapshotJson: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "active_slot") val activeSlot: Int? = null,
    @ColumnInfo(name = "rounds", defaultValue = "0") val rounds: Int = 0,
    @ColumnInfo(name = "token_usage", defaultValue = "0") val tokenUsage: Long = 0,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long? = null,
    @ColumnInfo(name = "last_event_seq", defaultValue = "0") val lastEventSeq: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "ai_run_events",
    primaryKeys = ["run_id", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = AiRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AiRunEventEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "tool_call_id") val toolCallId: String? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "ai_tool_executions",
    primaryKeys = ["run_id", "tool_call_id"],
    foreignKeys = [
        ForeignKey(
            entity = AiRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AiToolExecutionEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "tool_call_id") val toolCallId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "arguments_hash") val argumentsHash: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "result_json") val resultJson: String? = null,
    @ColumnInfo(name = "side_effect_key") val sideEffectKey: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "ai_note_proposals",
    foreignKeys = [
        ForeignKey(
            entity = AiRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id"], name = "idx_ai_proposal_run"),
        Index(value = ["user_id", "status", "id"], name = "idx_ai_proposal_owner"),
    ],
)
data class AiNoteProposalEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "target_kind") val targetKind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "base_revision") val baseRevision: Long,
    @ColumnInfo(name = "read_receipt_id") val readReceiptId: String,
    @ColumnInfo(name = "read_receipt_json", defaultValue = "'{}'") val readReceiptJson: String = "{}",
    @ColumnInfo(name = "patch_json") val patchJson: String,
    @ColumnInfo(name = "preview_hash") val previewHash: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null,
)
