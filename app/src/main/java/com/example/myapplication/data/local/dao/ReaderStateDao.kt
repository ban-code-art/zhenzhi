package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.ReaderStateEntity

@Dao
interface ReaderStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(state: ReaderStateEntity): Long

    @Query("SELECT * FROM reader_states WHERE user_id = :userId AND document_id = :documentId LIMIT 1")
    suspend fun find(userId: Long, documentId: String): ReaderStateEntity?

    @Query(
        "UPDATE reader_states " +
            "SET writer_epoch = writer_epoch + 1, last_sequence = 0, revision = revision + 1 " +
            "WHERE user_id = :userId AND document_id = :documentId",
    )
    suspend fun claimWriter(userId: Long, documentId: String): Int

    @Query("UPDATE reader_states SET locator_json = NULL, writer_epoch = writer_epoch + 1, " +
        "last_sequence = 0, revision = revision + 1 WHERE document_id = :documentId")
    suspend fun invalidateForContentChange(documentId: String): Int

    @Query(
        "UPDATE reader_states " +
            "SET locator_json = :locatorJson, last_read_at = :now, " +
            "last_sequence = :sequence, revision = revision + 1 " +
            "WHERE user_id = :userId AND document_id = :documentId " +
            "AND writer_epoch = :writerEpoch AND last_sequence < :sequence",
    )
    suspend fun saveProgress(
        userId: Long,
        documentId: String,
        writerEpoch: Long,
        sequence: Long,
        locatorJson: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE reader_states SET is_completed = :completed, revision = revision + 1 " +
            "WHERE user_id = :userId AND document_id = :documentId",
    )
    suspend fun setCompleted(userId: Long, documentId: String, completed: Boolean): Int

    @Query(
        "UPDATE reader_states SET is_hidden = :hidden, revision = revision + 1 " +
            "WHERE user_id = :userId AND document_id = :documentId",
    )
    suspend fun setHidden(userId: Long, documentId: String, hidden: Boolean): Int

    @Query(
        "UPDATE reader_states SET locator_json = REPLACE(locator_json, :oldPrefix, :newPrefix), " +
            "revision = revision + 1 " +
            "WHERE document_id = :documentId AND instr(locator_json, :oldPrefix) > 0",
    )
    suspend fun remapSectionPrefix(documentId: String, oldPrefix: String, newPrefix: String): Int

    @Query(
        "UPDATE reader_states SET settings_json = :settingsJson, revision = revision + 1 " +
            "WHERE user_id = :userId AND document_id = :documentId",
    )
    suspend fun saveSettings(userId: Long, documentId: String, settingsJson: String): Int

    @Query(
        "UPDATE reader_states SET display_title_override = :title, revision = revision + 1 " +
            "WHERE user_id = :userId AND document_id = :documentId",
    )
    suspend fun setDisplayTitleOverride(userId: Long, documentId: String, title: String?): Int
}
