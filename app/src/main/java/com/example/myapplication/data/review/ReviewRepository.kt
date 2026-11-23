package com.example.myapplication.data.review

import androidx.room.withTransaction
import com.example.myapplication.data.learning.localLearningDate
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.sha256Hex
import com.example.myapplication.domain.review.ReviewRating
import com.example.myapplication.domain.review.ReviewSchedule
import java.util.TimeZone
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ReviewRepository(
    private val database: AppDatabase,
    private val sessionManager: SessionManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
    private val sourceStatus: (suspend (SessionIdentity, ReviewItemEntity) -> String)? = null,
    private val legacySources: ReviewSourceCatalog? = null,
) {
    private val dao = database.reviewDao()

    suspend fun summary(identity: SessionIdentity, scope: ReviewScope = ReviewScope.All): ReviewSummary =
        authorized(identity) { userId ->
            val now = clock()
            val bookKey = scope.bookKey()
            val preview = verifiedDueItems(identity, userId, bookKey, now).take(PREVIEW_LIMIT)
            val dueCount = dao.countDueItems(userId, bookKey, now)
            val active = dao.activeSession(userId)
            ReviewSummary(
                dueCount = dueCount,
                nextDueAtUtc = dao.nextDueAt(userId, bookKey, now),
                todayReviewedCount = dao.reviewedCardCount(userId, localLearningDate(now, zone())),
                totalItemCount = dao.countItems(userId, bookKey),
                activeSessionId = active?.id,
                remainingCount = active?.let { dao.remainingItems(it.id) } ?: 0,
                preview = preview,
                estimatedMinutes = (minOf(dueCount, QUEUE_LIMIT) + 1) / 2,
            )
        }

    suspend fun items(identity: SessionIdentity): List<ReviewItemEntity> =
        authorized(identity) { userId ->
            val pending = pendingCards(userId)
            dao.items(userId).map { card -> repairLegacyCard(identity, card, clock(), pending[card.id]) }
        }

    suspend fun reviewedDates(identity: SessionIdentity): Set<String> =
        authorized(identity) { userId -> dao.reviewedDates(userId).toSet() }

    suspend fun addItems(identity: SessionIdentity, candidates: List<ReviewCandidate>): List<ReviewItemEntity> =
        authorized(identity) { userId ->
            val now = clock()
            val pending = pendingCards(userId)
            candidates.mapNotNull { candidate ->
                if (!candidate.isValid()) return@mapNotNull null
                val existing = findExisting(userId, candidate)
                val proposed = candidate.toEntity(userId, now)
                if (effectiveAvailability(identity, proposed) != ReviewAvailability.AVAILABLE) return@mapNotNull null
                when {
                    existing == null -> proposed.copy(id = dao.insertItem(proposed))
                    existing.hasLegacyGeneratedPrompt() ->
                        repairLegacyCard(identity, existing, now, pending[existing.id], retryIncomplete = true)
                    else -> existing
                }
            }.distinctBy(ReviewItemEntity::id)
        }

    suspend fun start(identity: SessionIdentity, scope: ReviewScope, operationId: String): ReviewSessionState? {
        requireOperationId(operationId)
        return authorized(identity) { userId ->
            val bookKey = scope.bookKey()
            dao.sessionByStartOperation(userId, operationId)?.let { existing ->
                if (existing.bookKey != bookKey) conflict("Review start operation was already used for another scope")
                return@authorized loadSessionState(identity, existing)
            }
            dao.activeSession(userId)?.let { return@authorized loadSessionState(identity, it) }

            val now = clock()
            val dueItems = verifiedDueItems(identity, userId, bookKey, now)
            if (dueItems.isEmpty()) return@authorized null
            val session = ReviewSessionEntity(
                portableSessionId = newPortableId(),
                userId = userId,
                activeUserId = userId,
                startOperationId = operationId,
                bookKey = bookKey,
                status = ReviewSessionStatus.ACTIVE,
                startedAtUtc = now,
                finishedAtUtc = null,
            )
            val storedSession = session.copy(id = dao.insertSession(session))
            dao.insertSessionItems(dueItems.mapIndexed { ordinal, card -> card.freeze(storedSession.id, ordinal) })
            loadSessionState(identity, storedSession)
        }
    }

    suspend fun session(identity: SessionIdentity, sessionId: Long): ReviewSessionState? =
        authorized(identity) { userId ->
            dao.session(userId, sessionId)?.let { loadSessionState(identity, it) }
        }

    suspend fun reveal(
        identity: SessionIdentity,
        ticket: ReviewCardTicket,
        allowFrozenSource: Boolean = false,
    ): ReviewSessionState = authorized(identity) { userId ->
        val (session, slot) = currentSlot(userId, ticket)
        requireUsable(identity, slot, allowFrozenSource)
        if (!slot.revealed) check(dao.updateSessionItem(slot.copy(revealed = true)) == 1)
        loadSessionState(identity, session)
    }

    suspend fun saveDraft(
        identity: SessionIdentity,
        ticket: ReviewCardTicket,
        draft: String?,
        allowFrozenSource: Boolean = false,
    ): ReviewSessionState {
        require(draft == null || draft.length <= MAX_ANSWER_CHARS)
        return authorized(identity) { userId ->
            val (session, slot) = currentSlot(userId, ticket)
            requireUsable(identity, slot, allowFrozenSource)
            if (slot.draftAnswer != draft) check(dao.updateSessionItem(slot.copy(draftAnswer = draft)) == 1)
            loadSessionState(identity, session)
        }
    }

    suspend fun skip(identity: SessionIdentity, ticket: ReviewCardTicket): ReviewSessionState =
        advanceWithoutReview(identity, ticket, ReviewSessionItemStatus.SKIPPED)

    suspend fun unavailable(identity: SessionIdentity, ticket: ReviewCardTicket): ReviewSessionState =
        advanceWithoutReview(identity, ticket, ReviewSessionItemStatus.UNAVAILABLE)

    suspend fun finish(identity: SessionIdentity, sessionId: Long): ReviewSessionState =
        authorized(identity) { userId ->
            val session = requireSession(userId, sessionId)
            if (session.status != ReviewSessionStatus.ACTIVE) return@authorized loadSessionState(identity, session)
            dao.abandonPendingItems(session.id)
            loadSessionState(identity, closeSession(session, clock()))
        }

    suspend fun submit(
        identity: SessionIdentity,
        ticket: ReviewCardTicket,
        operationId: String,
        rating: ReviewRating,
        activeMs: Long = 0,
        allowFrozenSource: Boolean = false,
    ): ReviewEventEntity {
        requireOperationId(operationId)
        require(activeMs >= 0)
        return authorized(identity) { userId ->
            dao.eventByOperation(userId, operationId)?.let { event ->
                if (event.sessionId != ticket.sessionId || event.ordinal != ticket.ordinal ||
                    event.snapshotRevision != ticket.snapshotRevision || event.rating != rating.name
                ) {
                    conflict("Review operation was already used for a different submission")
                }
                return@authorized event
            }
            val (session, slot) = currentSlot(userId, ticket)
            if (!slot.revealed) conflict("Show the review answer before submitting")
            val card = requireUsable(identity, slot, allowFrozenSource)
            if (card.policyVersion != ReviewSchedule.FIXED_INTERVAL_V1) conflict("Unknown review schedule policy")
            val scheduled = ReviewSchedule.next(card.stage, card.lastReviewedAtUtc, clock(), rating)
            val timeZone = zone()
            val event = ReviewEventEntity(
                portableEventId = newPortableId(),
                userId = userId,
                sessionId = session.id,
                itemId = card.id,
                portableSessionId = session.portableSessionId,
                cardIdentity = slot.cardIdentity,
                ordinal = slot.ordinal,
                operationId = operationId,
                rating = rating.name,
                beforeStage = card.stage,
                afterStage = scheduled.stage,
                beforeDueAtUtc = card.dueAtUtc,
                afterDueAtUtc = scheduled.dueAtUtc,
                snapshotRevision = slot.snapshotRevision,
                occurredAtUtc = scheduled.reviewedAtUtc,
                localDate = localLearningDate(scheduled.reviewedAtUtc, timeZone),
                timezoneId = timeZone.id,
                activeMs = activeMs,
            )
            val storedEvent = event.copy(id = dao.insertEvent(event))
            check(dao.updateItem(card.copy(
                stage = scheduled.stage,
                dueAtUtc = scheduled.dueAtUtc,
                lastReviewedAtUtc = scheduled.reviewedAtUtc,
                updatedAtUtc = maxOf(card.updatedAtUtc, scheduled.reviewedAtUtc),
            )) == 1)
            check(dao.updateSessionItem(slot.copy(
                status = ReviewSessionItemStatus.COMPLETED,
                submittedEventId = storedEvent.id,
            )) == 1)
            saveProgress(session.copy(completedCount = session.completedCount + 1), scheduled.reviewedAtUtc)
            storedEvent
        }
    }

    suspend fun setPaused(
        identity: SessionIdentity,
        itemId: Long,
        expectedRevision: Long,
        paused: Boolean,
    ): ReviewItemEntity = authorized(identity) { userId ->
        val item = requireItem(userId, itemId, expectedRevision)
        val updated = item.copy(paused = paused, updatedAtUtc = maxOf(item.updatedAtUtc, clock()))
        check(dao.updateItem(updated) == 1)
        updated
    }

    suspend fun updateCard(
        identity: SessionIdentity,
        itemId: Long,
        expectedRevision: Long,
        candidate: ReviewCandidate,
    ): ReviewItemEntity {
        require(candidate.isValid())
        return authorized(identity) { userId ->
            val item = requireItem(userId, itemId, expectedRevision)
            if (item.sourceNamespace != candidate.sourceNamespace || item.sourceKey != candidate.sourceKey) {
                conflict("The source of this review card has changed")
            }
            val now = clock()
            val proposed = candidate.toEntity(userId, now)
            if (effectiveAvailability(identity, proposed) != ReviewAvailability.AVAILABLE) conflict("Review source is unavailable")
            val reset = item.copy(
                sourceRefJson = proposed.sourceRefJson,
                sourceTags = proposed.sourceTags,
                sourceTitle = proposed.sourceTitle,
                bookKey = proposed.bookKey,
                priority = proposed.priority,
                prompt = proposed.prompt,
                answerSnapshot = proposed.answerSnapshot,
                snapshotHash = proposed.snapshotHash,
                sourceRevision = proposed.sourceRevision,
                itemRevision = Math.addExact(item.itemRevision, 1L),
                availability = ReviewAvailability.AVAILABLE,
                stage = ReviewSchedule.INITIAL_STAGE,
                dueAtUtc = now,
                updatedAtUtc = maxOf(item.updatedAtUtc, now),
            )
            check(dao.updateItem(reset) == 1)
            reset
        }
    }

    suspend fun refreshSourceState(
        identity: SessionIdentity,
        itemId: Long,
        expectedRevision: Long,
        availability: String,
        sourceTitle: String? = null,
    ): ReviewItemEntity {
        require(availability in AVAILABILITY_VALUES)
        require(sourceTitle == null || sourceTitle.length <= MAX_TITLE_CHARS)
        return authorized(identity) { userId ->
            val item = requireItem(userId, itemId, expectedRevision)
            val nextAvailability = if (
                item.availability == ReviewAvailability.SOURCE_CHANGED && availability == ReviewAvailability.AVAILABLE
            ) item.availability else availability
            val updated = item.copy(
                sourceTitle = sourceTitle ?: item.sourceTitle,
                availability = nextAvailability,
                updatedAtUtc = maxOf(item.updatedAtUtc, clock()),
            )
            check(dao.updateItem(updated) == 1)
            updated
        }
    }

    suspend fun deleteItem(identity: SessionIdentity, itemId: Long, expectedRevision: Long): Boolean =
        authorized(identity) { userId ->
            val item = dao.item(userId, itemId) ?: return@authorized false
            if (item.itemRevision != expectedRevision) conflict("Review card revision has changed")
            dao.deleteItem(userId, itemId) == 1
        }

    private suspend fun findExisting(userId: Long, candidate: ReviewCandidate): ReviewItemEntity? {
        val byOperation = candidate.creationOperationId?.let { dao.itemByCreationOperation(userId, it) }
        if (byOperation != null) {
            if (byOperation.sourceNamespace != candidate.sourceNamespace ||
                byOperation.excerptFingerprint != candidate.excerptFingerprint ||
                (candidate.excerptFingerprint == null && byOperation.sourceKey != candidate.sourceKey)
            ) conflict("Review creation operation was already used for another source")
            return byOperation
        }
        return dao.itemBySource(userId, candidate.sourceNamespace, candidate.sourceKey)
            ?: candidate.excerptFingerprint?.let { dao.itemByExcerpt(userId, candidate.sourceNamespace, it) }
    }

    private suspend fun advanceWithoutReview(
        identity: SessionIdentity,
        ticket: ReviewCardTicket,
        status: String,
    ): ReviewSessionState = authorized(identity) { userId ->
        val (session, slot) = currentSlot(userId, ticket)
        val unavailable = status == ReviewSessionItemStatus.UNAVAILABLE
        val updated = if (unavailable) {
            slot.copy(
                status = status,
                sourceRevision = null,
                sourceRefJson = null,
                sourceTitle = null,
                prompt = null,
                answerSnapshot = null,
                draftAnswer = null,
                revealed = false,
            )
        } else {
            slot.copy(status = status, draftAnswer = null)
        }
        check(dao.updateSessionItem(updated) == 1)
        val withCounts = if (unavailable) {
            session.copy(unavailableCount = session.unavailableCount + 1)
        } else {
            session.copy(skippedCount = session.skippedCount + 1)
        }
        loadSessionState(identity, saveProgress(withCounts, clock()))
    }

    private suspend fun currentSlot(userId: Long, ticket: ReviewCardTicket): Pair<ReviewSessionEntity, ReviewSessionItemEntity> {
        val session = requireSession(userId, ticket.sessionId)
        if (session.activeUserId != userId || session.status != ReviewSessionStatus.ACTIVE) conflict("Review session has ended")
        val slot = dao.sessionItems(session.id).firstOrNull { it.status == ReviewSessionItemStatus.PENDING }
        if (slot == null || slot.ticket != ticket) conflict("Review card or position has changed")
        return session to slot
    }

    private suspend fun requireSession(userId: Long, sessionId: Long): ReviewSessionEntity =
        dao.session(userId, sessionId) ?: conflict("Review session is unavailable")

    private suspend fun requireItem(userId: Long, itemId: Long, expectedRevision: Long): ReviewItemEntity {
        val item = dao.item(userId, itemId) ?: conflict("Review card is unavailable")
        if (item.itemRevision != expectedRevision) conflict("Review card revision has changed")
        return item
    }

    private suspend fun requireUsable(
        identity: SessionIdentity,
        slot: ReviewSessionItemEntity,
        allowFrozenSource: Boolean,
    ): ReviewItemEntity {
        val itemId = slot.itemId ?: conflict("Review card is unavailable")
        val card = requireItem(checkNotNull(identity.userId), itemId, slot.snapshotRevision)
        val availability = effectiveAvailability(identity, card)
        if (card.paused || !(availability == ReviewAvailability.AVAILABLE ||
                (availability == ReviewAvailability.SOURCE_CHANGED && allowFrozenSource))
        ) conflict("Review source is unavailable or has changed")
        return card
    }

    private suspend fun verifiedDueItems(
        identity: SessionIdentity,
        userId: Long,
        bookKey: String?,
        now: Long,
    ): List<ReviewItemEntity> {
        if (sourceStatus == null && legacySources == null) return dao.dueItems(userId, bookKey, now, QUEUE_LIMIT)
        val pending = pendingCards(userId)
        var lastHiddenId = 0L
        do {
            val hiddenItems = dao.hiddenDueItems(userId, bookKey, now, SOURCE_CHECK_LIMIT, lastHiddenId)
            for (hidden in hiddenItems) {
                recordAvailability(hidden, effectiveAvailability(identity, hidden), now)
                lastHiddenId = hidden.id
            }
        } while (hiddenItems.size == SOURCE_CHECK_LIMIT)

        val queue = ArrayList<ReviewItemEntity>(QUEUE_LIMIT)
        var verifiedCount = 0
        do {
            val page = dao.dueItems(userId, bookKey, now, SOURCE_CHECK_LIMIT, verifiedCount)
            for (raw in page) {
                val sourceAvailability = effectiveAvailability(identity, raw)
                val card = if (sourceAvailability == ReviewAvailability.AVAILABLE) {
                    repairLegacyCard(identity, raw, now, pending[raw.id], sourceAlreadyVerified = true)
                } else raw
                val availability = if (card === raw) sourceAvailability else card.availability
                if (availability == ReviewAvailability.AVAILABLE) {
                    verifiedCount++
                    if (queue.size < QUEUE_LIMIT) queue.add(card)
                } else {
                    recordAvailability(card, availability, now)
                }
            }
        } while (page.size == SOURCE_CHECK_LIMIT)
        return queue
    }

    private suspend fun pendingCards(userId: Long): Map<Long, ReviewSessionItemEntity> {
        if (legacySources == null) return emptyMap()
        val active = dao.activeSession(userId) ?: return emptyMap()
        return dao.sessionItems(active.id).mapNotNull { slot ->
            slot.itemId?.takeIf { slot.status == ReviewSessionItemStatus.PENDING }?.let { it to slot }
        }.toMap()
    }

    private suspend fun repairLegacyCard(
        identity: SessionIdentity,
        card: ReviewItemEntity,
        now: Long,
        pending: ReviewSessionItemEntity?,
        retryIncomplete: Boolean = false,
        sourceAlreadyVerified: Boolean = false,
    ): ReviewItemEntity {
        val sources = legacySources ?: return card
        val eligible = card.availability == ReviewAvailability.AVAILABLE ||
            (retryIncomplete && card.availability == ReviewAvailability.CONTENT_INCOMPLETE)
        if (!eligible || !card.hasLegacyGeneratedPrompt() ||
            pending?.let { !it.draftAnswer.isNullOrBlank() || it.snapshotRevision != card.itemRevision } == true ||
            (!sourceAlreadyVerified && sources.statusWithinIdentity(identity, card) != ReviewAvailability.AVAILABLE)
        ) return card
        val improved = sources.repairLegacyWithinIdentity(identity, card)
        val updated = if (improved == null) {
            if (card.availability == ReviewAvailability.CONTENT_INCOMPLETE) return card
            card.copy(availability = ReviewAvailability.CONTENT_INCOMPLETE)
        } else {
            card.copy(
                prompt = improved.prompt,
                answerSnapshot = improved.answer,
                snapshotHash = hashSnapshot(improved.prompt, improved.answer),
                itemRevision = Math.addExact(card.itemRevision, 1L),
                availability = ReviewAvailability.AVAILABLE,
                updatedAtUtc = maxOf(card.updatedAtUtc, now),
            )
        }
        check(dao.updateItem(updated) == 1)
        if (pending != null && updated.itemRevision != pending.snapshotRevision) {
            check(dao.updateSessionItem(pending.withUpdatedQuestion(updated)) == 1)
        }
        return updated
    }

    private fun ReviewSessionItemEntity.withUpdatedQuestion(card: ReviewItemEntity): ReviewSessionItemEntity =
        copy(snapshotRevision = card.itemRevision, prompt = card.prompt, answerSnapshot = card.answerSnapshot)

    private suspend fun recordAvailability(card: ReviewItemEntity, availability: String, now: Long) {
        if (card.availability == availability || card.availability == ReviewAvailability.SOURCE_CHANGED) return
        check(dao.updateAvailability(card.userId, card.id, card.itemRevision, availability, now) == 1)
    }

    private suspend fun effectiveAvailability(identity: SessionIdentity, card: ReviewItemEntity): String {
        val actual = sourceStatus?.invoke(identity, card) ?: return card.availability
        check(actual in AVAILABILITY_VALUES) { "Unknown review source availability" }
        return if (actual == ReviewAvailability.AVAILABLE &&
            card.availability in setOf(ReviewAvailability.SOURCE_CHANGED, ReviewAvailability.CONTENT_INCOMPLETE)
        ) {
            card.availability
        } else {
            actual
        }
    }

    private suspend fun loadSessionState(identity: SessionIdentity, session: ReviewSessionEntity): ReviewSessionState {
        val storedItems = dao.sessionItems(session.id)
        var current = storedItems.firstOrNull { it.status == ReviewSessionItemStatus.PENDING }
        var card = current?.itemId?.let { dao.item(session.userId, it) }
        if (current != null && card != null) {
            val repaired = repairLegacyCard(identity, card, clock(), current)
            if (repaired.itemRevision != card.itemRevision) current = current.withUpdatedQuestion(repaired)
            card = repaired
        }
        val availability = when {
            current == null -> null
            card == null -> ReviewAvailability.SOURCE_MISSING
            card.paused -> ReviewAvailability.SOURCE_HIDDEN
            else -> {
                val effective = effectiveAvailability(identity, card)
                recordAvailability(card, effective, clock())
                if (effective == ReviewAvailability.AVAILABLE && card.itemRevision != current.snapshotRevision) {
                    ReviewAvailability.SOURCE_CHANGED
                } else effective
            }
        }
        val items = storedItems.map { original ->
            val slot = current?.takeIf { it.ordinal == original.ordinal } ?: original
            if (slot.ordinal == current?.ordinal && availability != ReviewAvailability.AVAILABLE &&
                availability != ReviewAvailability.SOURCE_CHANGED
            ) {
                slot.copy(sourceRefJson = null, sourceTitle = null, prompt = null, answerSnapshot = null, draftAnswer = null)
            } else {
                slot.copy(answerSnapshot = slot.answerSnapshot.takeIf { slot.revealed })
            }
        }
        return ReviewSessionState(session, items, dao.sessionEvents(session.userId, session.id), availability)
    }

    private suspend fun saveProgress(session: ReviewSessionEntity, now: Long): ReviewSessionEntity {
        if (dao.remainingItems(session.id) == 0) return closeSession(session, now)
        check(dao.updateSession(session) == 1)
        return session
    }

    private suspend fun closeSession(session: ReviewSessionEntity, now: Long): ReviewSessionEntity {
        val closed = session.copy(
            activeUserId = null,
            status = ReviewSessionStatus.FINISHED,
            finishedAtUtc = maxOf(session.startedAtUtc, now),
        )
        check(dao.updateSession(closed) == 1)
        return closed
    }

    private suspend fun <T> authorized(identity: SessionIdentity, block: suspend (Long) -> T): T {
        val userId = identity.userId ?: throw ReviewIdentityExpiredException()
        val result = sessionManager.withCurrentIdentity(identity) {
            ReviewTransactionResult(database.withTransaction { block(userId) })
        } ?: throw ReviewIdentityExpiredException()
        return result.value
    }

    private data class ReviewTransactionResult<T>(val value: T)

    private companion object {
        const val QUEUE_LIMIT = 20
        const val PREVIEW_LIMIT = 3
        const val SOURCE_CHECK_LIMIT = 40
        const val MAX_PROMPT_CHARS = 500
        const val MAX_ANSWER_CHARS = 8_000
        const val MAX_SOURCE_REF_BYTES = 65_536
        const val MAX_TITLE_CHARS = 500
        val AVAILABILITY_VALUES = setOf(
            ReviewAvailability.AVAILABLE, ReviewAvailability.SOURCE_CHANGED, ReviewAvailability.SOURCE_MISSING,
            ReviewAvailability.SOURCE_HIDDEN, ReviewAvailability.SOURCE_REVOKED, ReviewAvailability.CONTENT_INCOMPLETE,
        )

        fun ReviewScope.bookKey(): String? = (this as? ReviewScope.Book)?.bookKey

        fun requireOperationId(value: String) {
            require(value.isNotBlank() && value.length <= 256)
        }

        fun newPortableId(): String = UUID.randomUUID().toString()

        fun hashSnapshot(prompt: String, answer: String): String =
            sha256Hex(JsonArray(listOf(JsonPrimitive(prompt), JsonPrimitive(answer))).toString())

        fun conflict(message: String): Nothing = throw ReviewConflictException(message)

        fun ReviewCandidate.isValid(): Boolean {
            if (sourceNamespace.isBlank() || sourceNamespace.length > 64 || sourceKey.isBlank() || sourceKey.length > 1_024 ||
                sourceRevision.isBlank() || sourceRevision.length > 1_024 || sourceTitle.length > MAX_TITLE_CHARS ||
                prompt.isBlank() || prompt.length > MAX_PROMPT_CHARS ||
                answerSnapshot.isBlank() || answerSnapshot.length > MAX_ANSWER_CHARS ||
                priority !in 0..2 || sourceTags.size > 16 || sourceTags.any { it.isBlank() || it.length > 64 } ||
                bookKey?.let { it.isBlank() || it.length > 256 } == true ||
                creationOperationId?.let { it.isBlank() || it.length > 256 } == true ||
                excerptFingerprint?.let { it.isBlank() || it.length > 256 } == true ||
                sourceRefJson.length > MAX_SOURCE_REF_BYTES || sourceRefJson.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_REF_BYTES
            ) return false
            return try {
                Json.parseToJsonElement(sourceRefJson) is JsonObject
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun ReviewCandidate.toEntity(userId: Long, now: Long): ReviewItemEntity = ReviewItemEntity(
            portableItemId = newPortableId(),
            userId = userId,
            sourceNamespace = sourceNamespace,
            sourceKey = sourceKey,
            sourceRefJson = sourceRefJson,
            sourceTags = JsonArray(sourceTags.sorted().map { JsonPrimitive(it) }).toString(),
            sourceTitle = sourceTitle,
            bookKey = bookKey,
            priority = priority,
            prompt = prompt,
            answerSnapshot = answerSnapshot,
            snapshotHash = hashSnapshot(prompt, answerSnapshot),
            sourceRevision = sourceRevision,
            itemRevision = 1,
            creationOperationId = creationOperationId,
            excerptFingerprint = excerptFingerprint,
            availability = ReviewAvailability.AVAILABLE,
            paused = false,
            stage = ReviewSchedule.INITIAL_STAGE,
            dueAtUtc = now,
            lastReviewedAtUtc = null,
            policyVersion = ReviewSchedule.FIXED_INTERVAL_V1,
            createdAtUtc = now,
            updatedAtUtc = now,
        )

        fun ReviewItemEntity.freeze(sessionId: Long, ordinal: Int): ReviewSessionItemEntity = ReviewSessionItemEntity(
            sessionId = sessionId,
            ordinal = ordinal,
            itemId = id,
            cardIdentity = portableItemId,
            snapshotRevision = itemRevision,
            sourceRevision = sourceRevision,
            sourceRefJson = sourceRefJson,
            sourceTitle = sourceTitle,
            prompt = prompt,
            answerSnapshot = answerSnapshot,
            status = ReviewSessionItemStatus.PENDING,
        )
    }
}
