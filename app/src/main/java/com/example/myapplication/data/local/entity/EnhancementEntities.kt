package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "course_books",
    indices = [Index(value = ["content_key"], unique = true, name = "idx_course_book_key")],
)
data class CourseBookEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "summary") val summary: String? = null,
    @ColumnInfo(name = "cover_asset") val coverAsset: String? = null,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "content_version") val contentVersion: Long,
    @ColumnInfo(name = "review_status") val reviewStatus: String,
)

@Entity(
    tableName = "course_book_chapters",
    primaryKeys = ["book_id", "chapter_key"],
    foreignKeys = [
        ForeignKey(entity = CourseBookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReaderDocumentEntity::class, parentColumns = ["id"], childColumns = ["document_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ReaderSectionEntity::class, parentColumns = ["id"], childColumns = ["section_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["legacy_chapter_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "order_index", "chapter_key"], name = "idx_course_chapter_order"),
        Index(value = ["document_id"], name = "idx_course_chapter_document"),
        Index(value = ["section_id"], name = "idx_course_chapter_section"),
        Index(value = ["legacy_chapter_id"], name = "idx_course_chapter_legacy"),
    ],
)
data class CourseBookChapterEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_key") val chapterKey: String,
    @ColumnInfo(name = "parent_key") val parentKey: String? = null,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "section_id") val sectionId: String? = null,
    @ColumnInfo(name = "legacy_chapter_id") val legacyChapterId: Long? = null,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
)

@Entity(
    tableName = "reader_annotation_targets",
    foreignKeys = [
        ForeignKey(entity = ReaderAnnotationEntity::class, parentColumns = ["id"], childColumns = ["annotation_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["annotation_id", "ordinal"], unique = true, name = "idx_annotation_target_order")],
)
data class AnnotationTargetEntity(
    @PrimaryKey @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "annotation_id") val annotationId: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "anchor_version", defaultValue = "1") val anchorVersion: Int = 1,
    @ColumnInfo(name = "precision") val precision: String,
) {
    init {
        require(targetId.isNotBlank() && annotationId.isNotBlank() && locatorJson.isNotBlank())
        require(ordinal >= 0) { "target ordinal must not be negative" }
        require(anchorVersion == 1) { "unsupported anchor version" }
        require(precision in setOf("EXACT", "PAGE_ONLY", "UNRESOLVED")) { "unsupported target precision" }
    }
}
