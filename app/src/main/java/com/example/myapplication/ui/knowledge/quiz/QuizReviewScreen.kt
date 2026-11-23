package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.quiz.*
import com.example.myapplication.domain.quiz.*
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.ui.ai.AgentSourceUi
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

private data class ReviewFields(
    val stem: String, val answers: String, val analysis: String, val knowledgePoint: String,
    val options: List<String>, val rubricAccepted: List<String>, val rubricNegated: List<String>,
    val rubricWeights: List<String>, val evidenceIds: List<String>,
) {
    fun item(original: QuizItemSnapshot): QuizItemSnapshot? {
        val weights = rubricWeights.map { it.toIntOrNull() ?: return null }
        return original.copy(stem=stem,answer=parts(answers),analysis=analysis,knowledgePoint=knowledgePoint,
            options=options,evidenceIds=evidenceIds,rubric=original.rubric.mapIndexed { index,point ->
                point.copy(accepted=parts(rubricAccepted[index]),negatedBy=parts(rubricNegated[index]),weight=weights[index]) })
    }
    companion object {
        fun from(item: QuizItemSnapshot) = ReviewFields(item.stem,item.answer.joinToString("；"),item.analysis,item.knowledgePoint,
            item.options,item.rubric.map { it.accepted.joinToString("；") },item.rubric.map { it.negatedBy.joinToString("；") },
            item.rubric.map { it.weight.toString() },item.evidenceIds)
        private fun parts(value: String) = value.split('；').map { it.trim() }.filter { it.isNotBlank() }
    }
}

private val ChecksSaver = Saver<Map<Int,Set<String>>,String>(
    save={ value -> JsonObject(value.mapKeys { it.key.toString() }.mapValues { JsonArray(it.value.sorted().map(::JsonPrimitive)) }).toString() },
    restore={ value -> runCatching { Json.parseToJsonElement(value).jsonObject.mapKeys { it.key.toInt() }
        .mapValues { it.value.jsonArray.map { key -> key.jsonPrimitive.content }.toSet() } }.getOrDefault(emptyMap()) },
)
private val EditorsSaver = Saver<Map<Int,ReviewFields>,String>(
    save={ value -> JsonObject(value.mapKeys { it.key.toString() }.mapValues { (_,f) -> buildJsonObject {
        put("stem",f.stem);put("answers",f.answers);put("analysis",f.analysis);put("knowledgePoint",f.knowledgePoint)
        put("options",JsonArray(f.options.map(::JsonPrimitive)));put("rubricAccepted",JsonArray(f.rubricAccepted.map(::JsonPrimitive)))
        put("rubricNegated",JsonArray(f.rubricNegated.map(::JsonPrimitive)));put("rubricWeights",JsonArray(f.rubricWeights.map(::JsonPrimitive)))
        put("evidenceIds",JsonArray(f.evidenceIds.map(::JsonPrimitive)))
    } }).toString() },
    restore={ value -> runCatching { Json.parseToJsonElement(value).jsonObject.mapKeys { it.key.toInt() }.mapValues { (_,raw) ->
        val f=raw.jsonObject
        fun list(key:String)=f.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        ReviewFields(f.getValue("stem").jsonPrimitive.content,f.getValue("answers").jsonPrimitive.content,
            f.getValue("analysis").jsonPrimitive.content,f.getValue("knowledgePoint").jsonPrimitive.content,
            list("options"),list("rubricAccepted"),list("rubricNegated"),list("rubricWeights"),list("evidenceIds"))
    } }.getOrDefault(emptyMap()) },
)

private enum class ReviewDeparture { BACK, REGENERATE, RELOAD }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuizReviewRoute(
    service: QuizReviewService,
    ctxProvider: suspend () -> AccessContext?,
    paperId: String? = null,
    onBack: () -> Unit,
    onOpenPaper: (String) -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit,
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
    identityKey: Any? = null,
    onRegenerate: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val papersListState = rememberLazyListState()
    val latestProvider by rememberUpdatedState(ctxProvider)
    val latestIdentity by rememberUpdatedState(identityKey)
    val returned = remember(initialReturnPosition) {
        runCatching { Json.parseToJsonElement(initialReturnPosition).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
    }
    var selected by rememberSaveable(identityKey, paperId) {
        mutableStateOf(paperId ?: returned["paperId"]?.jsonPrimitive?.contentOrNull)
    }
    var summaries by remember(identityKey) { mutableStateOf(emptyList<QuizReviewService.Summary>()) }
    var session by remember(identityKey) { mutableStateOf<QuizReviewService.Session?>(null) }
    var checks by rememberSaveable(identityKey, paperId, stateSaver = ChecksSaver) {
        mutableStateOf(emptyMap<Int, Set<String>>())
    }
    var editors by rememberSaveable(identityKey, paperId, stateSaver = EditorsSaver) {
        mutableStateOf(emptyMap<Int, ReviewFields>())
    }
    var cachedHash by rememberSaveable(identityKey, paperId) { mutableStateOf("") }
    var cachedPaper by rememberSaveable(identityKey, paperId) { mutableStateOf<String?>(null) }
    var requestId by rememberSaveable(identityKey, paperId) { mutableStateOf(UUID.randomUUID().toString()) }
    var message by remember(identityKey) { mutableStateOf<String?>(null) }
    var loading by remember(identityKey) { mutableStateOf(true) }
    var busy by remember(identityKey) { mutableStateOf(false) }
    var generation by remember(identityKey) { mutableLongStateOf(0L) }
    var reload by remember(identityKey) { mutableIntStateOf(0) }
    var deleteOrdinal by rememberSaveable(identityKey, paperId) { mutableStateOf<Int?>(null) }
    var discardRequested by rememberSaveable(identityKey, paperId) { mutableStateOf(false) }
    var pendingDeparture by rememberSaveable(identityKey, paperId) { mutableStateOf<ReviewDeparture?>(null) }

    suspend fun current(ctx: AccessContext, ticket: Long): Boolean {
        if (ticket != generation || latestIdentity != identityKey) return false
        val now = latestProvider() ?: return false
        return now.userId == ctx.userId && now.sessionEpoch == ctx.sessionEpoch
    }
    fun acceptLoaded(loaded: QuizReviewService.Session, retained: Map<Int, ReviewFields> = emptyMap()) {
        if (cachedPaper != loaded.paperId || cachedHash != loaded.reference.draftSha256) {
            editors = loaded.draft.items.associate { it.ordinal to (retained[it.ordinal] ?: ReviewFields.from(it)) }
            checks = emptyMap()
            cachedPaper = loaded.paperId
            cachedHash = loaded.reference.draftSha256
            requestId = UUID.randomUUID().toString()
        }
        session = loaded
    }
    fun runAction(block: suspend (AccessContext, Long) -> Unit) {
        if (busy || loading) return
        val ticket = generation
        busy = true
        scope.launch {
            try {
                val ctx = latestProvider()
                if (ctx == null) {
                    message = "请先登录后核对草稿"
                } else if (current(ctx, ticket)) {
                    block(ctx, ticket)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == generation && latestIdentity == identityKey) {
                    message = error.message ?: "操作失败，请重试"
                }
            } finally {
                if (ticket == generation && latestIdentity == identityKey) busy = false
            }
        }
    }
    LaunchedEffect(identityKey, selected, reload) {
        val ticket = ++generation
        val requested = selected
        message = null
        busy = false
        loading = true
        try {
            val ctx = latestProvider()
            if (ctx == null) {
                session = null
                summaries = emptyList()
                message = "请先登录后核对草稿"
                return@LaunchedEffect
            }
            val list = service.list(ctx.userId)
            val loaded = requested?.let { service.load(ctx.userId, it) }
            if (!current(ctx, ticket)) return@LaunchedEffect
            summaries = list
            if (loaded != null) {
                acceptLoaded(loaded)
            } else if (requested != null) {
                if (session?.paperId == requested) session = null
                message = "这份草稿已处理或来源已变化"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (ticket == generation && latestIdentity == identityKey) message = error.message ?: "读取草稿失败"
        } finally {
            if (ticket == generation && latestIdentity == identityKey) loading = false
        }
    }
    val currentSession = session?.takeIf { it.paperId == selected }
    LaunchedEffect(currentSession?.paperId) {
        val loaded = currentSession ?: return@LaunchedEffect
        val fromReturn = returned["paperId"]?.jsonPrimitive?.contentOrNull == loaded.paperId
        val savedOrdinal = if (fromReturn) returned["ordinal"]?.jsonPrimitive?.intOrNull else null
        val ordinal = savedOrdinal ?: loaded.currentOrdinal
        val itemIndex = loaded.draft.items.indexOfFirst { it.ordinal == ordinal }.coerceAtLeast(0)
        val savedIndex = if (fromReturn) returned["scrollIndex"]?.jsonPrimitive?.intOrNull else null
        val index = savedIndex?.coerceIn(0, loaded.draft.items.size + 3) ?: itemIndex + 2
        val offset = if (fromReturn) returned["scrollOffset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0 else 0
        listState.scrollToItem(index, offset)
    }
    val dirty = currentSession?.draft?.items?.any { item ->
        editors[item.ordinal]?.let { it != ReviewFields.from(item) } == true
    } == true

    fun depart(destination: ReviewDeparture) {
        pendingDeparture = null
        if (dirty) {
            editors = emptyMap()
            checks = emptyMap()
            cachedHash = ""
        }
        when (destination) {
            ReviewDeparture.BACK -> if (paperId == null && selected != null) selected = null else onBack()
            ReviewDeparture.REGENERATE -> currentSession?.paperId?.let { onRegenerate?.invoke(it) }
            ReviewDeparture.RELOAD -> {
                editors = emptyMap()
                checks = emptyMap()
                cachedHash = ""
                requestId = UUID.randomUUID().toString()
                deleteOrdinal = null
                discardRequested = false
                loading = true
                reload++
            }
        }
    }
    fun requestDeparture(destination: ReviewDeparture) {
        if (busy || (destination == ReviewDeparture.RELOAD && loading)) return
        if (dirty) pendingDeparture = destination else depart(destination)
    }

    val page = selected?.let { listOf("review:$it") }.orEmpty()
    UiPageStack(page) { visible ->
        val visibleId = visible.singleOrNull()?.removePrefix("review:")
        val displayed = session?.takeIf { it.paperId == visibleId }
        val requestBack = unifiedBackRequest(
            BackState(
                page = BackPage.CHILD,
                imeVisible = isBackImeVisible(),
                topLayer = when {
                    pendingDeparture != null -> BackLayer.Overlay("review-discard-edits")
                    deleteOrdinal != null || discardRequested -> BackLayer.Overlay("review-delete")
                    dirty -> BackLayer.Editor("review-editor", true)
                    else -> null
                },
            ),
        ) { decision ->
            when (decision) {
                is BackDecision.CloseLayer -> {
                    pendingDeparture = null
                    deleteOrdinal = null
                    discardRequested = false
                }
                is BackDecision.ProtectDraft, BackDecision.PopPage -> requestDeparture(ReviewDeparture.BACK)
                else -> Unit
            }
        }
        Scaffold(
            containerColor = UiRoles.surface(UiSurfaceRole.Page),
            topBar = {
                CourseScreenHeader(title = "待核对草稿", onBack = requestBack, backTag = "quiz-review-back")
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                state = if (visibleId == null) papersListState else listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                item {
                    Text("逐题核对原文与答案。通过核对的试卷仅供个人练习。", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, color = if (it.startsWith("已保存")) UiRoles.accent else MaterialTheme.colorScheme.error) }
                    if (displayed != null || message != null) {
                        UiAction("重新载入", onClick = { requestDeparture(ReviewDeparture.RELOAD) },
                            enabled = !busy && !loading, modifier = Modifier.testTag("quiz-review-reload"),
                            actionId = "quiz.review.reload")
                    }
                }
                if (visibleId == null) {
                    if (!loading && summaries.isEmpty() && message == null) item { Text("暂无待核对草稿") }
                    items(summaries, key = { it.paperId }) { entry ->
                        val created = remember(entry.createdAt) {
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAt))
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(created, style = MaterialTheme.typography.bodyMedium)
                                UiAction("打开草稿", onClick = { selected = entry.paperId },
                                    enabled = !busy && !loading, actionId = "quiz.review.open.${entry.paperId}")
                            }
                        }
                    }
                }
                if (displayed != null) {
                    val editable = !busy && !loading
                    items(displayed.draft.items, key = { it.ordinal }) { question ->
                        val original = ReviewFields.from(question)
                        val fields = editors[question.ordinal] ?: original
                        val proof = displayed.supportAssessments[displayed.draft.items.indexOf(question)]
                        val itemDirty = fields != original
                        val reviewable = QuizPersonalReview.isReviewable(question, proof)
                        fun change(updated: ReviewFields) {
                            editors = editors + (question.ordinal to updated)
                            checks = checks - question.ordinal
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("第${question.ordinal}题 · 待核对", style = MaterialTheme.typography.titleMedium)
                                if (!reviewable && proof.state != QuizSupportState.SUPPORTED) {
                                    Text("需要先修订或更换来源：${proof.reasonCodes.joinToString("、")}",
                                        color = MaterialTheme.colorScheme.error)
                                }
                                ReviewField("题干", fields.stem, editable) { change(fields.copy(stem = it)) }
                                fields.options.forEachIndexed { index, value ->
                                    ReviewField("选项 ${('A'.code + index).toChar()}", value, editable) {
                                        change(fields.copy(options = fields.options.replacing(index, it)))
                                    }
                                }
                                val answerLabel = if (question.type == QuizItemType.CHOICE) "正确选项字母" else "参考答案（多项用；分隔）"
                                ReviewField(answerLabel, fields.answers, editable) { change(fields.copy(answers = it)) }
                                ReviewField("解析", fields.analysis, editable) { change(fields.copy(analysis = it)) }
                                ReviewField("知识点", fields.knowledgePoint, editable) { change(fields.copy(knowledgePoint = it)) }
                                question.rubric.forEachIndexed { index, _ ->
                                    ReviewField("评分点 ${index + 1}（等价表达用；分隔）", fields.rubricAccepted[index], editable) {
                                        change(fields.copy(rubricAccepted = fields.rubricAccepted.replacing(index, it)))
                                    }
                                    ReviewField("评分点 ${index + 1} 分值", fields.rubricWeights[index], editable) {
                                        change(fields.copy(rubricWeights = fields.rubricWeights.replacing(index, it)))
                                    }
                                    ReviewField("评分点 ${index + 1} 否定表达（可选）", fields.rubricNegated[index], editable) {
                                        change(fields.copy(rubricNegated = fields.rubricNegated.replacing(index, it)))
                                    }
                                }
                                displayed.sources.filter { it.evidenceId in question.evidenceIds }.forEach { ref ->
                                    Text(ref.quote, style = MaterialTheme.typography.bodyMedium)
                                    UiAction(
                                        "查看来源",
                                        onClick = {
                                            if (dirty) {
                                                message = "请先保存草稿中的修改，再查看来源"
                                            } else runAction { ctx, ticket ->
                                                service.savePosition(ctx, displayed.paperId, question.ordinal)
                                                if (current(ctx, ticket)) {
                                                    onSourceCheckpoint(buildJsonObject {
                                                        put("paperId", displayed.paperId)
                                                        put("ordinal", question.ordinal)
                                                        put("scrollIndex", listState.firstVisibleItemIndex)
                                                        put("scrollOffset", listState.firstVisibleItemScrollOffset)
                                                    }.toString())
                                                    onOpenSource(AgentSourceUi(question.ordinal, ref.documentId,
                                                        ref.sourceTitleSnapshot, ref.sectionId, null, ref.quote,
                                                        ReaderLocatorCodec.encode(ref.locator)))
                                                }
                                            }
                                        },
                                        enabled = editable,
                                        actionId = "quiz.review.source.${question.ordinal}.${ref.evidenceId}",
                                    )
                                }
                                if (proof.reasonCodes.any { it in setOf("UNRELATED_EVIDENCE", "EVIDENCE_CONFLICT", "EVIDENCE_INVALID") }) {
                                    Text("可改用本次读取的其他引文，保存后重新核对")
                                    displayed.sources.forEach { source ->
                                        ReviewCheckbox(
                                            label = "${source.evidenceId} · ${source.quote}",
                                            checked = source.evidenceId in fields.evidenceIds,
                                            enabled = editable,
                                        ) { checked ->
                                            val before = fields.evidenceIds
                                            change(fields.copy(evidenceIds = if (checked) (before + source.evidenceId).distinct()
                                                else before - source.evidenceId))
                                        }
                                    }
                                }
                                proof.assertionChecks.forEach { assertion ->
                                    val selectedChecks = checks[question.ordinal].orEmpty()
                                    ReviewCheckbox(
                                        label = "已核对：${assertion.claimText}",
                                        checked = assertion.assertionKey in selectedChecks,
                                        enabled = editable && !itemDirty && reviewable,
                                    ) { checked ->
                                        val next = if (checked) selectedChecks + assertion.assertionKey else selectedChecks - assertion.assertionKey
                                        checks = checks + (question.ordinal to next)
                                    }
                                }
                                if (itemDirty) Text("修改尚未保存，保存后需重新核对。", color = MaterialTheme.colorScheme.error)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    UiAction(
                                        "保存修改",
                                        onClick = {
                                            runAction { ctx, ticket ->
                                                val updated = fields.item(question)
                                                if (updated == null) {
                                                    message = "评分点分值必须是整数"
                                                } else {
                                                    val saved = service.saveEdit(ctx, displayed.paperId,
                                                        displayed.reference.draftSha256, question.ordinal, updated)
                                                    if (current(ctx, ticket)) {
                                                        if (saved == null) message = "保存失败或草稿已变化，请重新载入"
                                                        else {
                                                            acceptLoaded(saved, editors - question.ordinal)
                                                            message = "已保存修改，请重新核对"
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        enabled = editable && itemDirty,
                                        actionId = "quiz.review.save.${question.ordinal}",
                                    )
                                    UiAction("删除此题", onClick = { deleteOrdinal = question.ordinal },
                                        enabled = editable, foreground = MaterialTheme.colorScheme.error,
                                        actionId = "quiz.review.deleteQuestion.${question.ordinal}")
                                }
                            }
                        }
                    }
                    item {
                        val allChecked = displayed.draft.items.indices.all { index ->
                            val item = displayed.draft.items[index]
                            val proof = displayed.supportAssessments[index]
                            (proof.state == QuizSupportState.SUPPORTED && proof.authority == "AUDITED_TEMPLATE") ||
                                (QuizPersonalReview.isReviewable(item, proof) &&
                                    checks[item.ordinal].orEmpty() == proof.assertionChecks.map { it.assertionKey }.toSet())
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (dirty) Text("还有修改尚未保存")
                            else if (!allChecked) Text("请逐项核对答案、评分点、错误选项和解析")
                            if (displayed.draft.items.size != 10) Text("题数不足。可继续修订草稿，或重新生成完整试卷。")
                            UiAction(
                                "仅用于我的练习，确认并创建新试卷",
                                onClick = {
                                    runAction { ctx, ticket ->
                                        when (val result = service.confirmPersonal(
                                            ctx, displayed.paperId, displayed.reference.draftSha256, checks, requestId,
                                        )) {
                                            is QuizReviewService.Outcome.Published -> if (current(ctx, ticket)) onOpenPaper(result.paperId)
                                            is QuizReviewService.Outcome.Rejected -> if (current(ctx, ticket)) {
                                                message = "无法发布个人练习：${result.code}"
                                            }
                                        }
                                    }
                                },
                                enabled = editable && !dirty && allChecked && displayed.draft.items.size == 10,
                                filled = true,
                                modifier = Modifier.fillMaxWidth(),
                                actionId = "quiz.review.createPaper",
                            )
                        }
                    }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (onRegenerate != null) {
                                UiAction("重新生成完整试卷", onClick = { requestDeparture(ReviewDeparture.REGENERATE) },
                                    enabled = editable, actionId = "quiz.review.regenerate")
                            }
                            UiAction("删除整份待审草稿", onClick = { discardRequested = true },
                                enabled = editable, foreground = MaterialTheme.colorScheme.error,
                                actionId = "quiz.review.requestDeleteDraft")
                        }
                    }
                }
            }
        }
    }

    pendingDeparture?.let { destination ->
        UiConfirmDialog(
            title = "有未保存的修改",
            message = when (destination) {
                ReviewDeparture.BACK -> "放弃尚未保存的内容并返回？"
                ReviewDeparture.REGENERATE -> "放弃尚未保存的内容并重新生成试卷？"
                ReviewDeparture.RELOAD -> "放弃尚未保存的内容并重新载入当前草稿？旧核对记录将清除。"
            },
            confirmLabel = if (destination == ReviewDeparture.RELOAD) "放弃并重新载入" else "放弃修改",
            dismissLabel = "继续编辑",
            onConfirm = { depart(destination) },
            onDismiss = { pendingDeparture = null },
            ownerKey = Triple(identityKey, selected, destination),
            actionId = "quiz.review.discardUnsaved",
        )
    }
    if (currentSession != null) {
        deleteOrdinal?.let { ordinal ->
            UiConfirmDialog(
                title = "删除这道草稿题？",
                message = "将删除第 $ordinal 题。题数不足时需要重新生成完整试卷。",
                confirmLabel = "删除",
                dismissLabel = "取消",
                onConfirm = {
                    deleteOrdinal = null
                    runAction { ctx, ticket ->
                        val saved = service.saveEdit(ctx, currentSession.paperId, currentSession.reference.draftSha256, ordinal, null)
                        if (current(ctx, ticket)) {
                            if (saved == null) message = "删除失败、草稿已变化或这已是最后一题"
                            else acceptLoaded(saved, editors - ordinal)
                        }
                    }
                },
                onDismiss = { deleteOrdinal = null },
                ownerKey = Triple(identityKey, currentSession.reference.draftSha256, ordinal),
                actionId = "quiz.review.deleteQuestion",
            )
        }
        if (discardRequested) {
            UiConfirmDialog(
                title = "删除待审草稿？",
                message = "这份草稿将从待核对列表移除，已创建的试卷与成绩仍保留。",
                confirmLabel = "删除",
                dismissLabel = "取消",
                onConfirm = {
                    discardRequested = false
                    runAction { ctx, ticket ->
                        val removed = service.discard(ctx, currentSession.paperId, currentSession.reference.draftSha256)
                        if (current(ctx, ticket)) {
                            if (removed) {
                                editors = emptyMap()
                                checks = emptyMap()
                                cachedHash = ""
                                selected = null
                            } else message = "草稿已变化或不可用，请重新载入"
                        }
                    }
                },
                onDismiss = { discardRequested = false },
                ownerKey = Triple(identityKey, currentSession.paperId, currentSession.reference.draftSha256),
                actionId = "quiz.review.deleteDraft",
            )
        }
    }
}

@Composable
private fun ReviewField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth())
}

private fun List<String>.replacing(index: Int, value: String): List<String> =
    toMutableList().apply { set(index, value) }

@Composable
private fun ReviewCheckbox(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
