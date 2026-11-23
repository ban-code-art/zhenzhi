package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizItemType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.util.Locale

enum class SourcePracticeOrigin(val label: String) {
    COURSE_DRAFT("内置课程练习"),
    SOURCE_TEXT("原文练习"),
    AI_REVIEW_DRAFT("AI 学习草稿"),
}

enum class SourcePracticeKind { CHOICE, CLOZE, RECALL }

/** A local study session. It cannot publish a formal paper or write an official grade. */
data class SourcePractice(
    val id: String,
    val title: String,
    val sourceRevision: String,
    val origin: SourcePracticeOrigin,
    val questions: List<SourcePracticeQuestion>,
    val coverageNote: String,
    val excludedQuestionCount: Int = 0,
)

data class SourcePracticeQuestion(
    val id: String,
    val kind: SourcePracticeKind,
    val stem: String,
    val options: List<String> = emptyList(),
    val answers: List<String>,
    val analysis: String,
    val sourceQuotes: List<String>,
    val sourceLabel: String,
    val sectionIds: List<String>,
    val score: Int = 1,
)

/** Only verified, currently authorized source text should be supplied by the loader. */
data class SourcePracticeSection(
    val sectionId: String,
    val title: String,
    val text: String,
    /** Explanation for the final body segment, never a question or distractor source. */
    val explanation: String = "",
)

/** A verbatim prefix of verified source text, preserving a complete UTF-16 code point at its edge. */
internal fun sourcePracticePrefix(text: String, maxChars: Int): String {
    return sourcePracticeSlice(text, 0, maxChars)
}

/** Reads a saved block offset without allocating or rescanning its entire suffix. */
internal fun sourcePracticeSlice(text: String, start: Int, maxChars: Int): String {
    require(start in 0..text.length && maxChars >= 0)
    require(start == 0 || start == text.length || !Character.isLowSurrogate(text[start]) ||
        !Character.isHighSurrogate(text[start - 1]))
    var end = start + minOf(text.length - start, maxChars)
    if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1]) &&
        Character.isLowSurrogate(text[end])) end--
    return text.substring(start, end.coerceAtLeast(start))
}

object SourcePracticeFactory {
    private const val MAX_LOCAL_QUESTIONS = 12
    private const val MAX_SOURCE_CANDIDATES = 512
    private const val MAX_DEFINITIONS = 256
    private const val MAX_NUMERIC_FACTS_PER_SECTION = 96
    private const val MAX_RECALL_FACTS_PER_SECTION = 64
    private val whitespace = Regex("\\s+")
    private val optionPrefix = Regex("^[A-Z][.．、)）:]\\s*")
    private val definition = Regex("^([^：:，,。！？；;\\n]{1,32}?)(?:是指|定义为|指的是|指|是|：|:)(.{6,200})$")
    private val quantity = Regex("(?<![\\dA-Za-z])\\d+(?:\\.\\d+)?\\s*(?:毫秒|分钟|小时|字节|KiB|MiB|GiB|KB|MB|GB|秒|天|年|次|个|层|步|项|种|分|度|℃|％|%|cm|mm|mL|mg)")
    private val enumeration = Regex("包括|分为|分别为|由.+组成|步骤")
    private val genericConcepts = setOf("这", "它", "其", "我", "本章", "本节", "本文", "这里", "例如", "其中", "它们")

    fun fromCourseDraft(
        title: String,
        manifest: JsonObject,
        chapterKey: String,
        sourceRevision: String,
        sourceText: String,
    ): SourcePractice {
        val normalizedSource = normalize(sourceText)
        val records = (manifest["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.string("chapterKey") == chapterKey }
        val questions = records.mapNotNull { item ->
            val key = item.string("questionKey")
            val stem = item.string("stem")
            val answers = item.strings("answer")
            val score = (item["score"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val quotes = (item["evidenceRefs"] as? JsonArray).orEmpty().mapNotNull { ref ->
                (ref as? JsonObject)?.string("quote")?.takeIf { it.isNotBlank() }
            }.distinct()
            if (key.isBlank() || stem.isBlank() || answers.isEmpty() || score <= 0 ||
                quotes.isEmpty() || quotes.any { !normalizedSource.contains(normalize(it)) }) return@mapNotNull null
            val kind = when (item.string("type")) {
                "CHOICE" -> SourcePracticeKind.CHOICE
                "ESSAY" -> SourcePracticeKind.RECALL
                else -> return@mapNotNull null
            }
            val options = item.strings("options")
            if (kind == SourcePracticeKind.CHOICE &&
                (options.size < 2 || options.distinct().size != options.size ||
                    answers.size != 1 || answers.single() !in options)) return@mapNotNull null
            SourcePracticeQuestion(
                id = stableId("course", sourceRevision, key),
                kind = kind,
                stem = stem,
                options = if (kind == SourcePracticeKind.CHOICE) options else emptyList(),
                answers = answers,
                analysis = item.string("analysis").ifBlank { "请对照下方原文核对答案。" },
                sourceQuotes = quotes,
                sourceLabel = title,
                sectionIds = listOf(chapterKey),
                score = score,
            )
        }.distinctBy { it.id }
        val excluded = records.size - questions.size
        return SourcePractice(
            id = stableId("course-practice", sourceRevision, chapterKey, questions.joinToString { it.id }),
            title = title,
            sourceRevision = sourceRevision,
            origin = SourcePracticeOrigin.COURSE_DRAFT,
            questions = questions,
            coverageNote = "本章现有 " + questions.size + " 题，答案、解析和引用均来自课程材料；简答题请自行对照原文。"
                + if (excluded > 0) " 已跳过 " + excluded + " 道无法绑定当前原文的题目。" else "",
            excludedQuestionCount = excluded,
        )
    }

    fun fromSourceText(
        title: String,
        sourceRevision: String,
        sections: List<SourcePracticeSection>,
        complete: Boolean = true,
        excludeQuestionIds: Set<String> = emptySet(),
    ): SourcePractice {
        val candidates = sourceCandidates(sourceRevision, sections)
        val excludedSources = candidates.filter { it.id in excludeQuestionIds }.map(::sourceFingerprint).toSet()
        val selected = selectSourceQuestions(candidates.filter {
            it.id !in excludeQuestionIds && sourceFingerprint(it) !in excludedSources
        }, MAX_LOCAL_QUESTIONS)
        return sourceBatch(title, sourceRevision, selected, sections.map { it.sectionId }.distinct().size, complete)
    }

    /** Candidate extraction happens once per newly read window; more batches reuse this result. */
    internal fun sourceCandidates(
        sourceRevision: String,
        sections: List<SourcePracticeSection>,
    ): List<SourcePracticeQuestion> {
        data class Definition(
            val concept: String,
            val value: String,
            val sentence: SourceSentence,
            val section: SourcePracticeSection,
        )
        val sentencesBySection = sections.associateWith { section -> sourceSentences(section) }
        val definitions = sentencesBySection.flatMap { (section, lines) ->
            lines.mapNotNull { line ->
                val matched = definition.matchEntire(line.text) ?: return@mapNotNull null
                val concept = matched.groupValues[1].trim()
                val value = matched.groupValues[2].trim()
                if (concept in genericConcepts || concept.startsWith("当") || concept.startsWith("如果") ||
                    concept.startsWith("因此") || value.length < 6) null
                else Definition(concept, value, line, section)
            }
        }.distinctBy { normalize(it.concept) to normalize(it.value) }.take(MAX_DEFINITIONS)
        val candidates = mutableListOf<SourcePracticeQuestion>()
        definitions.forEach { item ->
            val distractors = definitions.asSequence().filter { other ->
                other.concept != item.concept && normalize(other.value) != normalize(item.value) &&
                    !normalize(other.value).contains(normalize(item.value)) &&
                    !normalize(item.value).contains(normalize(other.value))
            }.sortedBy { kotlin.math.abs(it.value.length - item.value.length) }.map { it.value }.distinct().take(3).toList()
            if (distractors.isNotEmpty()) {
                val values = listOf(item.value) + distractors
                val offset = Math.floorMod(stableId(sourceRevision, item.sentence.text).hashCode(), values.size)
                val options = values.drop(offset) + values.take(offset)
                candidates += SourcePracticeQuestion(
                    stableId("definition", sourceRevision, item.section.sectionId, item.sentence.text),
                    SourcePracticeKind.CHOICE,
                    "根据原文，「" + item.concept + "」对应的描述是？",
                    options,
                    listOf(item.value),
                    explained("原文将「" + item.concept + "」描述为「" + item.value + "」。其他选项取自文中其他概念的描述。",
                        item.sentence.explanation),
                    listOf(item.sentence.text),
                    item.section.title,
                    listOf(item.section.sectionId),
                )
            }
        }
        sentencesBySection.forEach { (section, lines) ->
            lines.asSequence().filter { it.text.length in 16..260 }.flatMap { line ->
                quantity.findAll(line.text).take(2).map { match -> line to match }
            }.take(MAX_NUMERIC_FACTS_PER_SECTION).forEach { (line, match) ->
                candidates += SourcePracticeQuestion(
                    stableId("quantity", sourceRevision, section.sectionId, line.text, match.range.first.toString()),
                    SourcePracticeKind.CLOZE,
                    "根据原文，补全数值（含单位）：\n" + line.text.replaceRange(match.range, "____"),
                    answers = listOf(match.value),
                    analysis = explained("原文中的完整数值是「" + match.value + "」，请同时核对数值和单位。", line.explanation),
                    sourceQuotes = listOf(line.text),
                    sourceLabel = section.title,
                    sectionIds = listOf(section.sectionId),
                )
            }
            val recallLines = lines.filter {
                it.text.length in 24..500 && enumeration.containsMatchIn(it.text)
            } + lines.filter { it.text.length in 30..500 && !enumeration.containsMatchIn(it.text) }
            recallLines.distinctBy { normalize(it.text) }.take(MAX_RECALL_FACTS_PER_SECTION).forEach { line ->
                val match = enumeration.find(line.text)
                val prefix = match?.let { line.text.take(it.range.first).trim().takeLast(36) }.orEmpty()
                candidates += SourcePracticeQuestion(
                    stableId("recall", sourceRevision, section.sectionId, line.text),
                    SourcePracticeKind.RECALL,
                    if (match != null && prefix.isNotBlank())
                        "请写出原文中「" + prefix + "」涉及的要点，并说明它们之间的关系。"
                    else "用自己的话概括「" + section.title + "」中这段原文的关键内容：\n\n" +
                        sourcePracticePrefix(line.text, 120),
                    answers = listOf(line.text),
                    analysis = explained("这道题用于回忆和理解。对照原文检查关键概念、顺序和限制条件，再标记自己的掌握情况。",
                        line.explanation),
                    sourceQuotes = listOf(line.text),
                    sourceLabel = section.title,
                    sectionIds = listOf(section.sectionId),
                )
            }
        }
        if (candidates.isEmpty()) {
            sentencesBySection.forEach { (section, lines) ->
                val original = lines.firstOrNull { line -> line.text.count { it.isLetter() } >= 6 } ?: return@forEach
                val quote = if (original.text.length > 500) sourcePracticePrefix(original.text, 480) else original.text
                candidates += SourcePracticeQuestion(
                    stableId("recall-fragment", sourceRevision, section.sectionId, quote),
                    SourcePracticeKind.RECALL,
                    "根据这段原文写出要点：\n\n" + quote,
                    answers = listOf(quote),
                    analysis = explained("请只依据这段原文核对关键要点，再自行标记掌握情况。", original.explanation),
                    sourceQuotes = listOf(quote),
                    sourceLabel = section.title,
                    sectionIds = listOf(section.sectionId),
                )
            }
        }
        return selectSourceQuestions(candidates.distinctBy { normalize(it.stem) }, MAX_SOURCE_CANDIDATES)
    }

    internal fun selectSourceQuestions(candidates: List<SourcePracticeQuestion>, limit: Int): List<SourcePracticeQuestion> {
        require(limit >= 0)
        val bySection = candidates.groupBy { it.sectionIds.firstOrNull() }
        return buildList {
            var row = 0
            while (size < limit) {
                val next = bySection.values.mapNotNull { it.getOrNull(row) }
                if (next.isEmpty()) break
                addAll(next.take(limit - size))
                row++
            }
        }
    }

    internal fun sourceBatch(
        title: String,
        sourceRevision: String,
        selected: List<SourcePracticeQuestion>,
        sectionCount: Int,
        complete: Boolean,
    ): SourcePractice {
        val covered = selected.flatMap { it.sectionIds }.distinct().size
        return SourcePractice(
            id = stableId("source-practice", sourceRevision,
                selected.joinToString { it.id }),
            title = title,
            sourceRevision = sourceRevision,
            origin = SourcePracticeOrigin.SOURCE_TEXT,
            questions = selected,
            coverageNote = (if (complete) "已从当前原文提取 " else "已从当前可读取的部分原文提取 ") +
                selected.size + " 题，涉及 " + covered + "/" + sectionCount +
                " 节。练习只核对这些要点，简答题自行对照参考答案。",
        )
    }

    /** Adapts the actual private draft already checked by QuizReviewService.load. */
    fun fromReviewDraft(title: String, session: QuizReviewService.Session): SourcePractice {
        val refs = session.sources.associateBy { it.evidenceId }
        val questions = session.draft.items.mapIndexedNotNull { index, item ->
            val assessment = session.supportAssessments.getOrNull(index) ?: return@mapIndexedNotNull null
            if (assessment.state == QuizSupportState.UNSUPPORTED) return@mapIndexedNotNull null
            val sources = item.evidenceIds.mapNotNull(refs::get)
            if (sources.size != item.evidenceIds.size || sources.isEmpty()) return@mapIndexedNotNull null
            val options = item.options.map { it.replace(optionPrefix, "").trim() }
            val answers = if (item.type == QuizItemType.CHOICE) {
                val letter = item.choiceAnswerLetter?.singleOrNull() ?: return@mapIndexedNotNull null
                listOf(options.getOrNull(letter - 'A') ?: return@mapIndexedNotNull null)
            } else item.referencePoints
            if (answers.isEmpty() || (item.type == QuizItemType.CHOICE &&
                    (options.size < 2 || options.distinct().size != options.size))) return@mapIndexedNotNull null
            SourcePracticeQuestion(
                id = stableId("ai-review", session.reference.draftSha256, item.ordinal.toString()),
                kind = if (item.type == QuizItemType.CHOICE) SourcePracticeKind.CHOICE else SourcePracticeKind.RECALL,
                stem = item.stem,
                options = if (item.type == QuizItemType.CHOICE) options else emptyList(),
                answers = answers,
                analysis = item.analysis.ifBlank { "请对照下方原文核对。" },
                sourceQuotes = sources.map { it.quote }.distinct(),
                sourceLabel = sources.map { it.sourceTitleSnapshot }.distinct().joinToString(" · "),
                sectionIds = sources.map { it.sectionId }.distinct(),
                score = item.score,
            )
        }
        val excluded = session.draft.items.size - questions.size
        return SourcePractice(
            stableId("ai-review-practice", session.draft.sourceRevision, session.reference.draftSha256),
            title,
            session.draft.sourceRevision,
            SourcePracticeOrigin.AI_REVIEW_DRAFT,
            questions,
            "这份 AI 学习草稿保留实际答案、解析和原文引用，供个人自测；请核对原文，简答自行评价。"
                + if (excluded > 0) " 已跳过 " + excluded + " 道原文不支持的题目。" else "",
            excluded,
        )
    }

    /** Recall responses need human self-review and must never be graded as zero automatically. */
    fun isAnswerCorrect(question: SourcePracticeQuestion, response: String): Boolean? {
        if (question.kind == SourcePracticeKind.RECALL) return null
        val answer = normalize(response).lowercase(Locale.ROOT)
        return answer.isNotEmpty() && question.answers.any { normalize(it).lowercase(Locale.ROOT) == answer }
    }

    private data class SourceSentence(val text: String, val explanation: String)

    private fun sourceSentences(section: SourcePracticeSection): List<SourceSentence> {
        val bodies = mutableListOf<Pair<String, String>>()
        var body: StringBuilder? = null
        var sourceEnd = -1
        var explanation = ""
        fun finishBody() {
            body?.let { bodies += it.toString() to explanation }
            body = null
        }
        SourcePracticeText.segments(section.text).forEach { segment ->
            val previous = body?.lastOrNull { !it.isWhitespace() }
            val continuesSentence = body != null && sourceEnd == segment.sourceStart && explanation.isBlank() &&
                previous != null && previous !in "。！？；\r\n"
            if (!continuesSentence) {
                finishBody()
                body = StringBuilder()
            }
            body!!.append(segment.text)
            sourceEnd = segment.sourceEnd
            explanation = segment.explanation
        }
        finishBody()
        return bodies.flatMapIndexed { bodyIndex, (text, bodyExplanation) ->
            val lines = sentences(text)
            val finalExplanation = bodyExplanation.ifBlank {
                section.explanation.takeIf { bodyIndex == bodies.lastIndex }.orEmpty()
            }
            lines.mapIndexed { index, line ->
                SourceSentence(line, finalExplanation.takeIf { index == lines.lastIndex }.orEmpty())
            }
        }
    }

    private fun explained(base: String, explanation: String): String =
        if (explanation.isBlank()) base else base + "\n\n原文解析：\n" + explanation

    internal fun sourceFingerprint(question: SourcePracticeQuestion): String =
        stableId(*question.sourceQuotes.map(::normalize).toTypedArray())

    internal fun stemFingerprint(question: SourcePracticeQuestion): String = stableId(normalize(question.stem))

    private fun sentences(text: String): List<String> = text
        .split(Regex("(?<=[。！？；])|[\\r\\n]+"))
        .map { it.trim().replace(Regex("^(?:[-*+]\\s+|\\d+[.)、]\\s*)"), "") }
        .filter { it.isNotBlank() }

    private fun normalize(text: String): String = text.replace(whitespace, "")
    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }

    private fun stableId(vararg values: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            result[index * 2] = hex[value ushr 4]
            result[index * 2 + 1] = hex[value and 15]
        }
        return String(result)
    }
}
