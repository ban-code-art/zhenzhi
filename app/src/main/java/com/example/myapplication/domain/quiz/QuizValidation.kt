package com.example.myapplication.domain.quiz

/**
 * 校验错误码（第四期详细设计 §14 校验表与 06-验证门禁 FX-15/Q-01/Q-02）。
 *
 * 顺序即校验顺序：结构/类型 → 题数题型 → score → 选项/答案 → rubric →
 * 题干/知识点重复 → evidence/source → 全章覆盖。任何层失败都要能定位到
 * 具体 item/field，供定向修复；不允许“裁掉不合格题后当 10 题通过”。
 */
enum class QuizValidationCode {
    /** JSON 结构/字段类型/枚举值不合法。 */
    STRUCTURE_INVALID,

    /** 题数或题型与模板不一致（7+3、自定义 5–10+3、离线卷声明数）。 */
    COUNT_MISMATCH,

    /** 分值分配与模板/实际题目不一致（各题分值、总分、裁剪后总分）。 */
    SCORE_SUM_MISMATCH,

    /** 选项非法：数量/重复/空文本/稳定 A/B/C/D 标识缺失或错位。 */
    OPTIONS_INVALID,

    /** 答案越界或与选项标识不匹配。 */
    ANSWER_OUT_OF_RANGE,

    /** rubric 非法：要点数不在 3–5、权重非正、权重和≠题目分值、要点为空。 */
    RUBRIC_INVALID,

    /** 同卷题干或知识点重复（FX-15“同卷两题同知识点重复”）。 */
    DUPLICATE_ITEM,

    /** 出处无法绑定/伪造编号/quote 不符/revision 不一致。 */
    EVIDENCE_UNVERIFIED,

    /** 全章覆盖不足：计划未覆盖的核心知识点、或覆盖比例低于阈值。 */
    COVERAGE_INSUFFICIENT,

    /** 固定 sourceRevision 已变化：终止或重启新 draft，不拿旧结果充数。 */
    SOURCE_CHANGED,
}

/**
 * 一条可定位的校验问题。
 *
 * [itemIndex] 为 0-based 的 items 下标（整卷级问题为 null），[field] 是出问题的
 * 字段名（如 "score"、"options[2]"、"rubric[1].weight"）。
 */
data class ValidationIssue(
    val code: QuizValidationCode,
    val itemIndex: Int? = null,
    val field: String? = null,
    val message: String,
) {
    /**
     * 可定位路径，例如 items[3].score / items[*].items。
     *
     * 注意：不能用 `get()` 访问器——`field` 是 Kotlin 软关键字，在访问器里会被当成
     * 后备字段（backing field），从而要求该属性有初始化器。这里用初始化表达式，
     * 并用 `this@ValidationIssue.field` 明确引用构造属性。
     */
    val path: String = buildString {
        append("items[")
        append(itemIndex?.toString() ?: "*")
        append("].")
        append(this@ValidationIssue.field ?: "*")
    }

    override fun toString(): String = code.name + " " + path + " " + message
}

/** 校验结果：只有 issues 为空才允许发布 READY。 */
data class QuizValidationReport(
    val issues: List<ValidationIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()

    val codes: List<QuizValidationCode> get() = issues.map { it.code }

    fun has(code: QuizValidationCode): Boolean = issues.any { it.code == code }

    fun issuesFor(itemIndex: Int?): List<ValidationIssue> = issues.filter { it.itemIndex == itemIndex }

    /** 定向修复提示：按 item 聚合，供生成器只重写失败的题。 */
    fun repairTargets(): Map<Int?, List<String>> = issues.groupBy({ it.itemIndex }, { it.path })

    fun summary(): String = if (isValid) "VALID" else issues.joinToString("; ")

    companion object {
        val VALID = QuizValidationReport(emptyList())
    }
}
