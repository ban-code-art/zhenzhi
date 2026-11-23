package com.example.myapplication.data.knowledge.index

/**
 * 索引代次的"生成口径"一致性判定（G-01）。
 *
 * 为什么需要它：`knowledge_documents` 的重建判定原本只看 `source_revision`。
 * 解析器升级（本期给 MOBI 补 HUFF/CDIC、给 PDF 写 `no-text` 标记、给 OCR 加 tile 等）
 * 并不会改变源 revision，于是旧代次索引会一直保留旧解析结果，表现为
 * "书更新了、索引还是老内容"。这里把判定收敛成纯函数，便于单测锁定。
 *
 * 规则（宁缺不假）：
 *  - 期望版本为空（未配置/调用方没给）→ 不重建；
 *  - 存储版本为空（schema 升级前的历史行）→ 不重建，否则每次唤醒都会重排全库；
 *  - 任一版本不一致 → 重建，代次由调用方递增（走既有 LOCAL 任务路径）。
 */
object IndexVersioning {

    const val REASON_PARSER_VERSION_CHANGED = "PARSER_VERSION_CHANGED"
    const val REASON_CHUNKER_VERSION_CHANGED = "CHUNKER_VERSION_CHANGED"
    const val REASON_BOTH_CHANGED = "PARSER_AND_CHUNKER_CHANGED"

    data class Verdict(val needsRebuild: Boolean, val reason: String? = null) {
        companion object {
            val UP_TO_DATE = Verdict(false, null)
        }
    }

    fun evaluate(
        storedExtractorVersion: String?,
        storedChunkerVersion: String?,
        expectedExtractorVersion: String,
        expectedChunkerVersion: String,
    ): Verdict {
        val expectedParser = expectedExtractorVersion.trim()
        val expectedChunker = expectedChunkerVersion.trim()
        if (expectedParser.isEmpty() || expectedChunker.isEmpty()) return Verdict.UP_TO_DATE
        val storedParser = storedExtractorVersion?.trim()?.takeIf { it.isNotEmpty() }
        val storedChunker = storedChunkerVersion?.trim()?.takeIf { it.isNotEmpty() }
        if (storedParser == null || storedChunker == null) return Verdict.UP_TO_DATE
        val parserChanged = storedParser != expectedParser
        val chunkerChanged = storedChunker != expectedChunker
        return when {
            parserChanged && chunkerChanged -> Verdict(true, REASON_BOTH_CHANGED)
            parserChanged -> Verdict(true, REASON_PARSER_VERSION_CHANGED)
            chunkerChanged -> Verdict(true, REASON_CHUNKER_VERSION_CHANGED)
            else -> Verdict.UP_TO_DATE
        }
    }
}
