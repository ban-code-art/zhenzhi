package com.example.myapplication.data.quiz

/** Body text is always one continuous, unmodified range of verified source text. */
internal data class SourcePracticeTextSegment(
    val text: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val explanation: String = "",
)

/** Removes labelled answer metadata before any question or distractor is selected. */
internal object SourcePracticeText {
    private const val MAX_EXPLANATION_CHARS = 2_000
    private const val LABELS =
        "(?:参考答案与解析|参考答案及解析|答案与解析|答案及解析|正确答案|标准答案|参考答案|" +
            "答案解析|答案分析|参考解析|试题解析|题目解析|解题思路|参考解答|答案|解析|解答|详解)"
    private const val LABEL_SPACE = "[\\t \\u3000]*+"
    private val metadataMarker = Regex(
        "(?:【" + LABEL_SPACE + LABELS + LABEL_SPACE + "】" + LABEL_SPACE + "[:：]?|" +
            "\\[" + LABEL_SPACE + LABELS + LABEL_SPACE + "\\]" + LABEL_SPACE + "[:：]?|" +
            LABELS + LABEL_SPACE + "[:：])",
    )
    private const val STRONG_DEFINITION = "([^：:，,。！？；;\\s\\u3000]{1,32}?)(?:是指|定义为|指的是)"
    private val firstStrongDefinition = Regex("^(?:[-*+][\\t ]++|\\d{1,5}[.)、][\\t ]++)?" + STRONG_DEFINITION)
    private val adjacentStrongDefinition = Regex("(?<=[\\t \\u3000])" + STRONG_DEFINITION)
    private val genericConcepts = setOf("这", "它", "其", "我", "本章", "本节", "本文", "这里", "例如", "其中", "它们")

    fun segments(sourceText: String): List<SourcePracticeTextSegment> {
        if (sourceText.isEmpty()) return emptyList()
        val body = mutableListOf<PendingSegment>()
        var explanationContinues = false

        fun appendExplanation(start: Int, end: Int) {
            val previous = body.lastOrNull() ?: return
            val from = trimStart(sourceText, start, end)
            val to = trimEnd(sourceText, from, end)
            if (from >= to) return
            val separator = if (previous.explanation.isEmpty()) 0 else 1
            val available = MAX_EXPLANATION_CHARS - previous.explanation.length - separator
            if (available <= 0) return
            val captureEnd = safeEnd(sourceText, from + minOf(available, to - from))
            if (captureEnd <= from) return
            if (separator != 0) previous.explanation.append('\n')
            previous.explanation.append(sourceText, from, captureEnd)
        }

        fun emitBody(start: Int, end: Int) {
            val from = trimStart(sourceText, start, end)
            val to = trimEnd(sourceText, from, end)
            if (from >= to) return
            val text = sourceText.substring(from, to)
            val first = firstStrongDefinition.find(text)
            if (first == null || !isSpecificConcept(first.groupValues[1])) {
                body += PendingSegment(from, to)
                return
            }
            // PDF br becomes whitespace. Restore only explicit definitions; bare 是/指 is ambiguous.
            val firstConceptStart = first.groups[1]!!.range.first
            var currentStart = from
            adjacentStrongDefinition.findAll(text).forEach { next ->
                if (next.range.first > firstConceptStart && isSpecificConcept(next.groupValues[1])) {
                    val nextStart = from + next.range.first
                    val previousEnd = trimEnd(sourceText, currentStart, nextStart)
                    if (previousEnd > currentStart) body += PendingSegment(currentStart, previousEnd)
                    currentStart = nextStart
                }
            }
            if (currentStart < to) body += PendingSegment(currentStart, to)
        }

        fun consumeSentence(start: Int, end: Int) {
            val from = trimStart(sourceText, start, end)
            val to = trimEnd(sourceText, from, end)
            if (from >= to) return
            val sentence = sourceText.substring(from, to)
            // Flattened PDF whitespace cannot prove that an explanation has ended.
            // Only an explicit definition at the next sentence start resumes body; original newlines reset below.
            if (explanationContinues) {
                val nextDefinition = firstStrongDefinition.find(sentence)
                if (nextDefinition == null || !isSpecificConcept(nextDefinition.groupValues[1])) {
                    appendExplanation(from, to)
                    return
                }
            }
            explanationContinues = false
            val marker = metadataMarker.find(sentence)
            if (marker == null) {
                emitBody(from, to)
            } else {
                val metadataStart = from + marker.range.first
                emitBody(from, metadataStart)
                appendExplanation(metadataStart, to)
                // Classify the complete suffix before applying the display limit, never reusing its tail.
                explanationContinues = true
            }
        }

        var start = 0
        sourceText.forEachIndexed { index, char ->
            when (char) {
                '\r', '\n' -> {
                    consumeSentence(start, index)
                    explanationContinues = false
                    start = index + 1
                }
                '。', '！', '？', '；' -> {
                    consumeSentence(start, index + 1)
                    start = index + 1
                }
            }
        }
        consumeSentence(start, sourceText.length)
        return body.map { segment ->
            SourcePracticeTextSegment(
                text = sourceText.substring(segment.start, segment.end),
                sourceStart = segment.start,
                sourceEnd = segment.end,
                explanation = segment.explanation.toString(),
            )
        }
    }

    private data class PendingSegment(val start: Int, val end: Int, val explanation: StringBuilder = StringBuilder())

    private fun isSpecificConcept(concept: String): Boolean = concept !in genericConcepts &&
        !concept.startsWith("当") && !concept.startsWith("如果") && !concept.startsWith("因此")

    private fun trimStart(text: String, start: Int, end: Int): Int {
        var from = start
        while (from < end && text[from].isWhitespace()) from++
        return from
    }

    private fun trimEnd(text: String, start: Int, end: Int): Int {
        var to = end
        while (to > start && text[to - 1].isWhitespace()) to--
        return to
    }

    private fun safeEnd(text: String, end: Int): Int = if (end > 0 && end < text.length &&
        Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end - 1 else end
}
