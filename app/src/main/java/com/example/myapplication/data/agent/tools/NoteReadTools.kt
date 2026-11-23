package com.example.myapplication.data.agent.tools

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.ReaderCatalogDao
import com.example.myapplication.data.reader.notes.NoteRepository
import com.example.myapplication.domain.agent.AgentHashing
import com.example.myapplication.domain.agent.AgentTool
import com.example.myapplication.domain.agent.ToolArgumentException
import com.example.myapplication.domain.agent.ToolArgs
import com.example.myapplication.domain.agent.ToolError
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolGroup
import com.example.myapplication.domain.agent.ToolResult
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.agent.boundedObject
import com.example.myapplication.domain.agent.integerSchema
import com.example.myapplication.domain.agent.stringSchema
import com.example.myapplication.domain.reader.AccessContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 笔记读取工具（W4-02 / 详细设计 §3 工具权限表 / §13）。
 *
 * - read_note：只读 notebook 或 annotation；返回当前 revision 与 read receipt
 *   （runId/targetId/revision/hash/eventSeq），供后续写提议（其它工作包）复述基线；
 *   读前核验同 user + 授权文档（删除/撤权/非 READY → ACCESS_REVOKED）。
 * - search_notes：**用户笔记 namespace** 内检索（reader_notebooks body 与
 *   reader_annotations noteText/quote），结果按当前 user 过滤，分页；教材证据编号
 *   由 EvidenceRegistry 产生，不与笔记结果混编号。
 *
 * 本期不实现 propose_note_patch（W4-05）；搜索使用参数化只读 SQL（不新增 v5 DAO/实体）。
 */
class NoteReadTools(
    private val database: AppDatabase,
    private val noteRepository: NoteRepository,
    private val catalog: ReaderCatalogDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun tools(): List<AgentTool> = listOf(ReadNoteTool(), SearchNotesTool())

    private sealed interface TargetResolution {
        data class Resolved(
            val targetKind: String,
            val targetId: String,
            val documentId: String,
            val revision: Long,
            val updatedAt: Long,
            val body: String,
            val sectionId: String? = null,
            val annotationKind: String? = null,
            val quote: String? = null,
            val noteText: String? = null,
        ) : TargetResolution

        data class Failed(val error: ToolError) : TargetResolution
    }

    private inner class ReadNoteTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "read_note",
            group = ToolGroup.NOTE_READING,
            description = "读取当前用户的 notebook 或 annotation（同 user + 授权文档），" +
                "返回 revision 与 read receipt；不修改正文。",
            parameters = boundedObject(
                required = emptyList(),
                properties = mapOf(
                    "notebookId" to stringSchema(1, MAX_TARGET_ID_CHARS),
                    "annotationId" to stringSchema(1, MAX_TARGET_ID_CHARS),
                    "revision" to integerSchema(0, Int.MAX_VALUE),
                    "maxChars" to integerSchema(1, MAX_NOTE_BODY_CHARS),
                ),
            ),
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val notebookId = ToolArgs.optionalString(arguments, "notebookId", MAX_TARGET_ID_CHARS)
                val annotationId = ToolArgs.optionalString(arguments, "annotationId", MAX_TARGET_ID_CHARS)
                if ((notebookId == null) == (annotationId == null)) {
                    throw ToolArgumentException("target", "exactly one of notebookId/annotationId is required")
                }
                val requestedRevision = ToolArgs.optionalInt(arguments, "revision", 0, Int.MAX_VALUE)?.toLong()
                val maxChars = ToolArgs.optionalInt(arguments, "maxChars", 1, MAX_NOTE_BODY_CHARS)
                    ?: DEFAULT_READ_CHARS
                when (val resolved = if (notebookId != null) {
                    resolveNotebook(context.accessContext(), notebookId, requestedRevision, maxChars)
                } else {
                    resolveAnnotation(context.accessContext(), annotationId!!, requestedRevision, maxChars)
                }) {
                    is TargetResolution.Failed -> ToolResult.failure("", spec.name, resolved.error)
                    is TargetResolution.Resolved -> {
                        val document = catalog.findDocumentIncludingDeleted(resolved.documentId)
                        val receipt = buildJsonObject {
                            put("runId", context.runId)
                            put("targetId", resolved.targetId)
                            put("revision", resolved.revision)
                            put("hash", AgentHashing.sha256(
                                if (resolved.targetKind == "NOTEBOOK") {
                                    resolved.body
                                } else {
                                    (resolved.quote.orEmpty() + "\u0000" + resolved.noteText.orEmpty())
                                },
                            ))
                            put("eventSeq", context.eventSeq)
                        }
                        val payload = buildJsonObject {
                            put("targetKind", resolved.targetKind)
                            put("targetId", resolved.targetId)
                            put("documentId", resolved.documentId)
                            put("title", document?.title.orEmpty())
                            resolved.sectionId?.let { put("sectionId", it) }
                            resolved.annotationKind?.let { put("annotationKind", it) }
                            put("revision", resolved.revision)
                            put("updatedAt", resolved.updatedAt)
                            put("truncated", resolved.body.length >= maxChars)
                            if (resolved.targetKind == "NOTEBOOK") {
                                put("body", resolved.body.take(maxChars))
                                put("bodyLength", resolved.body.length)
                            } else {
                                put("quote", resolved.quote.orEmpty())
                                put("noteText", resolved.noteText.orEmpty())
                            }
                            putJsonObject("readReceipt") {
                                put("runId", context.runId)
                                put("targetId", resolved.targetId)
                                put("revision", resolved.revision)
                                put("hash", receipt.stringOrEmpty("hash"))
                                put("eventSeq", context.eventSeq)
                            }
                        }
                        ToolResult.success(callId = "", toolName = spec.name, payload = payload)
                    }
                }
            } catch (error: ToolArgumentException) {
                ToolResult.failure(
                    callId = "",
                    toolName = spec.name,
                    error = ToolError(ToolErrorCode.INVALID_ARGUMENT, error.message.orEmpty(), true, error.field),
                )
            }
        }

        private suspend fun resolveNotebook(
            context: AccessContext,
            notebookId: String,
            requestedRevision: Long?,
            maxChars: Int,
        ): TargetResolution {
            val notebook = database.readerNotebookDao().findNotebook(context.userId, notebookId)
                ?: return TargetResolution.Failed(ToolError(ToolErrorCode.NOT_FOUND, "notebook not found"))
            authorize(context, notebook.documentId)?.let { return TargetResolution.Failed(it) }
            if (requestedRevision != null && requestedRevision != notebook.revision) {
                return TargetResolution.Failed(
                    ToolError(ToolErrorCode.REVISION_MISSING, "notebook revision no longer matches"),
                )
            }
            return TargetResolution.Resolved(
                targetKind = "NOTEBOOK",
                targetId = notebook.id,
                documentId = notebook.documentId,
                revision = notebook.revision,
                updatedAt = notebook.updatedAt,
                body = notebook.bodyMarkdown.take(maxChars),
            )
        }

        private suspend fun resolveAnnotation(
            context: AccessContext,
            annotationId: String,
            requestedRevision: Long?,
            maxChars: Int,
        ): TargetResolution {
            val annotation = database.readerAnnotationDao().findAnnotation(context.userId, annotationId)
                ?: return TargetResolution.Failed(ToolError(ToolErrorCode.NOT_FOUND, "annotation not found"))
            if (annotation.deletedAt != null) {
                return TargetResolution.Failed(ToolError(ToolErrorCode.NOT_FOUND, "annotation is deleted"))
            }
            authorize(context, annotation.documentId)?.let { return TargetResolution.Failed(it) }
            if (requestedRevision != null && requestedRevision != annotation.revision) {
                return TargetResolution.Failed(
                    ToolError(ToolErrorCode.REVISION_MISSING, "annotation revision no longer matches"),
                )
            }
            return TargetResolution.Resolved(
                targetKind = "ANNOTATION",
                targetId = annotation.id,
                documentId = annotation.documentId,
                sectionId = annotation.sectionId,
                annotationKind = annotation.kind,
                quote = annotation.quoteSnapshot?.take(maxChars),
                noteText = annotation.noteText?.take(maxChars),
                revision = annotation.revision,
                updatedAt = annotation.updatedAt,
                body = annotation.noteText.orEmpty().take(maxChars),
            )
        }

        private suspend fun authorize(context: AccessContext, documentId: String): ToolError? {
            // NoteRepository.load 内部按 ReaderAccessPolicy 复核 owner/kind/READY/deleted。
            val loaded = noteRepository.load(context, documentId)
            if (loaded != null) return null
            val document = catalog.findDocumentIncludingDeleted(documentId)
                ?: return ToolError(ToolErrorCode.NOT_FOUND, "document not found")
            val rec = com.example.myapplication.domain.reader.ReaderDocumentAccessRecord(
                documentId = document.id,
                kind = document.kind,
                ownerUserId = document.ownerUserId,
                contentRevision = document.contentRevision,
                accessEpoch = document.accessEpoch,
                importStatus = document.importStatus,
                deletedAt = document.deletedAt,
            )
            val decision = com.example.myapplication.domain.reader.ReaderAccessPolicy.canRead(context.userId, rec)
            return if (decision is com.example.myapplication.domain.reader.ReaderAccessDecision.Denied &&
                decision.code == "ACCESS_REVOKED"
            ) {
                ToolError(ToolErrorCode.ACCESS_REVOKED, "document access revoked")
            } else {
                ToolError(ToolErrorCode.NOT_FOUND, "document not readable")
            }
        }
    }

    private inner class SearchNotesTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "search_notes",
            group = ToolGroup.NOTE_READING,
            description = "在当前用户的笔记 namespace 内检索（notebook 正文与 annotation note），分页；" +
                "不返回其他用户或未授权文档的笔记。",
            parameters = boundedObject(
                required = listOf("query"),
                properties = mapOf(
                    "query" to stringSchema(1, MAX_NOTE_QUERY_CHARS),
                    "cursor" to stringSchema(1, 32),
                    "limit" to integerSchema(1, MAX_NOTE_SEARCH_LIMIT),
                    "documentId" to stringSchema(1, MAX_TARGET_ID_CHARS),
                ),
            ),
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val query = ToolArgs.requireString(arguments, "query", 1, MAX_NOTE_QUERY_CHARS)
                val cursor = ToolArgs.optionalString(arguments, "cursor", 32)
                val limit = ToolArgs.optionalInt(arguments, "limit", 1, MAX_NOTE_SEARCH_LIMIT) ?: DEFAULT_NOTE_SEARCH_LIMIT
                val documentId = ToolArgs.optionalString(arguments, "documentId", MAX_TARGET_ID_CHARS)
                val offset = cursor?.let {
                    it.toIntOrNull()?.takeIf { value -> value >= 0 }
                        ?: throw ToolArgumentException("cursor", "cursor must be a non-negative integer")
                } ?: 0
                // 每源多取 offset+limit+1：既能覆盖全局前 offset+limit 项，又能判断是否还有下一页
                val fetch = (offset + limit + 1).coerceAtMost(MAX_NOTE_SEARCH_FETCH)
                val notebookRows = searchNotebooks(context.userId, query, documentId, fetch)
                val annotationRows = searchAnnotations(context.userId, query, documentId, fetch)
                val merged = (notebookRows + annotationRows)
                    .sortedWith(compareByDescending<SearchRow> { it.updatedAt }.thenBy { it.id })
                val page = merged.drop(offset).take(limit)
                val nextCursor = if (merged.size > offset + limit) (offset + limit).toString() else null
                val payload = buildJsonObject {
                    put("query", query)
                    put("count", page.size)
                    if (nextCursor != null) put("nextCursor", nextCursor)
                    putJsonArray("notes") {
                        page.forEach { row ->
                            addJsonObject {
                                put("kind", row.kind)
                                put("id", row.id)
                                put("documentId", row.documentId)
                                put("title", row.title)
                                put("revision", row.revision)
                                put("updatedAt", row.updatedAt)
                                put("snippet", row.snippet)
                            }
                        }
                    }
                }
                ToolResult.success(callId = "", toolName = spec.name, payload = payload)
            } catch (error: ToolArgumentException) {
                ToolResult.failure(
                    callId = "",
                    toolName = spec.name,
                    error = ToolError(ToolErrorCode.INVALID_ARGUMENT, error.message.orEmpty(), true, error.field),
                )
            }
        }

        private fun searchNotebooks(
            userId: Long,
            query: String,
            documentId: String?,
            limit: Int,
        ): List<SearchRow> {
            val sql = buildString {
                append("SELECT n.id, n.document_id, n.body_markdown, n.revision, n.updated_at, d.title ")
                append("FROM reader_notebooks n JOIN reader_documents d ON d.id = n.document_id ")
                append("WHERE n.user_id = ? AND d.deleted_at IS NULL AND d.import_status = 'READY' ")
                append("AND (d.kind = 'BUILTIN' OR d.owner_user_id = ?) ")
                append("AND n.body_markdown LIKE ? ESCAPE '\\' ")
                if (documentId != null) append("AND n.document_id = ? ")
                append("ORDER BY n.updated_at DESC, n.id ASC LIMIT ?")
            }
            val args = mutableListOf<Any?>(userId, userId, likePattern(query))
            if (documentId != null) args += documentId
            args += limit
            return runReadQuery(sql, args.toTypedArray()) { cursor ->
                val index = cursor.getColumnIndexOrThrow("body_markdown")
                val body = cursor.getString(index).orEmpty()
                SearchRow(
                    kind = "NOTEBOOK",
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    documentId = cursor.getString(cursor.getColumnIndexOrThrow("document_id")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")).orEmpty(),
                    revision = cursor.getLong(cursor.getColumnIndexOrThrow("revision")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    snippet = snippet(body, query),
                )
            }
        }

        private fun searchAnnotations(
            userId: Long,
            query: String,
            documentId: String?,
            limit: Int,
        ): List<SearchRow> {
            val sql = buildString {
                append("SELECT a.id, a.document_id, a.quote_snapshot, a.note_text, a.revision, a.updated_at, d.title ")
                append("FROM reader_annotations a JOIN reader_documents d ON d.id = a.document_id ")
                append("WHERE a.user_id = ? AND a.deleted_at IS NULL AND d.deleted_at IS NULL ")
                append("AND d.import_status = 'READY' AND (d.kind = 'BUILTIN' OR d.owner_user_id = ?) ")
                append("AND (a.note_text LIKE ? ESCAPE '\\' OR a.quote_snapshot LIKE ? ESCAPE '\\') ")
                if (documentId != null) append("AND a.document_id = ? ")
                append("ORDER BY a.updated_at DESC, a.id ASC LIMIT ?")
            }
            val pattern = likePattern(query)
            val args = mutableListOf<Any?>(userId, userId, pattern, pattern)
            if (documentId != null) args += documentId
            args += limit
            return runReadQuery(sql, args.toTypedArray()) { cursor ->
                val note = cursor.getString(cursor.getColumnIndexOrThrow("note_text"))
                val quote = cursor.getString(cursor.getColumnIndexOrThrow("quote_snapshot"))
                SearchRow(
                    kind = "ANNOTATION",
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    documentId = cursor.getString(cursor.getColumnIndexOrThrow("document_id")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")).orEmpty(),
                    revision = cursor.getLong(cursor.getColumnIndexOrThrow("revision")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    snippet = snippet(note ?: quote.orEmpty(), query),
                )
            }
        }

        private fun <T> runReadQuery(
            sql: String,
            args: Array<Any?>,
            mapper: (android.database.Cursor) -> T,
        ): List<T> {
            val cursor = database.openHelper.readableDatabase.query(sql, args)
            cursor.use {
                val rows = mutableListOf<T>()
                while (it.moveToNext()) rows += mapper(it)
                return rows
            }
        }

        private fun likePattern(query: String): String =
            "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        private fun snippet(text: String, query: String): String {
            val index = text.indexOf(query, ignoreCase = true)
            if (index < 0) return text.take(SNIPPET_RADIUS * 2)
            val start = (index - SNIPPET_RADIUS).coerceAtLeast(0)
            val end = (index + query.length + SNIPPET_RADIUS).coerceAtMost(text.length)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < text.length) "…" else ""
            return prefix + text.substring(start, end) + suffix
        }
    }

    private data class SearchRow(
        val kind: String,
        val id: String,
        val documentId: String,
        val title: String,
        val revision: Long,
        val updatedAt: Long,
        val snippet: String,
    )

    private fun JsonObject.stringOrEmpty(name: String): String =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    companion object {
        const val MAX_TARGET_ID_CHARS = 256
        const val MAX_NOTE_BODY_CHARS = 65_536
        const val DEFAULT_READ_CHARS = 16_384
        const val MAX_NOTE_QUERY_CHARS = 400
        const val MAX_NOTE_SEARCH_LIMIT = 50
        const val DEFAULT_NOTE_SEARCH_LIMIT = 20
        const val MAX_NOTE_SEARCH_FETCH = 200
        const val SNIPPET_RADIUS = 60
    }
}
