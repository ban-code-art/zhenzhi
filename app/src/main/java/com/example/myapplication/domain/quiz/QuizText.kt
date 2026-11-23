package com.example.myapplication.domain.quiz

/**
 * 文本归一化：校验去重、rubric 匹配、否定判断共用一套规则，避免各处各写一份。
 *
 * 规则：转小写 → 去掉空白与中英文标点。保留字母/数字/CJK 主体，
 * 使“A. 阅读、标注和复习”与“阅读 标注和复习”可比较。
 */
object QuizText {
    private val IGNORED = Regex("[\\s\\p{Punct}，。、；：？！“”‘’（）【】《》〈〉…——·「」『』]")

    fun normalize(text: String): String = text.lowercase().replace(IGNORED, "")

    fun isBlankOrPlaceholder(text: String?): Boolean {
        val normalized = text?.trim().orEmpty()
        return normalized.isEmpty() || normalized == "-" || normalized == "null"
    }
}
