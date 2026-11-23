package com.example.myapplication.data.review

internal data class ReviewQuestion(val prompt: String, val answer: String)

internal fun ReviewItemEntity.hasLegacyGeneratedPrompt(): Boolean = when (sourceNamespace) {
    "annotation" -> prompt.startsWith("回想你在「") &&
        prompt.substringBefore('\n').endsWith("」记录的要点") &&
        prompt.substringAfter('\n', "").let { it.isNotBlank() && answerSnapshot.startsWith(it) }
    "notebook", "notebook-excerpt" -> prompt == "回想「$sourceTitle」的笔记要点"
    else -> false
}

/**
 * Makes recall questions from facts already in the source. When a statement does not
 * expose a definition, it tests one concrete term with the rest as context.
 */
internal object ReviewQuestionBuilder {
    private const val CONTEXT_LIMIT = 1_200
    private const val STATEMENT_LIMIT = 340
    private const val BLANK = "____"

    private val citation = Regex("\\[([^]\\n]+)]\\([^\\n)]*\\)")
    private val markdownPrefix = Regex("^(?:#{1,6}\\s+|[-*+]\\s+|>\\s*)+")
    private val markdownCode = Regex("(?ms)^\\s*```[^\\n]*\\n.*?^\\s*```[^\\n]*$")
    private val sentenceBoundary = Regex("[。！？!?；;]+|\\.(?=\\s|$)|[\\r\\n]+")
    private val danglingStart = Regex("^(?:的|而(?:是|且|我们)|前面|下面|上面|我们|它|这(?:个|些|样|就|是)|那(?:个|些)|其(?:中|实|他)|也|都|并且|于是|因此|所以|但是|不过|然后|例如)")
    private val danglingEnd = Regex("(?:[,，、:：…]|省略|如下|一些|之类|例如|包括|意味着|学习的是)$")
    private val definition = Regex("^(.+?)(?:是指|指的是|属于|是一种|是用于|用于|是|指)(.+)$")
    private val bookDefinition = Regex("^(.{2,40}?)[（(]([^()（）]{4,65})[）)]$")
    private val englishTerm = Regex("(?<![A-Za-z0-9])(?:[A-Z]{2,}[A-Za-z0-9]*(?:[-.][A-Za-z0-9]+)*|[A-Z][a-z0-9]+[A-Za-z0-9]*(?:[-.][A-Za-z0-9]+)*|[a-z]+[A-Z][A-Za-z0-9]+)(?![A-Za-z0-9])")
    private val chineseTerm = Regex("「([^」]{2,24})」|“([^”]{2,24})”|【([^】]{2,24})】|《([^》]{2,24})》")
    private val quantity = Regex("[0-9]+(?:\\.[0-9]+)?(?:%|毫秒|秒|分钟|小时|字节|位|个|次|级|种|步|天|年|KB|MB|GB)")
    private val detail = Regex("(?:包括|包含|支持|采用|分为|需要|负责|执行|保持|保存|验证|存储|发送|接收|管理|提高|降低|确保|限制|避免|检测|返回|提供|依赖)([^，,。；：:]{2,22})")
    private val headingTopic = Regex("^(?:第[一二三四五六七八九十百0-9]+[章节篇]\\s*|[0-9.]+\\s*)")

    fun fromSavedContext(prefix: String?, quote: String, suffix: String?, canonicalStart: Int): ReviewQuestion? {
        val before = prefix.orEmpty()
        val after = suffix.orEmpty()
        val start = if (canonicalStart <= before.length) 0 else
            (sentenceBoundary.findAll(before).lastOrNull()?.range?.last ?: return null) + 1
        val throughQuote = before + quote
        val context = throughQuote + after
        val stop = sentenceBoundary.findAll(context, start).lastOrNull()?.range?.last ?: return null
        val selectedStart = before.length - start
        val selectedEnd = minOf(throughQuote.length, stop + 1) - start
        return fromPassage(context.substring(start, stop + 1), selectedStart until selectedEnd)
    }

    fun fromPassage(passage: String, selection: IntRange? = null): ReviewQuestion? {
        if (selection == null) {
            return statements(markdownCode.replace(passage, "")).firstNotNullOfOrNull { question(it.text) }
        }
        if (selection.isEmpty() || selection.first < 0 || selection.last >= passage.length) return null
        val start = maxOf(0, selection.first - CONTEXT_LIMIT)
        val end = minOf(passage.length, selection.last + 1 + CONTEXT_LIMIT)
        val statements = statements(passage.substring(start, end))
        return statements.withIndex().asSequence()
            .filter { (index, _) -> (start == 0 || index != 0) && (end == passage.length || index != statements.lastIndex) }
            .map { (_, candidate) ->
                val candidateStart = candidate.start + start
                val candidateEnd = candidate.end + start
                val overlap = (minOf(selection.last + 1, candidateEnd) - maxOf(selection.first, candidateStart)).coerceAtLeast(0)
                val distance = maxOf(selection.first - candidateEnd, candidateStart - selection.last, 0)
                RankedStatement(candidate.text, overlap, distance)
            }
            .filter { it.distance <= 160 }
            .sortedWith(compareByDescending<RankedStatement> { it.overlap > 0 }.thenBy { it.distance }.thenByDescending { it.overlap })
            .firstNotNullOfOrNull { question(it.text) }
    }

    private data class Statement(val text: String, val start: Int, val end: Int)
    private data class RankedStatement(val text: String, val overlap: Int, val distance: Int)

    private fun statements(passage: String): List<Statement> = buildList {
        var start = 0
        for (separator in sentenceBoundary.findAll(passage)) {
            val end = separator.range.first
            if (end > start && separator.value.none { it in "?？" }) {
                add(Statement(passage.substring(start, end), start, end))
            }
            start = separator.range.last + 1
        }
        if (start < passage.length) add(Statement(passage.substring(start), start, passage.length))
    }

    private fun question(statement: String): ReviewQuestion? {
        val text = headingTopic.replaceFirst(
            markdownPrefix.replaceFirst(citation.replace(statement.trim(), "$1"), "").replace("`", ""),
            "",
        ).trim()
        if (text.length !in 8..STATEMENT_LIMIT || danglingStart.containsMatchIn(text) || danglingEnd.containsMatchIn(text)) return null
        bookDefinition.matchEntire(text)?.let { match ->
            val (subject, meaning) = match.destructured
            if (meaning.endsWith("技术") || meaning.endsWith("机制") || meaning.endsWith("协议") || meaning.endsWith("方法")) {
                return ReviewQuestion("文中所说的「${meaning}」指什么？", "${subject.trim()}是${meaning}。")
            }
        }
        definition.matchEntire(text)?.let { match ->
            val (rawSubject, rawFact) = match.destructured
            val subject = rawSubject.trim()
            val fact = rawFact.trim()
            if (subject.length in 2..35 && subject.none { it in "，,:：。！？" } &&
                fact.length >= 4 && !danglingEnd.containsMatchIn(fact) &&
                !fact.contains(subject, ignoreCase = true) &&
                !subject.endsWith("就") && !subject.endsWith("不") && !subject.endsWith("也")
            ) {
                val relation = text.substring(rawSubject.length, text.length - rawFact.length)
                val prompt = when (relation.trim()) {
                    "用于", "是用于" -> "「${subject}」有什么用途？"
                    "属于" -> "「${subject}」属于哪一类？"
                    else -> "「${subject}」是什么？"
                }
                return ReviewQuestion(prompt, "${text}。")
            }
        }
        val blank = sequenceOf(
            englishTerm.findAll(text).map { it.range },
            chineseTerm.findAll(text).map { it.range.first + 1 until it.range.last },
            quantity.findAll(text).map { it.range },
            detail.findAll(text).mapNotNull { it.groups[1]?.range },
        ).flatten().firstOrNull { range ->
            val key = text.substring(range)
            key.length >= 2 && text.length - key.length >= 10 &&
                !text.removeRange(range).contains(key, ignoreCase = true)
        } ?: return null
        val missing = text.substring(blank)
        val context = text.replaceRange(blank, BLANK)
        return ReviewQuestion("下面这句话中的空白处应填什么？\n${context}。", "${missing}\n\n原文：${text}。")
    }
}
