package com.example.myapplication.data.review

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val IN_SCOPE = "userId = :userId AND (:bookKey IS NULL OR bookKey = :bookKey)"
private const val ELIGIBLE = "$IN_SCOPE AND availability = 'AVAILABLE' AND paused = 0"
private const val DUE = "$ELIGIBLE AND dueAtUtc <= :now"

@Dao
interface ReviewDao {
    @Query("SELECT COUNT(*) FROM review_items WHERE $DUE")
    suspend fun countDueItems(userId: Long, bookKey: String?, now: Long): Int

    @Query("SELECT MIN(dueAtUtc) FROM review_items WHERE $ELIGIBLE AND dueAtUtc > :now")
    suspend fun nextDueAt(userId: Long, bookKey: String?, now: Long): Long?

    @Query(
        "SELECT * FROM review_items WHERE $DUE " +
            "ORDER BY dueAtUtc, priority, sourceKey, sourceNamespace LIMIT :limit OFFSET :offset"
    )
    suspend fun dueItems(userId: Long, bookKey: String?, now: Long, limit: Int, offset: Int = 0): List<ReviewItemEntity>

    @Query(
        "SELECT * FROM review_items WHERE $IN_SCOPE AND paused = 0 AND dueAtUtc <= :now " +
            "AND availability IN ('SOURCE_HIDDEN', 'SOURCE_REVOKED') AND id > :afterId " +
            "ORDER BY id LIMIT :limit"
    )
    suspend fun hiddenDueItems(userId: Long, bookKey: String?, now: Long, limit: Int, afterId: Long = 0): List<ReviewItemEntity>

    @Query(
        "UPDATE review_items SET availability = :availability, updatedAtUtc = MAX(updatedAtUtc, :now) " +
            "WHERE userId = :userId AND id = :itemId AND itemRevision = :revision AND availability != :availability"
    )
    suspend fun updateAvailability(userId: Long, itemId: Long, revision: Long, availability: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM review_items WHERE $IN_SCOPE")
    suspend fun countItems(userId: Long, bookKey: String?): Int

    @Query("SELECT * FROM review_items WHERE $IN_SCOPE ORDER BY sourceNamespace, sourceKey")
    suspend fun items(userId: Long, bookKey: String? = null): List<ReviewItemEntity>

    @Query("SELECT * FROM review_items WHERE userId = :userId AND id = :itemId")
    suspend fun item(userId: Long, itemId: Long): ReviewItemEntity?

    @Query("SELECT * FROM review_items WHERE userId = :userId AND portableItemId = :portableId")
    suspend fun itemByPortableId(userId: Long, portableId: String): ReviewItemEntity?

    @Query("SELECT * FROM review_items WHERE userId = :userId AND sourceNamespace = :namespace AND sourceKey = :sourceKey")
    suspend fun itemBySource(userId: Long, namespace: String, sourceKey: String): ReviewItemEntity?

    @Query("SELECT sourceRefJson FROM review_items WHERE userId = :userId AND sourceNamespace = :namespace")
    fun observeSourceRefsByNamespace(userId: Long, namespace: String): Flow<List<String>>

    @Query("SELECT * FROM review_items WHERE userId = :userId AND creationOperationId = :operationId")
    suspend fun itemByCreationOperation(userId: Long, operationId: String): ReviewItemEntity?

    @Query(
        "SELECT * FROM review_items WHERE userId = :userId AND sourceNamespace = :namespace " +
            "AND excerptFingerprint = :fingerprint ORDER BY id LIMIT 1"
    )
    suspend fun itemByExcerpt(userId: Long, namespace: String, fingerprint: String): ReviewItemEntity?

    @Insert
    suspend fun insertItem(item: ReviewItemEntity): Long

    @Update
    suspend fun updateItem(item: ReviewItemEntity): Int

    @Query("DELETE FROM review_items WHERE userId = :userId AND id = :itemId")
    suspend fun deleteItem(userId: Long, itemId: Long): Int

    @Query("SELECT * FROM review_sessions WHERE userId = :userId AND id = :sessionId")
    suspend fun session(userId: Long, sessionId: Long): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions WHERE userId = :userId AND activeUserId = :userId")
    suspend fun activeSession(userId: Long): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions WHERE userId = :userId AND startOperationId = :operationId")
    suspend fun sessionByStartOperation(userId: Long, operationId: String): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions WHERE userId = :userId AND portableSessionId = :portableId")
    suspend fun sessionByPortableId(userId: Long, portableId: String): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions WHERE userId = :userId ORDER BY startedAtUtc, id")
    suspend fun sessions(userId: Long): List<ReviewSessionEntity>

    @Insert
    suspend fun insertSession(session: ReviewSessionEntity): Long

    @Update
    suspend fun updateSession(session: ReviewSessionEntity): Int

    @Query("SELECT * FROM review_session_items WHERE sessionId = :sessionId ORDER BY ordinal")
    suspend fun sessionItems(sessionId: Long): List<ReviewSessionItemEntity>

    @Query("SELECT COUNT(*) FROM review_session_items WHERE sessionId = :sessionId AND status = 'PENDING'")
    suspend fun remainingItems(sessionId: Long): Int

    @Insert
    suspend fun insertSessionItems(items: List<ReviewSessionItemEntity>)

    @Update
    suspend fun updateSessionItem(item: ReviewSessionItemEntity): Int

    @Query(
        "UPDATE review_session_items SET status = 'ABANDONED', draftAnswer = NULL " +
            "WHERE sessionId = :sessionId AND status = 'PENDING'"
    )
    suspend fun abandonPendingItems(sessionId: Long): Int

    @Query("SELECT * FROM review_events WHERE userId = :userId AND operationId = :operationId")
    suspend fun eventByOperation(userId: Long, operationId: String): ReviewEventEntity?

    @Query("SELECT * FROM review_events WHERE userId = :userId AND portableEventId = :portableId")
    suspend fun eventByPortableId(userId: Long, portableId: String): ReviewEventEntity?

    @Query("SELECT * FROM review_events WHERE userId = :userId AND sessionId = :sessionId ORDER BY ordinal, id")
    suspend fun sessionEvents(userId: Long, sessionId: Long): List<ReviewEventEntity>

    @Query("SELECT * FROM review_events WHERE userId = :userId ORDER BY occurredAtUtc, id")
    suspend fun events(userId: Long): List<ReviewEventEntity>

    @Query("SELECT COUNT(DISTINCT cardIdentity) FROM review_events WHERE userId = :userId AND localDate = :localDate")
    suspend fun reviewedCardCount(userId: Long, localDate: String): Int

    @Query("SELECT DISTINCT localDate FROM review_events WHERE userId = :userId ORDER BY localDate")
    suspend fun reviewedDates(userId: Long): List<String>

    @Query("SELECT DISTINCT localDate FROM review_events WHERE userId = :userId ORDER BY localDate")
    fun observeReviewedDates(userId: Long): Flow<List<String>>

    @Insert
    suspend fun insertEvent(event: ReviewEventEntity): Long
}
