package com.example.myapplication.data.review

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ReviewMigrations {
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `review_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `portableItemId` TEXT NOT NULL,
                    `userId` INTEGER NOT NULL,
                    `sourceNamespace` TEXT NOT NULL,
                    `sourceKey` TEXT NOT NULL,
                    `sourceRefJson` TEXT NOT NULL,
                    `sourceTags` TEXT NOT NULL,
                    `sourceTitle` TEXT NOT NULL,
                    `bookKey` TEXT,
                    `priority` INTEGER NOT NULL,
                    `prompt` TEXT NOT NULL,
                    `answerSnapshot` TEXT NOT NULL,
                    `snapshotHash` TEXT NOT NULL,
                    `sourceRevision` TEXT NOT NULL,
                    `itemRevision` INTEGER NOT NULL,
                    `creationOperationId` TEXT,
                    `excerptFingerprint` TEXT,
                    `availability` TEXT NOT NULL,
                    `paused` INTEGER NOT NULL,
                    `stage` INTEGER NOT NULL,
                    `dueAtUtc` INTEGER NOT NULL,
                    `lastReviewedAtUtc` INTEGER,
                    `policyVersion` TEXT NOT NULL,
                    `createdAtUtc` INTEGER NOT NULL,
                    `updatedAtUtc` INTEGER NOT NULL,
                    FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_items_userId_sourceNamespace_sourceKey` " +
                    "ON `review_items` (`userId`, `sourceNamespace`, `sourceKey`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_items_userId_portableItemId` " +
                    "ON `review_items` (`userId`, `portableItemId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_items_userId_creationOperationId` " +
                    "ON `review_items` (`userId`, `creationOperationId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_review_items_userId_sourceNamespace_excerptFingerprint` " +
                    "ON `review_items` (`userId`, `sourceNamespace`, `excerptFingerprint`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_review_items_userId_availability_paused_dueAtUtc` " +
                    "ON `review_items` (`userId`, `availability`, `paused`, `dueAtUtc`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_review_items_userId_bookKey_availability_paused_dueAtUtc` " +
                    "ON `review_items` (`userId`, `bookKey`, `availability`, `paused`, `dueAtUtc`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `review_sessions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `portableSessionId` TEXT NOT NULL,
                    `userId` INTEGER NOT NULL,
                    `activeUserId` INTEGER,
                    `startOperationId` TEXT,
                    `bookKey` TEXT,
                    `status` TEXT NOT NULL,
                    `startedAtUtc` INTEGER NOT NULL,
                    `finishedAtUtc` INTEGER,
                    `completedCount` INTEGER NOT NULL,
                    `skippedCount` INTEGER NOT NULL,
                    `unavailableCount` INTEGER NOT NULL,
                    FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_sessions_userId_portableSessionId` " +
                    "ON `review_sessions` (`userId`, `portableSessionId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_sessions_userId_startOperationId` " +
                    "ON `review_sessions` (`userId`, `startOperationId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_sessions_activeUserId` " +
                    "ON `review_sessions` (`activeUserId`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `review_session_items` (
                    `sessionId` INTEGER NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `itemId` INTEGER,
                    `cardIdentity` TEXT NOT NULL,
                    `snapshotRevision` INTEGER NOT NULL,
                    `sourceRevision` TEXT,
                    `sourceRefJson` TEXT,
                    `sourceTitle` TEXT,
                    `prompt` TEXT,
                    `answerSnapshot` TEXT,
                    `status` TEXT NOT NULL,
                    `draftAnswer` TEXT,
                    `revealed` INTEGER NOT NULL,
                    `submittedEventId` INTEGER,
                    PRIMARY KEY(`sessionId`, `ordinal`),
                    FOREIGN KEY(`sessionId`) REFERENCES `review_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`itemId`) REFERENCES `review_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_session_items_sessionId_cardIdentity` " +
                    "ON `review_session_items` (`sessionId`, `cardIdentity`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_review_session_items_itemId` " +
                    "ON `review_session_items` (`itemId`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `review_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `portableEventId` TEXT NOT NULL,
                    `userId` INTEGER NOT NULL,
                    `sessionId` INTEGER,
                    `itemId` INTEGER,
                    `portableSessionId` TEXT NOT NULL,
                    `cardIdentity` TEXT NOT NULL,
                    `ordinal` INTEGER,
                    `operationId` TEXT NOT NULL,
                    `rating` TEXT NOT NULL,
                    `beforeStage` INTEGER NOT NULL,
                    `afterStage` INTEGER NOT NULL,
                    `beforeDueAtUtc` INTEGER NOT NULL,
                    `afterDueAtUtc` INTEGER NOT NULL,
                    `snapshotRevision` INTEGER NOT NULL,
                    `occurredAtUtc` INTEGER NOT NULL,
                    `localDate` TEXT NOT NULL,
                    `timezoneId` TEXT NOT NULL,
                    `activeMs` INTEGER NOT NULL,
                    FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sessionId`) REFERENCES `review_sessions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`itemId`) REFERENCES `review_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_events_userId_operationId` " +
                    "ON `review_events` (`userId`, `operationId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_events_userId_portableEventId` " +
                    "ON `review_events` (`userId`, `portableEventId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_review_events_userId_localDate_cardIdentity` " +
                    "ON `review_events` (`userId`, `localDate`, `cardIdentity`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_review_events_sessionId` " +
                    "ON `review_events` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_review_events_itemId` " +
                    "ON `review_events` (`itemId`)"
            )
            installPrivacyGuards(db)
        }
    }

    // A card can also be deleted by an account cascade or restore tool. Scrub before
    // the foreign key detaches the row so even closed queues lose their sensitive text.
    fun installPrivacyGuards(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS review_item_scrub_session_snapshots
            BEFORE DELETE ON review_items
            BEGIN
                UPDATE review_sessions
                SET unavailableCount = unavailableCount + 1
                WHERE id IN (
                    SELECT sessionId FROM review_session_items WHERE itemId = OLD.id AND status = 'PENDING'
                );

                UPDATE review_session_items
                SET sourceRevision = NULL, sourceRefJson = NULL, sourceTitle = NULL,
                    prompt = NULL, answerSnapshot = NULL, draftAnswer = NULL, revealed = 0,
                    status = CASE WHEN status = 'PENDING' THEN 'SOURCE_DELETED' ELSE status END
                WHERE itemId = OLD.id;

                UPDATE review_sessions
                SET activeUserId = NULL, status = 'FINISHED',
                    finishedAtUtc = MAX(startedAtUtc, CAST(strftime('%s', 'now') AS INTEGER) * 1000)
                WHERE activeUserId = OLD.userId AND id IN (
                    SELECT sessionId FROM review_session_items WHERE itemId = OLD.id
                ) AND NOT EXISTS (
                    SELECT 1 FROM review_session_items
                    WHERE sessionId = review_sessions.id AND status = 'PENDING'
                );
            END
            """.trimIndent()
        )
    }
}
