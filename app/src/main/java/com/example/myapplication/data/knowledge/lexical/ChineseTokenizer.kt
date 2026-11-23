package com.example.myapplication.data.knowledge.lexical

/**
 * 中文/拉丁混合分词（设计 §7.2）。
 *
 * unicode61 tokenizer 把连续 CJK 视为一个 token，因此写入 FTS 前必须做
 * 双字（bigram）切分；查询时用同样的 bigram 序列构造 phrase，保证词面精确。
 *
 * - CJK 连续段输出全部 bigram；单字输出单字；
 * - 词典术语（经络/腧穴名及同义词）额外作为整词 token 写入，用于精确术语命中；
 * - 拉丁术语小写归一（连字符/下划线视为分隔，保留数字）；
 * - MATCH 表达式只由本类生成：token 用双引号包裹并转义内部引号，
 *   绝不把用户原始字符串拼进 SQL。
 */
object ChineseTokenizer {
    /** 术语词典：长词优先匹配。开发词典，正式内容审核后扩充。 */
    val TERMS: Set<String> = setOf(
        "手太阴肺经", "手阳明大肠经", "足阳明胃经", "足太阴脾经",
        "手少阴心经", "手太阳小肠经", "足太阳膀胱经", "足少阴肾经",
        "手厥阴心包经", "手少阳三焦经", "足少阳胆经", "足厥阴肝经",
        "任脉", "督脉", "十四经穴", "经外奇穴", "阿是穴",
        "经络", "腧穴", "经脉", "穴位", "针灸", "针刺", "艾灸", "灸法",
        "体表标志法", "骨度分寸法", "手指同身寸法", "简便取穴法",
        "合谷", "内关", "足三里", "三阴交", "太冲", "阳陵泉", "委中",
        "风池", "百会", "关元", "气海", "神门", "曲池", "肩髃",
        "得气", "行针", "留针", "补泻", "配穴", "取穴", "主治", "归经",
    )

    /** 同义词扩展：查询词 -> 明确词典别名（不把模型猜测写入）。 */
    val SYNONYMS: Map<String, List<String>> = mapOf(
        "足三里" to listOf("足三里穴", "下陵"),
        "合谷" to listOf("合谷穴", "虎口"),
        "内关" to listOf("内关穴"),
        "针灸" to listOf("针刺", "艾灸"),
        "腧穴" to listOf("穴位", "输穴"),
        "经络" to listOf("经脉", "脉络"),
    )

    private val CJK_RANGES = listOf(
        0x4E00..0x9FFF,
        0x3400..0x4DBF,
        0xF900..0xFAFF,
        0x3040..0x30FF,
        0xAC00..0xD7AF,
    )

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return CJK_RANGES.any { code in it }
    }

    /**
     * 分词：词典术语 + 拉丁归一 token + CJK bigram 序列。
     *
     * W3-12 基准修复（F2）：CJK bigram **必须保留原始位置序列，不得去重**。
     * 原实现用 `linkedSetOf` 收集 token，重复 bigram 会被折叠；而查询侧
     * [toMatchExpression] 生成的是相邻 phrase（如“甲乙甲乙”→“甲乙 乙甲 甲乙”），
     * 索引里丢失第二个“甲乙”后 phrase 永远不命中（实测 10k 库中该查询 0 命中，
     * golden 实有 4 个 chunk；见报告 §8 F2）。
     * 现改为 ArrayList 顺序追加；FTS 派生索引重建后生效。
     */
    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<String>(text.length + TERMS.size + 8)
        // 词典术语整词（TERMS 是 Set，同一术语只加一次；位于 bigram 序列之前，
        // 不破坏纯 CJK 查询的相邻 phrase 匹配）
        TERMS.forEach { term -> if (text.contains(term)) tokens += term }
        // 拉丁/数字（保序，同样是 phrase 的组成部分）
        latinTokenize(text).forEach(tokens::add)
        // CJK bigram：保序、不过滤重复
        cjkBigrams(text).forEach(tokens::add)
        return tokens
    }

    /** 写入 FTS 的索引文本：token 以空格连接。 */
    fun toIndexText(text: String): String = tokenize(text).joinToString(" ")

    /**
     * 生成安全 MATCH 表达式：每个查询词转为 bigram phrase（或单 token），
     * 词之间 AND；同义词扩展为 OR 分支。无法分词时返回 null。
     */
    fun toMatchExpression(query: String, expandSynonyms: Boolean = true): String? {
        val words = splitQueryWords(query)
        if (words.isEmpty()) return null
        val branches = mutableListOf<String>()
        words.forEach { word ->
            val variants = mutableListOf(word)
            if (expandSynonyms) {
                SYNONYMS[word]?.let(variants::addAll)
                // 反向：别名 -> 规范词
                SYNONYMS.forEach { (canonical, aliases) ->
                    if (aliases.contains(word)) variants += canonical
                }
            }
            val variantExprs = variants.distinct().mapNotNull(::wordToPhrase)
            if (variantExprs.isNotEmpty()) {
                branches += if (variantExprs.size == 1) variantExprs[0] else "(${variantExprs.joinToString(" OR ")})"
            }
        }
        if (branches.isEmpty()) return null
        return branches.joinToString(" AND ")
    }

    /**
     * 查询词切分：按空白/常见标点切，保留 CJK 与拉丁数字。
     * FTS 布尔操作符（OR/AND/NOT/NEAR）不作为用户查询词，避免改变查询语义。
     */
    fun splitQueryWords(query: String): List<String> = query
        .split(Regex("[\\s\\p{Punct}，。、；：！？（）【】《》“”‘’…—]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.uppercase() !in OPERATOR_WORDS }
        .take(MAX_QUERY_WORDS)

    private val OPERATOR_WORDS = setOf("OR", "AND", "NOT", "NEAR")

    private fun wordToPhrase(word: String): String? {
        val tokens = mutableListOf<String>()
        var latin = StringBuilder()
        var cjkRun = StringBuilder()

        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens += normalizeLatin(latin.toString())
                latin = StringBuilder()
            }
        }

        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            val run = cjkRun.toString()
            if (run.length == 1) tokens += run
            else for (i in 0 until run.length - 1) tokens += run.substring(i, i + 2)
            cjkRun = StringBuilder()
        }

        word.forEach { ch ->
            when {
                isCjk(ch) -> {
                    flushLatin()
                    cjkRun.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    latin.append(ch)
                }
                else -> {
                    flushCjk()
                    flushLatin()
                }
            }
        }
        flushCjk()
        flushLatin()
        if (tokens.isEmpty()) return null
        // FTS4 语义：引号内的空格分隔 token 是相邻 phrase；引号外的空格是 AND。
        // 因此多 token 必须放进同一个引号内，才能表达“相邻出现”。
        return quotePhrase(tokens)
    }

    private fun quotePhrase(tokens: List<String>): String {
        val escaped = tokens.joinToString(" ").replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun normalizeLatin(raw: String): String = raw.lowercase()

    private fun latinTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()
        text.forEach { ch ->
            when {
                ch.isLetterOrDigit() && !isCjk(ch) -> buffer.append(ch)
                else -> {
                    if (buffer.isNotEmpty()) {
                        tokens += normalizeLatin(buffer.toString())
                        buffer.clear()
                    }
                }
            }
        }
        if (buffer.isNotEmpty()) tokens += normalizeLatin(buffer.toString())
        return tokens
    }

    private fun cjkBigrams(text: String): List<String> {
        val bigrams = mutableListOf<String>()
        var run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            val value = run.toString()
            if (value.length == 1) bigrams += value
            else for (i in 0 until value.length - 1) bigrams += value.substring(i, i + 2)
            run = StringBuilder()
        }
        text.forEach { ch ->
            if (isCjk(ch)) run.append(ch) else flush()
        }
        flush()
        return bigrams
    }

    const val MAX_QUERY_WORDS = 16
}
