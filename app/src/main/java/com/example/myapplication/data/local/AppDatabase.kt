package com.example.myapplication.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.myapplication.data.encyclopedia.EncyclopediaMigrations
import com.example.myapplication.data.local.dao.AiProfileDao
import com.example.myapplication.data.local.dao.AiRunDao
import com.example.myapplication.data.local.dao.ChapterDao
import com.example.myapplication.data.local.dao.EncyclopediaFavoriteDao
import com.example.myapplication.data.local.dao.KnowledgeAnnManifestDao
import com.example.myapplication.data.local.dao.KnowledgeChunkDao
import com.example.myapplication.data.local.dao.KnowledgeDocumentDao
import com.example.myapplication.data.local.dao.KnowledgeEmbeddingDao
import com.example.myapplication.data.local.dao.KnowledgeJobDao
import com.example.myapplication.data.local.dao.KnowledgeLexicalDao
import com.example.myapplication.data.local.dao.KnowledgeVectorStateDao
import com.example.myapplication.data.local.dao.LearningActivityDao
import com.example.myapplication.data.local.dao.FavoriteDao
import com.example.myapplication.data.local.dao.QuestionDao
import com.example.myapplication.data.local.dao.QuizAttemptDao
import com.example.myapplication.data.local.dao.QuizPaperDao
import com.example.myapplication.data.local.dao.ReaderAnnotationDao
import com.example.myapplication.data.local.dao.ReaderCatalogDao
import com.example.myapplication.data.local.dao.ReaderNotebookDao
import com.example.myapplication.data.local.dao.ReaderJobDao
import com.example.myapplication.data.local.dao.ReaderStateDao
import com.example.myapplication.data.local.dao.StudyProgressDao
import com.example.myapplication.data.local.dao.TestAnswerDao
import com.example.myapplication.data.local.dao.TestRecordDao
import com.example.myapplication.data.local.dao.UserDao
import com.example.myapplication.data.local.dao.CourseBookDao
import com.example.myapplication.data.local.dao.AnnotationTargetDao
import com.example.myapplication.data.local.entity.CourseBookEntity
import com.example.myapplication.data.local.entity.CourseBookChapterEntity
import com.example.myapplication.data.local.entity.AnnotationTargetEntity
import com.example.myapplication.data.local.migration.EnhancementMigrations
import com.example.myapplication.data.local.entity.AiConsentEntity
import com.example.myapplication.data.local.entity.AiConversationEntity
import com.example.myapplication.data.local.entity.AiMessageEntity
import com.example.myapplication.data.local.entity.AiNoteProposalEntity
import com.example.myapplication.data.local.entity.AiProfileEntity
import com.example.myapplication.data.local.entity.AiRunEntity
import com.example.myapplication.data.local.entity.AiRunEventEntity
import com.example.myapplication.data.local.entity.AiToolExecutionEntity
import com.example.myapplication.data.local.entity.AiUserSettingsEntity
import com.example.myapplication.data.local.entity.ChapterEntity
import com.example.myapplication.data.local.entity.KnowledgeAnnManifestEntity
import com.example.myapplication.data.local.entity.KnowledgeChunkEntity
import com.example.myapplication.data.local.entity.KnowledgeChunkFtsEntity
import com.example.myapplication.data.local.entity.KnowledgeDocumentEntity
import com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity
import com.example.myapplication.data.local.entity.KnowledgeIndexJobEntity
import com.example.myapplication.data.local.entity.KnowledgeVectorStateEntity
import com.example.myapplication.data.local.entity.ContentPackageEntity
import com.example.myapplication.data.local.entity.ContentQuestionBindingEntity
import com.example.myapplication.data.local.entity.EncyclopediaFavoriteEntity
import com.example.myapplication.data.local.entity.FavoriteEntity
import com.example.myapplication.data.local.entity.LearningActivityDayEntity
import com.example.myapplication.data.local.entity.LearningActivityEventEntity
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.local.entity.QuizAttemptAnswerEntity
import com.example.myapplication.data.local.entity.QuizAttemptEntity
import com.example.myapplication.data.local.entity.QuizGradeEventEntity
import com.example.myapplication.data.local.entity.QuizItemEntity
import com.example.myapplication.data.local.entity.QuizPaperEntity
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.local.entity.ReaderBookmarkEntity
import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderDraftEntity
import com.example.myapplication.data.local.entity.ReaderJobEntity
import com.example.myapplication.data.local.entity.ReaderMutationReceiptEntity
import com.example.myapplication.data.local.entity.ReaderNotebookEntity
import com.example.myapplication.data.local.entity.ReaderNoteVersionEntity
import com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.local.entity.ReaderSourceReferenceEntity
import com.example.myapplication.data.local.entity.ReaderStateEntity
import com.example.myapplication.data.local.entity.StudyProgressEntity
import com.example.myapplication.data.local.entity.TestAnswerEntity
import com.example.myapplication.data.local.entity.TestRecordEntity
import com.example.myapplication.data.local.entity.UserEntity
import com.example.myapplication.data.local.migration.AiMigrations
import com.example.myapplication.data.local.migration.QuizMigrations
import com.example.myapplication.data.local.migration.ReaderMigrations
import com.example.myapplication.data.review.ReviewDao
import com.example.myapplication.data.review.ReviewEventEntity
import com.example.myapplication.data.review.ReviewItemEntity
import com.example.myapplication.data.review.ReviewMaterialDao
import com.example.myapplication.data.review.ReviewMigrations
import com.example.myapplication.data.review.ReviewSessionEntity
import com.example.myapplication.data.review.ReviewSessionItemEntity

@Database(
    entities = [
        UserEntity::class,
        ChapterEntity::class,
        QuestionEntity::class,
        TestRecordEntity::class,
        TestAnswerEntity::class,
        FavoriteEntity::class,
        StudyProgressEntity::class,
        ReaderDocumentEntity::class,
        ReaderContentRevisionEntity::class,
        ReaderSectionEntity::class,
        ReaderOutlineNodeEntity::class,
        ReaderStateEntity::class,
        ReaderAnnotationEntity::class,
        ReaderBookmarkEntity::class,
        ReaderSourceReferenceEntity::class,
        ReaderNotebookEntity::class,
        ReaderNoteVersionEntity::class,
        ReaderMutationReceiptEntity::class,
        ReaderDraftEntity::class,
        ReaderJobEntity::class,
        ContentPackageEntity::class,
        ContentQuestionBindingEntity::class,
        AiProfileEntity::class,
        AiUserSettingsEntity::class,
        AiConsentEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        AiRunEntity::class,
        AiRunEventEntity::class,
        AiToolExecutionEntity::class,
        AiNoteProposalEntity::class,
        KnowledgeDocumentEntity::class,
        KnowledgeChunkEntity::class,
        KnowledgeChunkFtsEntity::class,
        KnowledgeEmbeddingEntity::class,
        KnowledgeVectorStateEntity::class,
        KnowledgeIndexJobEntity::class,
        KnowledgeAnnManifestEntity::class,
        QuizPaperEntity::class,
        QuizItemEntity::class,
        QuizAttemptEntity::class,
        QuizAttemptAnswerEntity::class,
        QuizGradeEventEntity::class,
        LearningActivityEventEntity::class,
        LearningActivityDayEntity::class,
        CourseBookEntity::class,
        CourseBookChapterEntity::class,
        AnnotationTargetEntity::class,
        ReviewItemEntity::class,
        ReviewSessionEntity::class,
        ReviewSessionItemEntity::class,
        ReviewEventEntity::class,
        EncyclopediaFavoriteEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun chapterDao(): ChapterDao
    abstract fun questionDao(): QuestionDao
    abstract fun testRecordDao(): TestRecordDao
    abstract fun testAnswerDao(): TestAnswerDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun studyProgressDao(): StudyProgressDao
    abstract fun readerCatalogDao(): ReaderCatalogDao
    abstract fun readerStateDao(): ReaderStateDao
    abstract fun readerJobDao(): ReaderJobDao
    abstract fun readerAnnotationDao(): ReaderAnnotationDao
    abstract fun readerNotebookDao(): ReaderNotebookDao
    abstract fun aiProfileDao(): AiProfileDao
    abstract fun aiRunDao(): AiRunDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao
    abstract fun knowledgeChunkDao(): KnowledgeChunkDao
    abstract fun knowledgeEmbeddingDao(): KnowledgeEmbeddingDao
    abstract fun knowledgeVectorStateDao(): KnowledgeVectorStateDao
    abstract fun knowledgeJobDao(): KnowledgeJobDao
    abstract fun knowledgeLexicalDao(): KnowledgeLexicalDao
    abstract fun knowledgeAnnManifestDao(): KnowledgeAnnManifestDao
    abstract fun quizPaperDao(): QuizPaperDao
    abstract fun quizAttemptDao(): QuizAttemptDao
    abstract fun learningActivityDao(): LearningActivityDao
    abstract fun courseBookDao(): CourseBookDao
    abstract fun annotationTargetDao(): AnnotationTargetDao
    abstract fun reviewDao(): ReviewDao
    abstract fun reviewMaterialDao(): ReviewMaterialDao
    abstract fun encyclopediaFavoriteDao(): EncyclopediaFavoriteDao

    companion object {
        private const val DB_NAME = "acupuncture.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE users ADD COLUMN avatar_id INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN avatar_path TEXT")
            }
        }

        /** One immutable registration list shared by production and file-backed test builders. */
        val ALL_MIGRATIONS: List<Migration> = listOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            ReaderMigrations.MIGRATION_3_4,
            AiMigrations.MIGRATION_4_5,
            QuizMigrations.MIGRATION_5_6,
            EnhancementMigrations.MIGRATION_6_7,
            ReviewMigrations.MIGRATION_7_8,
            EncyclopediaMigrations.MIGRATION_8_9,
        )

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS.toTypedArray())
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL("PRAGMA foreign_keys = ON")
                            ReaderMigrations.installQuestionGuards(db)
                            AiMigrations.installFtsTriggers(db)
                            ReviewMigrations.installPrivacyGuards(db)
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA foreign_keys = ON")
                            ReaderMigrations.installQuestionGuards(db)
                            AiMigrations.installFtsTriggers(db)
                            ReviewMigrations.installPrivacyGuards(db)
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
