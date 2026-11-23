package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.myapplication.data.local.entity.CourseBookChapterEntity
import com.example.myapplication.data.local.entity.CourseBookEntity
import kotlinx.coroutines.flow.Flow

/** Metadata only; course content publication and parent graph validation belong to the seeder. */
@Dao
interface CourseBookDao {
    // ABORT preserves existing chapter mappings; REPLACE would cascade-delete them.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: CourseBookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapters(chapters: List<CourseBookChapterEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateBook(book: CourseBookEntity): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateChapter(chapter: CourseBookChapterEntity): Int

    @Query("SELECT * FROM course_books WHERE book_id = :bookId LIMIT 1")
    suspend fun findBook(bookId: String): CourseBookEntity?

    /** Seeder needs disabled rows too, to repair by stable key without duplicate inserts. */
    @Query("SELECT * FROM course_book_chapters WHERE book_id = :bookId ORDER BY order_index, chapter_key")
    suspend fun listAllChapters(bookId: String): List<CourseBookChapterEntity>

    @Query("SELECT * FROM course_books ORDER BY order_index, content_key")
    fun observeBooks(): Flow<List<CourseBookEntity>>

    @Query("SELECT * FROM course_book_chapters WHERE book_id = :bookId AND enabled = 1 ORDER BY order_index, chapter_key")
    suspend fun listChapters(bookId: String): List<CourseBookChapterEntity>

    @Query("SELECT * FROM course_book_chapters WHERE book_id = :bookId AND enabled = 1 ORDER BY order_index, chapter_key")
    fun observeChapters(bookId: String): Flow<List<CourseBookChapterEntity>>
}
