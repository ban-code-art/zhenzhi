package com.example.myapplication.data.knowledge.lexical

/**
 * FTS4 matchinfo 解码与字段加权排序（设计 §7.2）。
 *
 * matchinfo 使用 'pcx' 格式（由查询侧声明；FTS4 在 SQL 里用 matchinfo(fts,'pcx')）。
 * 本类只做纯计算：给定 phrase 计数与列命中数，计算
 *   score = Σ_phrase (phraseCount * phraseWeight) + Σ_col (colHits * colWeight)
 * 权重初值：title > heading > body；用户 scope 不参与打分。
 */
object FtsRanker {
    const val COLUMN_TITLE = 0
    const val COLUMN_HEADING = 1
    const val COLUMN_BODY = 2
    const val COLUMN_USER_SCOPE = 3

    private const val PHRASE_WEIGHT = 1.0
    private const val TITLE_WEIGHT = 8.0
    private const val HEADING_WEIGHT = 4.0
    private const val BODY_WEIGHT = 1.0

    data class MatchInfo(
        val phraseCount: Int,
        val columnCount: Int,
        /** phraseCount * columnCount 的命中数，行主序。 */
        val hits: IntArray,
    )

    data class Score(
        val value: Double,
        val titleHits: Int,
        val headingHits: Int,
        val bodyHits: Int,
    )

    /**
     * 解析 FTS4 matchinfo 'pcx' 输出。
     *
     * 实际布局（SQLite 实测确认）：
     *   [p, c, 每 phrase 每 column 三元组 (rowHits, totalHitsAcrossRows, rowsContaining)]
     * 共 2 + p*c*3 个整数。
     *
     * 打分只使用 rowHits（当前行命中数）：totalHits/rowsContaining 是全表统计，
     * 其他用户写入同词内容会让它们变化，不能进排序（否则 B 数据会改变 A 的 rank）。
     */
    fun parsePcx(bytes: ByteArray): MatchInfo? {
        if (bytes.size < 8) return null
        val ints = IntArray(bytes.size / 4)
        for (i in ints.indices) {
            ints[i] = (bytes[i * 4].toInt() and 0xFF) or
                ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
        val phraseCount = ints[0]
        val columnCount = ints[1]
        if (phraseCount <= 0 || columnCount <= 0) return null
        val expected = 2 + phraseCount * columnCount * 3
        if (ints.size < expected) return null
        val rowHits = IntArray(phraseCount * columnCount)
        for (i in rowHits.indices) {
            rowHits[i] = ints[2 + i * 3]
        }
        return MatchInfo(phraseCount, columnCount, rowHits)
    }

    fun score(info: MatchInfo): Score {
        var value = 0.0
        var titleHits = 0
        var headingHits = 0
        var bodyHits = 0
        for (phrase in 0 until info.phraseCount) {
            for (column in 0 until info.columnCount) {
                val hits = info.hits[phrase * info.columnCount + column]
                if (hits <= 0) continue
                value += hits * PHRASE_WEIGHT
                when (column) {
                    COLUMN_TITLE -> {
                        titleHits += hits
                        value += hits * TITLE_WEIGHT
                    }
                    COLUMN_HEADING -> {
                        headingHits += hits
                        value += hits * HEADING_WEIGHT
                    }
                    COLUMN_BODY -> {
                        bodyHits += hits
                        value += hits * BODY_WEIGHT
                    }
                    else -> Unit
                }
            }
        }
        return Score(value, titleHits, headingHits, bodyHits)
    }

    /** 便捷：字节 -> 分数；无法解析返回零分。 */
    fun scoreOf(matchInfoBytes: ByteArray): Score =
        parsePcx(matchInfoBytes)?.let(::score) ?: Score(0.0, 0, 0, 0)
}
