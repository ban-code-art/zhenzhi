package com.example.myapplication.data.reader.export

import androidx.room.withTransaction
import com.example.myapplication.data.encyclopedia.EncyclopediaLoadState
import com.example.myapplication.data.encyclopedia.EncyclopediaPackRepository
import com.example.myapplication.data.encyclopedia.EncyclopediaUserRepository
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.EncyclopediaFavoriteEntity
import com.example.myapplication.data.local.entity.ReaderMutationReceiptEntity
import com.example.myapplication.data.quiz.PortablePracticeImport
import com.example.myapplication.data.quiz.SourcePracticeArchiveRepository
import com.example.myapplication.data.quiz.SourcePracticeDocumentBinding
import com.example.myapplication.data.quiz.SourcePracticeScope
import com.example.myapplication.data.review.*
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.review.ReviewSchedule
import kotlinx.serialization.json.*

/** Boundaries between portable review identities, the base restore's actual identities and the live account. */
class BackupV3RoomSupport(
    private val database: AppDatabase,
    private val sessions: SessionManager,
    private val practice: SourcePracticeArchiveRepository,
    private val encyclopediaPack: EncyclopediaPackRepository? = null,
) {
    internal data class Capture(
        val state: PortableReviewState,
        val practice: Map<String, ByteArray>,
        val privateQuestions: Set<Long>,
        val encyclopediaFavorites: List<PortableEncyclopediaFavorite> = emptyList(),
    )
    internal data class ReviewCounts(
        val items: Int, val events: Int, val sessions: Int, val archives: Int, val missingSources: Int,
        val encyclopediaFavorites: Int = 0, val skippedUnknownEncyclopediaPoints: Int = 0,
    )
    internal data class RestoreResult(
        val job: ConfirmedV3Restore,
        val counts: ReviewCounts,
        val unavailableArchives: Int,
    )
    internal data class PracticePlan(
        val inputs: Map<SourcePracticeDocumentBinding, SourcePracticeDocumentBinding?>,
        val bindingHash: String,
    )
    private data class ReceiptLookup(val value: RestoreResult?)
    private data class Card(val item: ReviewItemEntity, val ref: PortableReviewSource, val documentId: String?)
    private data class LocalSource(val kind: String, val key: String, val ref: String, val book: String?, val availability: String)

    internal suspend fun capture(identity: SessionIdentity, selected: Set<String>, standalone: Boolean): Capture {
        val selectedRows = read(identity) { user ->
            val dao = database.reviewDao()
            val cards = dao.items(user).map { item ->
                val ref = item.portableSource()
                Card(item, ref, sourceDocument(user, ref) ?: item.bookKey?.removePrefix("import:")?.takeIf {
                    item.bookKey.startsWith("import:") && it in selected
                })
            }
            Triple(cards, dao.sessions(user), dao.events(user))
        }
        val practiceCandidates = selectedRows.first.filter { it.ref.kind == "practice" }.mapNotNull { card ->
            val candidate = practice.load(identity, checkNotNull(card.ref.id)) ?: return@mapNotNull null
            val docs = sourceDocuments(candidate.scope)
            if (docs.isNotEmpty() && docs.all { it in selected }) card.item.id to candidate.id else null
        }.toMap()
        val portable = practice.exportPortable(identity, practiceCandidates.values.toSet())
        return read(identity) { user ->
            // Check the scoped Room snapshot again before combining it with dependent private archive bytes.
            val dao = database.reviewDao()
            v2require(selectedRows.first.map { it.item } == dao.items(user) &&
                selectedRows.second == dao.sessions(user) && selectedRows.third == dao.events(user),
                "EXPORT_SNAPSHOT_CHANGED", "review state changed during export preview")
            val chosen = selectedRows.first.filter { card ->
                card.documentId in selected || card.item.id in practiceCandidates || standalone && card.item.bookKey == null &&
                    card.ref.kind in setOf("standalone", "missing", "question", EncyclopediaUserRepository.REVIEW_SOURCE_KIND) &&
                    card.documentId == null
            }
            val privateQuestions = chosen.mapNotNull { card ->
                if (card.ref.kind != "question") return@mapNotNull null
                card.ref.id?.toLongOrNull()?.let { database.questionDao().findById(it) }?.takeIf {
                    it.ownerUserId == user && it.enabled
                }?.id
            }.toSet()
            val items = chosen.map { (item, ref, _) ->
                val portableRef = if (ref.kind == "practice") portable.recordToPortable[ref.id]?.let { ref.copy(id = it) }
                    ?: PortableReviewSource("missing") else ref
                item.toPortable(portableRef)
            }
            val cardIds = items.map { it.portableItemId }.toSet()
            val matchingSessions = selectedRows.second.filter { session ->
                session.bookKey?.takeIf { it.startsWith("import:") }?.removePrefix("import:") in selected
            }.map { it.portableSessionId }.toSet()
            val events = selectedRows.third.filter { event ->
                event.cardIdentity in cardIds || event.portableSessionId in matchingSessions
            }.map { it.toPortable() }.distinctBy { it.portableEventId }
            val includedEvents = events.groupBy { it.portableSessionId }
            val summaries = selectedRows.second.mapNotNull { session ->
                val full = session.portableSessionId in matchingSessions
                val count = includedEvents[session.portableSessionId]?.size ?: 0
                if (!full && count == 0) return@mapNotNull null
                PortableReviewSession(session.portableSessionId, session.bookKey.takeIf { full },
                    session.startedAtUtc, session.finishedAtUtc, count,
                    if (full) session.skippedCount else 0, if (full) session.unavailableCount else 0)
            }
            val favorites = database.encyclopediaFavoriteDao().getByUser(user)
                .map { PortableEncyclopediaFavorite(it.pointCode, it.createdAt) }
            Capture(PortableReviewState(items, summaries, events), portable.entries, privateQuestions, favorites)
        }
    }

    internal suspend fun exportStillCurrent(expected: Capture, identity: SessionIdentity, selected: Set<String>, standalone: Boolean) {
        val current = capture(identity, selected, standalone)
        v2require(current.state == expected.state && current.privateQuestions == expected.privateQuestions &&
            current.encyclopediaFavorites == expected.encyclopediaFavorites &&
            current.practice.keys == expected.practice.keys && expected.practice.all { (id, bytes) -> current.practice.getValue(id).contentEquals(bytes) },
            "EXPORT_SNAPSHOT_CHANGED", "review state changed after preview")
    }

    internal fun validate(snapshot: BackupV3Archive.Snapshot): Map<String, SourcePracticeScope> {
        val baseDocuments = snapshot.base.backup.manifest.documents.map { it.exportDocumentId }.toSet()
        val practiceSources = snapshot.practiceEntries().mapValues { (id, bytes) ->
            SourcePracticeArchiveRepository.validatePortablePractice(id, bytes).also { scope ->
                v2require(baseDocuments.containsAll(sourceDocuments(scope)), "INVALID_REVIEW_SOURCE",
                    "source-practice references an unselected document")
            }
        }
        // A card may legitimately lack its source. Unknown IDs never bind to preexisting local rows.
        return practiceSources
    }

    internal fun estimate(snapshot: BackupV3Archive.Snapshot, base: BackupV2RestorePlan, ids: BackupV2IdMap,
        archives: Map<String, SourcePracticeScope>): ReviewCounts {
        val state = snapshot.review
        val absentDocuments = base.documentDecisions.filter {
            it.disposition == BackupV2DocumentDisposition.PENDING || it.disposition == BackupV2DocumentDisposition.BLOCKED
        }.map { it.sourceId }.toSet()
        val missing = state.items.count { item ->
            val ref = item.source
            val doc = item.bookKey?.takeIf { it.startsWith("import:") }?.removePrefix("import:")
            when (ref.kind) {
                "missing" -> true
                "standalone" -> false
                EncyclopediaUserRepository.REVIEW_SOURCE_KIND -> {
                    val encSnap = encyclopediaPack?.currentSnapshot
                    encSnap != null && ref.id?.let { encSnap.pointsByCode[it] } == null
                }
                "practice" -> archives[ref.id]?.let { scope ->
                    sourceDocuments(scope).any { it !in ids.documents || it in absentDocuments }
                } != false
                else -> tableFor(ref.kind)?.let { table ->
                    ids.tables[table]?.containsKey(ref.id) != true || doc in absentDocuments ||
                        base.rowDecisions.any { it.table == table && it.sourceKey == ref.id && it.disposition != BackupV2RowDisposition.INSERT }
                } != false
            }
        }
        return ReviewCounts(state.items.size, state.events.size, state.sessions.size, archives.size, missing, snapshot.encyclopedia.favorites.size)
    }

    internal suspend fun targetVersion(identity: SessionIdentity): String = read(identity, ::targetVersion)

    /** Prevent account switches across the base commit boundary without changing the V2 entry point. */
    internal suspend fun <T : Any> withCurrentIdentity(identity: SessionIdentity, operation: suspend () -> T): T =
        sessions.withCurrentIdentity(identity, operation) ?: throw ReviewIdentityExpiredException()

    private suspend fun targetVersion(user: Long): String {
        val dao = database.reviewDao()
        return v2Digest(buildString {
            appendLine(dao.items(user))
            appendLine(dao.sessions(user))
            appendLine(dao.events(user))
            appendLine(database.encyclopediaFavoriteDao().getByUser(user))
        })
    }

    internal suspend fun practicePlan(identity: SessionIdentity, scopes: Map<String, SourcePracticeScope>, ids: BackupV2IdMap): PracticePlan =
        read(identity) { user -> practicePlan(user, scopes, ids) }

    private suspend fun practicePlan(user: Long, scopes: Map<String, SourcePracticeScope>, ids: BackupV2IdMap): PracticePlan {
        val bindings = scopes.toSortedMap().values.flatMap { scope ->
            listOfNotNull(scope.documentId?.let { SourcePracticeDocumentBinding(it, scope.sourceRevision, scope.sectionIds) }) +
                scope.relatedSources.map(::practiceKey)
        }.distinct().associateWith { source ->
            val target = ids.documents[source.documentId] ?: return@associateWith null
            val document = readable(user, target)?.takeIf { it.contentRevision == source.sourceRevision }
                ?: return@associateWith null
            val sections = source.sectionIds.map { ids.sections[it] ?: return@associateWith null }
            val actual = database.readerCatalogDao().findSections(target, source.sourceRevision).map { it.id }.toSet()
            if (!actual.containsAll(sections)) return@associateWith null
            SourcePracticeDocumentBinding(target, document.contentRevision, sections, document.ownerUserId,
                document.accessEpoch, document.metadataRevision, document.extractorVersion)
        }
        val digest = v2Digest(v2Canonical(JsonArray(bindings.map { (source, target) ->
            JsonArray(listOf(practiceBindingJson(source), target?.let(::practiceBindingJson) ?: JsonNull))
        })))
        return PracticePlan(bindings, digest)
    }

    private fun practiceKey(binding: SourcePracticeDocumentBinding) =
        SourcePracticeDocumentBinding(binding.documentId, binding.sourceRevision, binding.sectionIds)

    private fun practiceBindingJson(binding: SourcePracticeDocumentBinding) = buildJsonObject {
        put("document", binding.documentId)
        put("revision", binding.sourceRevision)
        put("sections", JsonArray(binding.sectionIds.map(::JsonPrimitive)))
        put("owner", binding.ownerUserId?.toString())
        put("accessEpoch", binding.accessEpoch?.toString())
        put("metadataRevision", binding.metadataRevision?.toString())
        put("extractor", binding.extractorVersion)
    }

    internal suspend fun importPractice(identity: SessionIdentity, snapshot: BackupV3Archive.Snapshot, ids: BackupV2IdMap,
        plan: PracticePlan) = practice.importPortable(identity, snapshot.practiceEntries(),
        rebind = { binding -> plan.inputs.getValue(practiceKey(binding)) }, mapSection = { ids.sections[it] })

    internal suspend fun restoreResult(identity: SessionIdentity, jobId: String): RestoreResult? =
        read(identity) { user -> ReceiptLookup(result(user, jobId)?.takeIf { it.job.identity == identity }) }.value

    private suspend fun result(user: Long, jobId: String): RestoreResult? {
        val receipt = database.readerNotebookDao().findReceipt(user, "restore-v3:$jobId") ?: return null
        val payload = BackupV2Json.obj(BackupV2Json.parse(receipt.resultJson.toByteArray(Charsets.UTF_8), 8192),
            setOf("confirmation", "items", "events", "sessions", "archives", "missingSources", "unavailableArchives"))
        val job = ConfirmedV3Restore.decode(payload.getValue("confirmation"))
        v2require(receipt.targetKind == "RESTORE_V3" && job.jobId == jobId && job.identity.userId == user &&
            receipt.targetId == job.packageSha256 && receipt.requestHash == job.bindingHash,
            "INVALID_RECEIPT", "V3 Room receipt scope")
        fun count(key: String): Int {
            val value = BackupV2Json.integer(payload[key])
            v2require(value in 0..BackupV2Limits.RECORDS.toLong(), "INVALID_RECEIPT", "V3 receipt count")
            return value.toInt()
        }
        return RestoreResult(job, ReviewCounts(count("items"), count("events"), count("sessions"), count("archives"),
            count("missingSources")), count("unavailableArchives"))
    }

    internal suspend fun importReview(
        job: ConfirmedV3Restore,
        state: PortableReviewState,
        ids: BackupV2IdMap,
        archives: Map<String, SourcePracticeScope>,
        imported: PortablePracticeImport,
        frozenPractice: PracticePlan,
        encyclopedia: PortableEncyclopediaState = PortableEncyclopediaState(),
    ): RestoreResult {
        val identity = job.identity
        val encSnapshot = encyclopediaPack?.currentSnapshot
            ?: (encyclopediaPack?.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot
        val practiceIds = imported.recordIds
        // File-repository reads take their own identity lock. Materialize verified practice question bindings first.
            val practiceRecords = practiceIds.values.associateWith { practice.load(identity, it) }
        return read(identity) { user ->
            result(user, job.jobId)?.let { original ->
                v2require(original.job == job, "JOURNAL_PLAN_CONFLICT", "V3 Room receipt has different confirmation")
                return@read original
            }
            v2require(targetVersion(user) == job.reviewTargetVersion, "RESTORE_PLAN_CHANGED", "review changed after preview")
            v2require(practicePlan(user, archives, ids).bindingHash == frozenPractice.bindingHash,
                "RESTORE_PLAN_CHANGED", "practice sources changed after publication")
            val dao = database.reviewDao()
            val cardIds = linkedMapOf<String, ReviewItemEntity>()
            var missing = 0
            state.items.forEach { incoming ->
                val ref = incoming.source
                val practiceRecord = practiceIds[ref.id]?.let { practiceRecords[it] }
                val practiceId = practiceRecord?.takeIf { record -> record.practice.questions.any { it.id == ref.question } }
                    ?.id?.takeIf {
                        val scope = archives[ref.id]
                        scope != null && sourceDocuments(scope).all { original ->
                            ids.documents[original]?.let { target -> readable(user, target) != null } == true
                        }
                    }
                val source = resolve(user, incoming, ids, practiceId, encSnapshot)
                if (source.availability == ReviewAvailability.SOURCE_MISSING) missing++
                val bySource = dao.itemBySource(user, source.kind, source.key)
                val byPortable = dao.itemByPortableId(user, incoming.portableItemId)
                v2require(byPortable == null || source.kind == "missing" || byPortable.sourceNamespace == "missing" ||
                    byPortable.sourceNamespace == source.kind && byPortable.sourceKey == source.key,
                    "REVIEW_ID_COLLISION", "portable card ID refers to a different local source")
                val selected = bySource ?: byPortable
                val entity = when {
                    selected == null -> {
                        val row = incoming.toEntity(user, source)
                        row.copy(id = dao.insertItem(row))
                    }
                    // A prior partial restore may have installed a source-missing placeholder.
                    selected.sourceNamespace == "missing" && source.kind != "missing" -> {
                        val resolved = selected.copy(sourceNamespace = source.kind, sourceKey = source.key,
                            sourceRefJson = source.ref, bookKey = source.book, availability = source.availability,
                            itemRevision = Math.addExact(selected.itemRevision, 1))
                        dao.updateItem(resolved)
                        resolved
                    }
                    else -> selected
                }
                val scheduleChanged = entity.stage != incoming.stage || entity.dueAtUtc != incoming.dueAtUtc ||
                    entity.lastReviewedAtUtc != incoming.lastReviewedAtUtc || entity.paused != incoming.paused
                val updated = if (job.policy == DataManagementConflictPolicy.REPLACE_REVIEW_SCHEDULE &&
                    selected != null && scheduleChanged) entity.copy(
                    stage = incoming.stage, paused = incoming.paused, dueAtUtc = incoming.dueAtUtc,
                    lastReviewedAtUtc = incoming.lastReviewedAtUtc, itemRevision = Math.addExact(entity.itemRevision, 1),
                    updatedAtUtc = maxOf(entity.updatedAtUtc, incoming.updatedAtUtc)).also { dao.updateItem(it) } else entity
                cardIds[incoming.portableItemId] = updated
            }
            val sessionIds = state.sessions.associate { summary ->
                val existing = dao.sessionByPortableId(user, summary.portableSessionId)
                val id = existing?.takeIf { it.activeUserId == null }?.id ?: if (existing == null) {
                    dao.insertSession(ReviewSessionEntity(portableSessionId = summary.portableSessionId, userId = user,
                        activeUserId = null, startOperationId = null, bookKey = remapBook(summary.bookKey, ids),
                        status = ReviewSessionStatus.CLOSED_IMPORTED, startedAtUtc = summary.startedAtUtc,
                        finishedAtUtc = summary.finishedAtUtc, completedCount = summary.completedCount,
                        skippedCount = summary.skippedCount, unavailableCount = summary.unavailableCount))
                } else null
                summary.portableSessionId to id
            }
            // No learning-activity insert is performed. The history count is derived from review_events.
            state.events.forEach { event ->
                if (dao.eventByPortableId(user, event.portableEventId) != null) return@forEach
                val card = cardIds[event.cardIdentity]
                dao.insertEvent(ReviewEventEntity(portableEventId = event.portableEventId, userId = user,
                    sessionId = sessionIds[event.portableSessionId], itemId = card?.id,
                    portableSessionId = event.portableSessionId, cardIdentity = card?.portableItemId ?: event.cardIdentity,
                    ordinal = event.ordinal, operationId = "import:${event.portableEventId}", rating = event.rating,
                    beforeStage = event.beforeStage, afterStage = event.afterStage, beforeDueAtUtc = event.beforeDueAtUtc,
                    afterDueAtUtc = event.afterDueAtUtc, snapshotRevision = event.snapshotRevision,
                    occurredAtUtc = event.occurredAtUtc, localDate = event.localDate, timezoneId = event.timezoneId, activeMs = event.activeMs))
            }
            var restoredFavorites = 0
            var skippedUnknownPoints = 0
            val favDao = database.encyclopediaFavoriteDao()
            encyclopedia.favorites.forEach { fav ->
                if (encSnapshot != null && encSnapshot.pointsByCode[fav.pointCode] == null) {
                    skippedUnknownPoints++
                } else {
                    favDao.insertIgnore(
                        EncyclopediaFavoriteEntity(userId = user, pointCode = fav.pointCode, createdAt = fav.createdAtUtc),
                    )
                    restoredFavorites++
                }
            }
            val review = ReviewCounts(state.items.size, state.events.size, state.sessions.size, practiceIds.size, missing,
                restoredFavorites, skippedUnknownPoints)
            val result = RestoreResult(job, review, imported.unavailable.size)
            val payload = buildJsonObject {
                put("confirmation", job.encode())
                put("items", review.items)
                put("events", review.events)
                put("sessions", review.sessions)
                put("archives", review.archives)
                put("missingSources", review.missingSources)
                put("unavailableArchives", result.unavailableArchives)
                put("encyclopediaFavorites", restoredFavorites)
                put("skippedUnknownEncyclopediaPoints", skippedUnknownPoints)
            }
            // Both the review changes and this authoritative receipt commit in the same Room transaction.
            database.readerNotebookDao().insertReceipt(ReaderMutationReceiptEntity(user, job.operationId, "RESTORE_V3",
                job.packageSha256, job.bindingHash, 0, 1, v2Canonical(payload), System.currentTimeMillis()))
            result
        }
    }

    private suspend fun resolve(
        user: Long,
        item: PortableReviewItem,
        ids: BackupV2IdMap,
        practiceId: String?,
        encSnapshot: com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot? = encyclopediaPack?.currentSnapshot,
    ): LocalSource {
        val source = item.source
        var book = remapBook(item.bookKey, ids)
        var revision: String? = null
        val mapped = tableFor(source.kind)?.let { ids.tables[it]?.get(source.id) }
        val target = when (source.kind) {
            "annotation" -> mapped?.let { database.readerAnnotationDao().findAnnotation(user, it) }?.takeIf { it.deletedAt == null }
                ?.let { row ->
                    val doc = readable(user, row.documentId)
                    val locator = ReaderLocatorCodec.decodeOrNull(row.locatorJson)
                    if (doc == null || locator?.documentId != doc.id || locator.contentRevision != doc.contentRevision) null
                    else row.id.also { book = "import:${doc.id}"; revision = row.revision.toString() }
                }
            "notebook" -> mapped?.let { database.readerNotebookDao().findNotebook(user, it) }?.takeIf { it.bodyMarkdown.isNotBlank() }
                ?.let { row -> readable(user, row.documentId)?.let { doc ->
                    row.id.also { book = "import:${doc.id}"; revision = row.revision.toString() }
                } }
            "question" -> mapped?.toLongOrNull()?.let { database.questionDao().findById(it) }?.takeIf {
                it.enabled && (it.ownerUserId == null || it.ownerUserId == user) &&
                    (it.sourceDocumentId == null || readable(user, it.sourceDocumentId) != null)
            }?.let { row ->
                row.id.toString().also {
                    book = row.sourceDocumentId?.let { "import:$it" } ?: book
                    revision = row.sourceRevision ?: row.createdAt.toString()
                }
            }
            "practice" -> practiceId?.also { revision = item.sourceRevision }
            EncyclopediaUserRepository.REVIEW_SOURCE_KIND -> {
                val pointCode = source.id
                if (pointCode != null && (encSnapshot == null || encSnapshot.pointsByCode[pointCode] != null)) {
                    revision = encSnapshot?.manifest?.contentRevision ?: item.sourceRevision
                    pointCode
                } else null
            }
            "standalone" -> return LocalSource("standalone", "standalone:${item.portableItemId}",
                buildJsonObject { put("kind", "standalone") }.toString(), null, ReviewAvailability.AVAILABLE)
            else -> null
        }
        if (target == null) return LocalSource("missing", "missing:${item.portableItemId}",
            buildJsonObject { put("kind", "missing") }.toString(), book, ReviewAvailability.SOURCE_MISSING)
        val key = if (source.kind == EncyclopediaUserRepository.REVIEW_SOURCE_KIND) {
            "${EncyclopediaUserRepository.REVIEW_NAMESPACE}:$target"
        } else {
            "${source.kind}:$target" + (source.question?.let { ":$it" } ?: "")
        }
        val reference = buildJsonObject {
            put("kind", source.kind)
            put("id", target)
            source.question?.let { put("question", it) }
        }.toString()
        val availability = if (revision == item.sourceRevision) ReviewAvailability.AVAILABLE else ReviewAvailability.SOURCE_CHANGED
        val namespace = if (source.kind == EncyclopediaUserRepository.REVIEW_SOURCE_KIND) {
            EncyclopediaUserRepository.REVIEW_NAMESPACE
        } else {
            source.kind
        }
        return LocalSource(namespace, key, reference, book, availability)
    }

    private suspend fun sourceDocument(user: Long, ref: PortableReviewSource): String? {
        val id = ref.id ?: return null
        return when (ref.kind) {
            "annotation" -> database.readerAnnotationDao().findAnnotation(user, id)?.documentId
            "notebook" -> database.readerNotebookDao().findNotebook(user, id)?.documentId
            "question" -> id.toLongOrNull()?.let { database.questionDao().findById(it) }?.takeIf {
                it.ownerUserId == user || it.ownerUserId == null && it.enabled
            }?.let { question ->
                question.sourceDocumentId ?: question.chapterId?.let { database.readerCatalogDao().findBuiltInByChapter(it)?.id }
            }
            else -> null
        }
    }

    private suspend fun readable(user: Long, id: String) = database.readerCatalogDao().findDocument(id)?.takeIf {
        it.deletedAt == null && it.importStatus == "READY" &&
            (it.ownerUserId == user || it.ownerUserId == null && it.kind == "BUILTIN") &&
            database.readerStateDao().find(user, id)?.isHidden != true
    }

    private suspend fun <T : Any> read(identity: SessionIdentity, block: suspend (Long) -> T): T {
        val user = identity.userId?.takeIf { it > 0 } ?: throw ReviewIdentityExpiredException()
        return sessions.withCurrentIdentity(identity) { database.withTransaction { block(user) } }
            ?: throw ReviewIdentityExpiredException()
    }

    private companion object {
        fun sourceDocuments(scope: SourcePracticeScope): Set<String> = buildSet {
            scope.documentId?.let(::add)
            scope.bundledChapterKey?.let { add("builtin:chapter:$it") }
            scope.relatedSources.forEach { add(it.documentId) }
        }

        fun remapBook(book: String?, ids: BackupV2IdMap): String? = when {
            book?.startsWith("import:") == true -> ids.documents[book.removePrefix("import:")]?.let { "import:$it" }
            book?.startsWith("course:") == true && ids.courseChapters.keys.any { it.bookKey == book.removePrefix("course:") } -> book
            else -> null
        }

        fun tableFor(kind: String) = when (kind) {
            "annotation" -> BackupV2Table.READER_ANNOTATIONS
            "notebook" -> BackupV2Table.READER_NOTEBOOKS
            "question" -> BackupV2Table.QUESTIONS
            else -> null
        }

        fun ReviewItemEntity.portableSource(): PortableReviewSource {
            val ref = runCatching { Json.parseToJsonElement(sourceRefJson).jsonObject }.getOrNull()
                ?: return PortableReviewSource("missing")
            val kind = (ref["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = (ref["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val question = (ref["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when {
                kind == "standalone" -> PortableReviewSource(kind)
                kind in setOf("annotation", "notebook", "question", "practice", EncyclopediaUserRepository.REVIEW_SOURCE_KIND) && id != null ->
                    PortableReviewSource(checkNotNull(kind), id, question)
                else -> PortableReviewSource("missing")
            }
        }

        fun ReviewItemEntity.toPortable(ref: PortableReviewSource) = PortableReviewItem(portableItemId, ref,
            bookKey, Json.parseToJsonElement(sourceTags).jsonArray.map { it.jsonPrimitive.content }, sourceTitle,
            sourceRevision, prompt, answerSnapshot, priority, paused, stage, dueAtUtc, lastReviewedAtUtc, itemRevision,
            createdAtUtc, updatedAtUtc)

        fun ReviewEventEntity.toPortable() = PortableReviewEvent(portableEventId, portableSessionId, cardIdentity, ordinal,
            rating, beforeStage, afterStage, beforeDueAtUtc, afterDueAtUtc, snapshotRevision, occurredAtUtc, localDate, timezoneId, activeMs)

        fun PortableReviewItem.toEntity(user: Long, source: LocalSource) = ReviewItemEntity(
            portableItemId = portableItemId, userId = user, sourceNamespace = source.kind, sourceKey = source.key,
            sourceRefJson = source.ref, sourceTags = JsonArray(tags.map(::JsonPrimitive)).toString(), sourceTitle = sourceTitle,
            bookKey = source.book, priority = priority, prompt = prompt, answerSnapshot = answer,
            snapshotHash = v2Digest(JsonArray(listOf(JsonPrimitive(prompt), JsonPrimitive(answer))).toString()),
            sourceRevision = sourceRevision, itemRevision = itemRevision, creationOperationId = null, excerptFingerprint = null,
            availability = source.availability, paused = paused, stage = stage, dueAtUtc = dueAtUtc,
            lastReviewedAtUtc = lastReviewedAtUtc, policyVersion = ReviewSchedule.FIXED_INTERVAL_V1,
            createdAtUtc = createdAtUtc, updatedAtUtc = updatedAtUtc,
        )
    }
}
