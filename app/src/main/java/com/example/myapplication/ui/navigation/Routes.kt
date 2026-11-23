package com.example.myapplication.ui.navigation

import android.net.Uri
import com.example.myapplication.ui.ai.AgentLaunchContext
import com.example.myapplication.domain.reader.ReaderPlacement

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val KNOWLEDGE_REVIEW = "knowledge-review"
    const val READER = "reader/{documentId}?locator={locator}&returnKey={returnKey}"
    const val SOURCE_READER = "source-reader/{returnKey}"
    const val ENCYCLOPEDIA_SOURCE = "encyclopedia-source/{returnKey}"
    const val NOTEBOOK = "notebook/{documentId}"
    const val COURSE_LIBRARY = "course-library"
    const val IMPORTED_LIBRARY = "imported-library"
    const val COURSE_BOOK = "course-book/{bookId}"
    const val BUNDLED_COURSE = "bundled-course/{bookKey}"
    const val COURSE_READER = "course-reader/{bookId}/{chapterKey}?placement={placement}&locator={locator}&returnKey={returnKey}&source={source}"

    /** 全屏 AI 工作区（关闭≠停止；返回阅读现场由 back stack 保证）。 */
    const val AGENT =
        "agent?documentId={documentId}&documentTitle={documentTitle}&sectionId={sectionId}" +
            "&sectionTitle={sectionTitle}&quote={quote}&locator={locator}&returnKey={returnKey}"

    /** 随堂测生成/答题/结果/历史。 */
    const val QUIZ_GENERATING = "quiz/generating/{documentId}?sectionId={sectionId}"
    const val SOURCE_PRACTICE = "quiz/practice/{documentId}?sectionId={sectionId}&paperId={paperId}"
    const val SOURCE_PRACTICE_ARCHIVE = "quiz/saved-practice/{recordId}?questionId={questionId}"
    const val QUIZ_ATTEMPT = "quiz/attempt/{attemptId}"
    const val QUIZ_RESULT = "quiz/result/{attemptId}"
    const val QUIZ_REVIEW = "quiz/review?paperId={paperId}"
    fun quizReview(paperId: String? = null): String = "quiz/review?paperId=${Uri.encode(paperId.orEmpty())}"
    const val QUIZ_HISTORY = "quiz/history"
    const val QUIZ_REGENERATE = "quiz/regenerate/{paperId}"

    /**
     * 阅读器路由：可带一个持久 Locator（来源卡片/S[#] 精确跳转）。
     *
     * locator 只用于恢复阅读位置，不参与权限判断；空值时按上次进度打开。
     */
    fun reader(documentId: String, locatorJson: String? = null, returnKey: String? = null): String =
        "reader/${Uri.encode(documentId)}?locator=${Uri.encode(locatorJson.orEmpty())}&returnKey=${returnKey?.let(::checkedReturnKey).orEmpty()}"

    fun notebook(documentId: String): String = "notebook/${Uri.encode(documentId)}"
    fun courseBook(bookId: String): String = "course-book/${Uri.encode(bookId)}"
    fun bundledCourse(bookKey: String): String = "bundled-course/${Uri.encode(bookKey)}"
    fun sourcePracticeArchive(recordId: String, questionId: String? = null): String =
        "quiz/saved-practice/${Uri.encode(recordId)}?questionId=${Uri.encode(questionId.orEmpty())}"
    fun courseReader(bookId: String, chapterKey: String, placement: ReaderPlacement, returnKey: String,
        locatorJson: String? = null, source: Boolean = false): String =
        "course-reader/${Uri.encode(bookId)}/${Uri.encode(chapterKey)}?placement=${placement.name}" +
            "&locator=${Uri.encode(locatorJson.orEmpty())}&returnKey=${checkedReturnKey(returnKey)}&source=$source"

    /** Legacy builder retained for previously saved routes; new launches use [agent] with a key. */
    @Deprecated("Persist launch context and navigate with a returnKey")
    fun agent(context: AgentLaunchContext): String = buildString {
        append("agent?documentId=").append(Uri.encode(context.documentId.orEmpty()))
        append("&documentTitle=").append(Uri.encode(context.documentTitle.orEmpty()))
        append("&sectionId=").append(Uri.encode(context.sectionId.orEmpty()))
        append("&sectionTitle=").append(Uri.encode(context.sectionTitle.orEmpty()))
        append("&quote=").append(Uri.encode(context.quote.orEmpty()))
        append("&locator=").append(Uri.encode(context.locatorJson.orEmpty()))
    }

    fun agent(returnKey: String): String = "agent?returnKey=${checkedReturnKey(returnKey)}"

    fun sourceReader(returnKey: String): String = "source-reader/${checkedReturnKey(returnKey)}"

    fun encyclopediaSource(returnKey: String): String = "encyclopedia-source/${checkedReturnKey(returnKey)}"

    fun readLegacyAgent(arg: (String) -> String?): AgentLaunchContext {
        // Navigation already URI-decodes each argument. A second decode corrupts literal %20/%2F.
        fun value(name: String) = arg(name)?.takeIf { it.isNotBlank() }
        return AgentLaunchContext(value("documentId"), value("documentTitle"), value("sectionId"),
            value("sectionTitle"), value("quote"), value("locator"))
    }

    private fun checkedReturnKey(key: String): String {
        require(key.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        return key
    }

    fun quizGenerating(documentId: String, sectionId: String?): String =
        "quiz/generating/${Uri.encode(documentId)}?sectionId=${Uri.encode(sectionId.orEmpty())}"

    fun sourcePractice(documentId: String, sectionId: String? = null, paperId: String? = null): String =
        "quiz/practice/${Uri.encode(documentId)}?sectionId=${Uri.encode(sectionId.orEmpty())}&paperId=${Uri.encode(paperId.orEmpty())}"

    fun quizAttempt(attemptId: String): String = "quiz/attempt/${Uri.encode(attemptId)}"

    fun quizResult(attemptId: String): String = "quiz/result/${Uri.encode(attemptId)}"

    fun quizRegenerating(paperId: String): String = "quiz/regenerate/${Uri.encode(paperId)}"
}
