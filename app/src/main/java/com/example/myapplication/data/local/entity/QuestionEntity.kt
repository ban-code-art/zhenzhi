package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["owner_user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["type"], name = "index_questions_type"),
        Index(value = ["chapter_id"], name = "index_questions_chapter_id"),
        Index(value = ["type", "enabled"], name = "index_questions_type_enabled"),
        Index(value = ["owner_user_id", "id"], name = "index_questions_owner_user_id_id"),
        Index(value = ["source_document_id"], name = "index_questions_source_document_id"),
    ],
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: Long? = null,
    @ColumnInfo(name = "stem")
    val stem: String,
    @ColumnInfo(name = "options")
    val options: String? = null,
    @ColumnInfo(name = "answer")
    val answer: String,
    @ColumnInfo(name = "synonyms")
    val synonyms: String? = null,
    @ColumnInfo(name = "knowledge_point")
    val knowledgePoint: String? = null,
    @ColumnInfo(name = "analysis")
    val analysis: String,
    @ColumnInfo(name = "score")
    val score: Int = 2,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: Long? = null,
    @ColumnInfo(name = "origin", defaultValue = "'BUILTIN'")
    val origin: String = "BUILTIN",
    @ColumnInfo(name = "source_document_id")
    val sourceDocumentId: String? = null,
    @ColumnInfo(name = "source_locator_json")
    val sourceLocatorJson: String? = null,
    @ColumnInfo(name = "source_excerpt")
    val sourceExcerpt: String? = null,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: String? = null,
)