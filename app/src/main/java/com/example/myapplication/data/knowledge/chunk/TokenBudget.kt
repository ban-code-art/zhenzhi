package com.example.myapplication.data.knowledge.chunk

/**
 * Token 估算与预算（设计 §5.2）。
 *
 * - 默认子块目标 384、最小 128、最大 768、重叠约 48；父块 1024–2048。
 * - 未知 tokenizer 采用保守 UTF-8 byte 界限（每字节 1 token 上界），宁可少装不超窗口。
 * - CJK 字符按 1 token/字，拉丁按约 4 字符/token 估算；这是预算用途的保守近似，
 *   不是精确 BPE。真实模型上限由调用方在发送前再次检查。
 */
enum class TokenizerKind {
    /** 中文/拉丁混合的保守近似。 */
    CJK_AWARE,

    /** 未知 tokenizer：UTF-8 字节数即 token 上界。 */
    UTF8_BYTES,
}

object TokenEstimator {
    fun estimate(text: String, kind: TokenizerKind = TokenizerKind.CJK_AWARE): Int = when (kind) {
        TokenizerKind.UTF8_BYTES -> text.toByteArray(Charsets.UTF_8).size
        TokenizerKind.CJK_AWARE -> estimateCjkAware(text)
    }

    private fun estimateCjkAware(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0
        var latinRun = 0
        for (ch in text) {
            when {
                isCjk(ch) -> {
                    tokens += latinRunToTokens(latinRun)
                    latinRun = 0
                    tokens += 1
                }
                ch.isLetterOrDigit() -> latinRun += 1
                else -> {
                    tokens += latinRunToTokens(latinRun)
                    latinRun = 0
                }
            }
        }
        tokens += latinRunToTokens(latinRun)
        return tokens.coerceAtLeast(1)
    }

    private fun latinRunToTokens(chars: Int): Int = if (chars <= 0) 0 else (chars + 3) / 4

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }
}

data class ChunkBudget(
    val childTarget: Int = 384,
    val childMin: Int = 128,
    val childMax: Int = 768,
    val childOverlap: Int = 48,
    val parentTarget: Int = 1024,
    val parentMax: Int = 2048,
    val tokenizer: TokenizerKind = TokenizerKind.CJK_AWARE,
) {
    init {
        require(childMin >= 1) { "childMin must be >= 1" }
        require(childMin <= childTarget) { "childMin must be <= childTarget" }
        require(childTarget <= childMax) { "childTarget must be <= childMax" }
        require(childOverlap >= 0) { "childOverlap must be >= 0" }
        require(childOverlap < childMin) { "childOverlap must be < childMin" }
        require(parentTarget >= childMax) { "parentTarget must be >= childMax" }
        require(parentTarget <= parentMax) { "parentTarget must be <= parentMax" }
    }

    fun estimate(text: String): Int = TokenEstimator.estimate(text, tokenizer)

    companion object {
        val DEFAULT = ChunkBudget()

        /** 未知 tokenizer：保守 UTF-8 字节界限。 */
        val CONSERVATIVE = ChunkBudget(tokenizer = TokenizerKind.UTF8_BYTES)
    }
}
