package com.example.myapplication.data.quiz

import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SourcePracticeArchiveRestoration(
    val record: SourcePracticeRecord,
    val currentSourceAvailable: Boolean,
    val sourceWarning: String? = null,
)

/** Historical quotes belong to the saved user record; current-source actions still require verification. */
internal suspend fun restoreSourcePracticeArchive(
    repository: SourcePracticeArchiveRepository,
    identity: SessionIdentity,
    recordId: String,
    verifySources: suspend (SourcePracticeScope) -> Unit,
): SourcePracticeArchiveRestoration {
    val saved = repository.load(identity, recordId) ?: throw SourcePracticeArchiveException("这份随堂记录已不存在。")
    val available = try {
        verifySources(saved.scope)
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    currentCoroutineContext().ensureActive()
    // Source failure is recoverable; a changed user or epoch is never treated as source failure.
    val current = repository.load(identity, recordId) ?: throw SourcePracticeArchiveException("这份随堂记录已不存在。")
    if (current.practice != saved.practice || current.scope != saved.scope)
        throw SourcePracticeArchiveException("记录的题目或章节来源已变化，请重新打开。")
    return SourcePracticeArchiveRestoration(current, available, if (available) null else
        "原文已更新或暂时不可用。当前显示保存的题目与生成时的摘录；请返回阅读重新生成后，再向 AI 提问本章。")
}
