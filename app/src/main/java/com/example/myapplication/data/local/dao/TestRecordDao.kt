package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.myapplication.data.local.entity.TestRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TestRecordDao {

    @Insert
    suspend fun insert(record: TestRecordEntity): Long

    @Update
    suspend fun update(record: TestRecordEntity)

    @Query("SELECT * FROM test_records WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TestRecordEntity?

    @Query(
        "SELECT * FROM test_records WHERE user_id = :userId AND status = 'SUBMITTED' " +
            "ORDER BY started_at DESC"
    )
    fun observeSubmittedByUser(userId: Long): Flow<List<TestRecordEntity>>

    @Query(
        "SELECT * FROM test_records WHERE user_id = :userId AND status = 'IN_PROGRESS' " +
            "ORDER BY started_at DESC LIMIT 1"
    )
    suspend fun findInProgress(userId: Long): TestRecordEntity?

    @Query(
        "SELECT DISTINCT strftime('%Y-%m-%d', started_at / 1000, 'unixepoch', 'localtime') " +
            "FROM test_records WHERE user_id = :userId AND status = 'SUBMITTED' ORDER BY 1 DESC"
    )
    suspend fun getActiveDays(userId: Long): List<String>

    @Query(
        "SELECT DISTINCT strftime('%Y-%m-%d', started_at / 1000, 'unixepoch', 'localtime') " +
            "FROM test_records WHERE user_id = :userId AND status = 'SUBMITTED' ORDER BY 1 DESC"
    )
    fun observeActiveDays(userId: Long): Flow<List<String>>
}
