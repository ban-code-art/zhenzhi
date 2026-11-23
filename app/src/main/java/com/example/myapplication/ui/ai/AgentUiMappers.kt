package com.example.myapplication.ui.ai

import com.example.myapplication.data.agent.ReadingContext
import com.example.myapplication.data.agent.evidence.AgentSourceRecord
import com.example.myapplication.data.agent.evidence.EvidenceRegistry
import com.example.myapplication.data.agent.evidence.SourceNavigator
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.data.local.entity.AiConversationEntity
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ScopeSnapshot

/**
 * 数据/运行层 → Agent UI 状态映射（W4-11）。
 *
 * 纯函数、无 IO：接线时 `AgentWorkspacePort` 适配器用这些函数把
 * ConversationRepository/RunCoordinator/EvidenceRegistry 的模型转成 UI 状态。
 * 关键约束：
 *  - 来源卡片必须带 [AgentSourceUi.referenceId]（SourceNavigator 的入口）；
 *  - 来源失效时仍保留 quote，但 `canNavigate=false`（由 SourceNavigator 判定后填 unavailableReason）；
 *  - 阅读上下文只带当前 scope 的 document/locator/quote，不伪造全库范围。
 */
object AgentUiMappers {
    fun roleOf(raw: String): AgentMessageRole = when (raw.uppercase()) {
        "USER" -> AgentMessageRole.USER
        "ASSISTANT" -> AgentMessageRole.ASSISTANT
        else -> AgentMessageRole.SYSTEM
    }

    /** ai_messages.status → 可读状态；流式未完成的助手片段只显示“生成中”。 */
    fun messageStatusLabel(rawStatus: String?): String? = when (rawStatus?.uppercase()) {
        "STREAMING", "RUNNING" -> "生成中…"
        "COMPLETED", "DONE" -> "已完成"
        "CANCELLED" -> "已停止"
        "INTERRUPTED" -> "已中断"
        "FAILED", "ERROR" -> "失败"
        else -> null
    }

    /** SourceNavigator 的 Outcome → 状态提示；失效不跳到别处，只展示原因与 quote。 */
    fun sourceFailureLabel(reason: String?, quote: String?): String = when {
        reason.isNullOrBlank() && !quote.isNullOrBlank() -> "来源可核验"
        reason.isNullOrBlank() -> "来源不可用"
        else -> "来源不可用：$reason"
    }
}

/** 阅读页 / 经学百科 → RunCoordinator 的 ReadingContext（scope 由 RetrievalScopeResolver 或百科仓库在适配层补）。 */
fun AgentLaunchContext.toReadingContext(scope: ScopeSnapshot? = null): ReadingContext? {
    encyclopediaContext?.let { enc ->
        return ReadingContext(
            documentId = "",
            documentTitle = enc.title,
            locatorJson = EncyclopediaSourceLocator.encode(enc.toLocator()),
            selectedText = enc.selectedQuote,
            scope = scope,
            encyclopediaContext = enc,
        )
    }
    return documentId?.takeIf { it.isNotBlank() }?.let { documentId ->
        ReadingContext(
            documentId = documentId,
            documentTitle = documentTitle,
            locatorJson = locatorJson,
            selectedText = quote,
            scope = scope,
        )
    }
}

fun AiConversationEntity.toConversationUi(updatedAtLabel: String): AgentConversationUi =
    AgentConversationUi(
        id = id,
        title = title.ifBlank { "未命名会话" },
        updatedAtLabel = updatedAtLabel,
        archived = archivedAt != null,
    )

/**
 * EvidenceRegistry 的持久来源 → 来源卡片。
 * locator 由应用侧核验结果提供；编码失败时不可跳转但仍保留 quote。
 */
fun EvidenceRegistry.RegisteredSource.toSourceUi(sectionTitle: String? = null): AgentSourceUi =
    AgentSourceUi(
        ordinal = ordinal,
        documentId = documentId,
        documentTitle = sourceTitle.ifBlank { documentId },
        sectionId = sectionId,
        sectionTitle = sectionTitle,
        quote = quote,
        locatorJson = rawLocatorJson ?: evidence?.let { runCatching { ReaderLocatorCodec.encode(it.locator) }.getOrNull() },
        referenceId = referenceId,
    )

/**
 * `ai_messages.sources_json` 的来源快照 → 来源卡片（W4-02 生产链路）。
 *
 * - referenceId/quote/locatorJson 都来自应用侧核验后的快照，不是从回答正文正则猜的；
 * - 有 locator 才 `canNavigate=true`；缺 locator（历史数据/编码失败）时保留 quote，
 *   明确“无法精确跳转”（§17 SourceUnavailable），不跳到别处冒充原出处。
 */
fun AgentSourceRecord.toSourceUi(sectionTitle: String? = null): AgentSourceUi =
    AgentSourceUi(
        ordinal = ordinal,
        documentId = documentId,
        documentTitle = documentTitle?.takeIf { it.isNotBlank() } ?: documentId,
        sectionId = sectionId,
        sectionTitle = sectionTitle,
        quote = quote,
        locatorJson = locatorJson,
        referenceId = referenceId,
        unavailableReason = if (locatorJson.isNullOrBlank()) "来源缺少可定位信息，无法精确跳转" else null,
    )

/**
 * 持久来源快照 + [SourceNavigator] 的核验结果 → 来源卡片（详细设计 §17 SourceUnavailable）。
 *
 * - [SourceNavigator.Outcome.Verified]：用当前可核验的 quote/locator 刷新卡片，可跳转；
 * - [SourceNavigator.Outcome.Missing]：源已删除/改版，**保留历史 quote**，`canNavigate=false`；
 * - [SourceNavigator.Outcome.Revoked]：无权访问，保留历史 quote，`canNavigate=false`；
 * - availability=null（未接导航端口/检查异常）：按持久 locator 判断能否精确跳转。
 *
 * 任何情况下都不跳到“别书相似内容”冒充原出处，也不伪造 locator。
 */
fun AgentSourceRecord.toSourceUi(availability: SourceNavigator.Outcome?): AgentSourceUi = when (availability) {
    is SourceNavigator.Outcome.Verified -> copy(
        quote = availability.quote,
        sectionId = availability.sectionId,
        locatorJson = runCatching { ReaderLocatorCodec.encode(availability.locator) }.getOrNull() ?: locatorJson,
        documentTitle = documentTitle ?: availability.title.takeIf { it.isNotBlank() },
    ).toSourceUi()

    is SourceNavigator.Outcome.EncyclopediaVerified -> copy(
        quote = availability.quote,
        sectionId = availability.locator.sectionId,
        locatorJson = EncyclopediaSourceLocator.encode(availability.locator),
        documentTitle = documentTitle ?: availability.title.takeIf { it.isNotBlank() },
    ).toSourceUi(availability.locator.sectionTitle)

    is SourceNavigator.Outcome.Missing -> toSourceUi().copy(
        unavailableReason = "来源已失效：${availability.reason}",
    )

    is SourceNavigator.Outcome.Revoked -> toSourceUi().copy(
        unavailableReason = "来源不可访问：${availability.reason}",
    )

    null -> toSourceUi()
}
