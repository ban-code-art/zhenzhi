package com.example.myapplication.data.reader.notes

import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.jsonObject

enum class AnnotationKind {
    HIGHLIGHT,
    NOTE,
    INK,
}

data class AnnotationTargetInput(val locator: ReaderLocator, val precision: String)

enum class AnnotationWriteCheckpoint { BEFORE_WRITE, BEFORE_COMMIT }

/** Caller proof hook; this does not establish a shared DataStore/Room session lock. */
data class AnnotationWriteGuard(
    val expectedDocumentId: String,
    val expectedContentRevision: String,
    val expectedAccessEpoch: Long,
    val check: suspend (AccessContext, AnnotationWriteCheckpoint, List<AnnotationTargetInput>) -> OperationResult<Unit>,
)

enum class AnnotationColor(val hex: String, val label: String) {
    YELLOW("#FFE58A", "黄色"),
    GREEN("#A8E6B0", "绿色"),
    BLUE("#A9D8F5", "蓝色"),
    PINK("#F6B6CF", "粉色"),
    ORANGE("#FFC58A", "橙色"),
}

data class AnnotationStyle(
    val color: AnnotationColor = AnnotationColor.YELLOW,
    val underline: Boolean = false,
) {
    fun encode(): String = "{\"color\":\"${color.name}\",\"underline\":$underline}"

    companion object {
        fun decode(value: String?): AnnotationStyle {
            if (value.isNullOrBlank()) return AnnotationStyle()
            val color = Regex("\\\"color\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"")
                .find(value)?.groupValues?.getOrNull(1)
                ?.let { runCatching { AnnotationColor.valueOf(it) }.getOrNull() }
                ?: AnnotationColor.YELLOW
            val underline = Regex("\\\"underline\\\"\\s*:\\s*(true|false)")
                .find(value)?.groupValues?.getOrNull(1) == "true"
            return AnnotationStyle(color, underline)
        }
    }
}

data class ReaderSelectionSnapshot(
    val sectionId: String,
    val blockId: String?,
    val canonicalStart: Int,
    val canonicalEnd: Int,
    val quote: String,
    val prefix: String?,
    val suffix: String?,
) {
    init {
        require(sectionId.isNotBlank()) { "sectionId must not be blank" }
        require(canonicalStart >= 0) { "canonicalStart must be >= 0" }
        require(canonicalEnd >= canonicalStart) { "canonicalEnd must be >= canonicalStart" }
        require(quote.isNotBlank()) { "quote must not be blank" }
        require(quote.length <= 8192) { "quote is too long" }
    }
}

data class ReaderAnnotationView(
    val id: String,
    val documentId: String,
    val sectionId: String?,
    val kind: AnnotationKind,
    val locator: ReaderLocator?,
    val quote: String?,
    val noteText: String?,
    val style: AnnotationStyle,
    val inkBlobPath: String?,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val targets: List<AnnotationTargetInput> = emptyList(),
)

data class ReaderBookmarkView(
    val id: String,
    val documentId: String,
    val locator: ReaderLocator?,
    val anchorHash: String,
    val preview: String,
    val createdAt: Long,
)

data class ReaderNoteSnapshot(
    val notebookId: String,
    val documentId: String,
    val body: String,
    val revision: Long,
    val updatedAt: Long,
)

data class ReaderNoteDraftSnapshot(
    val notebookId: String,
    val editorId: String,
    val editorSequence: Long,
    val baseRevision: Long,
    val body: String,
    val updatedAt: Long,
)

data class ReaderNoteLoad(
    val documentId: String,
    val notebook: ReaderNoteSnapshot?,
    val draft: ReaderNoteDraftSnapshot?,
    val lastActor: String?,
)

data class ReaderNoteSaveAck(
    val notebookId: String,
    val revision: Long,
    val bodyHash: String,
    val savedSequence: Long,
)

data class ReaderNoteSaveReceipt(
    val operationId: String,
    val notebookId: String,
    val revision: Long,
    val bodyHash: String,
    val savedSequence: Long,
)

sealed interface NoteSaveOutcome {
    data class Saved(val ack: ReaderNoteSaveAck) : NoteSaveOutcome
    data class Conflict(
        val currentRevision: Long,
        val currentBody: String,
        val draftBody: String? = null,
    ) : NoteSaveOutcome
    data class Rejected(val code: String) : NoteSaveOutcome
    data class Failed(val code: String, val retryable: Boolean) : NoteSaveOutcome
}

object ReaderMutationHasher {
    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun requestHash(
        targetKind: String,
        targetId: String,
        baseRevision: Long,
        payload: String,
    ): String = sha256(
        listOf(targetKind, targetId, baseRevision.toString(), sha256(payload)).joinToString("|"),
    )

    fun locatorAnchor(locator: ReaderLocator): String {
        val identity = when (locator) {
            is ReaderLocator.Reflow -> listOf(
                "REFLOW",
                locator.sectionId,
                locator.blockId,
                locator.canonicalStart.toString(),
                locator.canonicalEnd.toString(),
                locator.exactQuote,
            )

            is ReaderLocator.Fixed -> listOf(
                "FIXED",
                locator.sectionId,
                locator.pageIndex.toString(),
                locator.rotationDegrees.toString(),
                locator.rects.joinToString(";") { it.stableKey() },
                locator.textQuote.orEmpty(),
            )

            is ReaderLocator.Note -> listOf(
                "NOTE",
                locator.sectionId,
                locator.blockId.orEmpty(),
                locator.canonicalStart?.toString().orEmpty(),
                locator.canonicalEnd?.toString().orEmpty(),
                locator.notebookId,
            )
        }
        return sha256(identity.joinToString("|"))
    }

    fun bookmarkHash(locator: ReaderLocator): String = locatorAnchor(locator)

    private fun NormalizedRect.stableKey(): String =
        listOf(left, top, right, bottom).joinToString(",") { "%.6f".format(it) }
}

object ReaderSelectionCodec {
    fun decode(raw: String?): ReaderSelectionSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
            val quote = root.string("quote")
            val start = root.int("canonicalStart")
            val end = root.int("canonicalEnd")
            if (quote.isBlank() || quote.length > 8192 ||
                !com.example.myapplication.domain.reader.ReaderLocatorValidator.isWellFormedUtf16(quote) ||
                start < 0 || end.toLong() - start != quote.length.toLong()) return null
            ReaderSelectionSnapshot(
                sectionId = root.string("sectionId"),
                blockId = root.optionalString("blockId"),
                canonicalStart = start,
                canonicalEnd = end,
                quote = quote,
                prefix = root.optionalString("prefix")?.take(64),
                suffix = root.optionalString("suffix")?.take(64),
            )
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String {
        val primitive = this[name] as? kotlinx.serialization.json.JsonPrimitive
            ?: error("missing $name")
        return primitive.content
    }

    private fun kotlinx.serialization.json.JsonObject.optionalString(name: String): String? {
        val element = this[name] ?: return null
        if (element is kotlinx.serialization.json.JsonNull) return null
        return (element as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    private fun kotlinx.serialization.json.JsonObject.int(name: String): Int {
        val primitive = this[name] as? kotlinx.serialization.json.JsonPrimitive
            ?: error("missing $name")
        return primitive.content.toInt()
    }
}

object ReaderLocatorJson {
    fun decode(value: String?): ReaderLocator? = value?.let(ReaderLocatorCodec::decodeOrNull)
}

