package com.example.myapplication.data.agent.notes

import com.example.myapplication.data.reader.notes.ReaderMutationHasher

/**
 * W4-05：propose_note_patch 的输入校验与 previewHash。
 *
 * 规则（第四期详细设计 §6/§13、06-验证 FX-05/06）：
 * - 单次 ≤ [MAX_EDITS] 段，新增文本合计 ≤ [MAX_NEW_TEXT_BYTES] 字节（UTF-8 初值，可配置产品预算）；
 * - 每段 oldText 必须在当前正文中**唯一命中**，只允许 [NotePatchEdit.OCCURRENCE_UNIQUE]；
 * - 不允许整篇 REPLACE（oldText 覆盖全文即拒绝）；
 * - 不允许触碰高亮 quote / 墨迹 / 源数据（[NoteProposalTarget.protectedTexts]）；
 * - 按顺序应用；任一段失败则整体失败，返回首个错误码；
 * - previewHash 覆盖 targetKind/targetId/baseRevision/按序规范化的 edits，确认时重算比对。
 *
 * 纯函数、无 Android/Room 依赖，可直接 JVM 单测。
 */
object NotePatchValidator {
    const val MAX_EDITS = 20
    const val MAX_NEW_TEXT_BYTES = 32 * 1024
    const val MAX_BODY_CHARS = 8_000_000

    const val CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val CODE_TOO_MANY_EDITS = "TOO_MANY_EDITS"
    const val CODE_TEXT_TOO_LARGE = "TEXT_TOO_LARGE"
    const val CODE_BAD_PATCH = "BAD_PATCH"
    const val CODE_PATCH_NOT_FOUND = "PATCH_NOT_FOUND"
    const val CODE_PATCH_NOT_UNIQUE = "PATCH_NOT_UNIQUE"
    const val CODE_WHOLE_BODY_REPLACE = "WHOLE_BODY_REPLACE"
    const val CODE_PROTECTED_SOURCE = "PROTECTED_SOURCE"
    const val CODE_NO_CHANGE = "NO_CHANGE"

    fun validate(
        target: NoteProposalTarget,
        edits: List<NotePatchEdit>,
    ): NotePatchValidation {
        if (edits.isEmpty()) return NotePatchValidation.Invalid(CODE_INVALID_ARGUMENT)
        if (edits.size > MAX_EDITS) return NotePatchValidation.Invalid(CODE_TOO_MANY_EDITS)

        var addedBytes = 0L
        for (edit in edits) {
            if (edit.occurrencePolicy != NotePatchEdit.OCCURRENCE_UNIQUE) {
                return NotePatchValidation.Invalid(CODE_INVALID_ARGUMENT)
            }
            if (edit.oldText.isEmpty()) return NotePatchValidation.Invalid(CODE_BAD_PATCH)
            addedBytes += utf8Length(edit.newText)
            if (addedBytes > MAX_NEW_TEXT_BYTES) {
                return NotePatchValidation.Invalid(CODE_TEXT_TOO_LARGE)
            }
            if (target.body.isNotEmpty() && edit.oldText == target.body) {
                return NotePatchValidation.Invalid(CODE_WHOLE_BODY_REPLACE)
            }
            if (touchesProtectedSource(target.protectedTexts, edit)) {
                return NotePatchValidation.Invalid(CODE_PROTECTED_SOURCE)
            }
        }

        var body = target.body
        for (edit in edits) {
            val first = body.indexOf(edit.oldText)
            if (first < 0) return NotePatchValidation.Invalid(CODE_PATCH_NOT_FOUND)
            if (body.indexOf(edit.oldText, first + 1) >= 0) {
                return NotePatchValidation.Invalid(CODE_PATCH_NOT_UNIQUE)
            }
            body = body.substring(0, first) + edit.newText + body.substring(first + edit.oldText.length)
            if (body.length > MAX_BODY_CHARS) {
                return NotePatchValidation.Invalid(CODE_TEXT_TOO_LARGE)
            }
        }
        if (body == target.body) return NotePatchValidation.Invalid(CODE_NO_CHANGE)

        return NotePatchValidation.Valid(
            newBody = body,
            previewHash = previewHash(target.kind, target.targetId, target.revision, edits),
        )
    }

    /**
     * previewHash：kind|targetId|baseRevision + 按序 edits（old/new/policy）。
     * 确认时用同一 baseRevision 与持久化 edits 重算，任何字段变化都会失配。
     */
    fun previewHash(
        kind: NoteProposalTargetKind,
        targetId: String,
        baseRevision: Long,
        edits: List<NotePatchEdit>,
    ): String {
        val canonical = buildString {
            append(kind.wire).append('\u0001')
            append(targetId).append('\u0001')
            append(baseRevision).append('\u0001')
            edits.forEach { edit ->
                append(edit.oldText).append('\u0002')
                append(edit.newText).append('\u0002')
                append(edit.occurrencePolicy).append('\u0003')
            }
        }
        return ReaderMutationHasher.sha256(canonical)
    }

    private fun touchesProtectedSource(protectedTexts: List<String>, edit: NotePatchEdit): Boolean =
        protectedTexts.any { protected ->
            if (protected.length < 2) {
                false
            } else {
                edit.oldText.contains(protected) ||
                    edit.newText.contains(protected) ||
                    protected.contains(edit.oldText)
            }
        }

    private fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size
}
