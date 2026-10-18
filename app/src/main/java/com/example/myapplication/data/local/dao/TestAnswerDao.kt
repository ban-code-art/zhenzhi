package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.TestAnswerEntity

@Dao
interface TestAnswerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(answers: List<TestAnswerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(answer: TestAnswerEntity): Long

    @Query("SELECT * FROM test_answers WHERE test_id = :testId ORDER BY order_index ASC")
    suspend fun findByTest(testId: Long): List<TestAnswerEntity>
}
