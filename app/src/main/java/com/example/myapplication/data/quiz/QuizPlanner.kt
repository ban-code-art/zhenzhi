package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizDifficulty
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizTemplateSpec
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 章节抽取质量：低 OCR 置信/抽取被禁用的段落不自动出题（设计 §7.2/§14）。 */
enum class SectionQuality { OK, LOW_OCR_CONFIDENCE, EXTRACTION_BLOCKED }

/** 固定 sourceRevision 下的章节结构输入（outline + 正文 + 知识点）。 */
data class SourceSection(
    val sectionId: String,
    val heading: String,
    val text: String,
    val order: Int = 0,
    val knowledgePoints: List<String> = emptyList(),
    val quality: SectionQuality = SectionQuality.OK,
    val fullyRead: Boolean = true,
)

data class QuizPlanRequest(
    val sourceRevision: String,
    val scopeJson: String = "{}",
    val sections: List<SourceSection>,
    val template: QuizTemplateSpec,
    /** 单次生成提示的正文预算；超预算必须记录 covered/uncovered，不能只说“已覆盖全章”。 */
    val maxPromptChars: Int = 24_000,
)

/** 计划中的一道题槽位（尚未生成内容）。 */
data class PlannedSlot(
    val ordinal: Int,
    val type: QuizItemType,
    val score: Int,
    val difficulty: QuizDifficulty,
    val knowledgePoint: String?,
    val sectionIds: List<String>,
)

/**
 * 生成计划（quiz_papers.generation_coverage_json 的领域视图）。
 *
 * [coveredSections]/[uncoveredSections] 记录预算内外的章节，是 [coveredRatio] 的分子与分母：
 * 「全章覆盖」按**章节（section）口径**判定——只问本次预算/抽取质量是否截断了全章
 * （设计 §7.2/§14：超预算必须记录已覆盖/未覆盖范围，不能拿 RAG top5 当“全章”）。
 * [coveredKnowledgePoints]/[uncoveredKnowledgePoints] 只服务题槽分配、查重与诊断
 * （后者统计计划可见的全部知识点，含预算截断章节，故不能当作“全章覆盖”的分母）：
 * 槽位数固定（chapter-quiz-v1 恒 10 题），真实教材动辄 30–100 个知识点，
 * 用知识点算覆盖率数学上必然低于阈值，不能拿它判断“全章覆盖”。
 * [sufficient] 为 false 时校验阶段必须给 COVERAGE_INSUFFICIENT，不能用 RAG top5 冒充全章。
 */
data class QuizGenerationPlan(
    val templateId: String,
    val templateVersion: String,
    val sourceRevision: String,
    val scopeJson: String,
    val slots: List<PlannedSlot>,
    val coveredSections: List<String>,
    val uncoveredSections: List<String>,
    val coveredKnowledgePoints: List<String>,
    val uncoveredKnowledgePoints: List<String>,
    val excludedSections: Map<String, String>,
    val truncated: Boolean,
    val notes: List<String>,
) {
    /** 计划可见章节数（预算内 + 预算外；低 OCR/禁止抽取被排除的章节不计入）。 */
    val totalSections: Int get() = coveredSections.size + uncoveredSections.size

    /** 章节覆盖率：本次预算是否装下了全章（[sufficient] 的唯一比例口径）。 */
    val coveredRatio: Double
        get() = if (totalSections == 0) 0.0 else coveredSections.size.toDouble() / totalSections

    val totalKnowledgePoints: Int get() = coveredKnowledgePoints.size + uncoveredKnowledgePoints.size

    /** 知识点口径覆盖率：仅用于诊断/展示，**不参与** [sufficient] 判定（固定题槽必然偏低）。 */
    val knowledgePointCoverage: Double
        get() = if (totalKnowledgePoints == 0) 0.0 else coveredKnowledgePoints.size.toDouble() / totalKnowledgePoints

    val sufficient: Boolean
        get() = coveredSections.isNotEmpty() &&
            coveredKnowledgePoints.isNotEmpty() &&
            coveredRatio >= QuizPlanner.MIN_COVERAGE_RATIO

    /** 覆盖不足的统一说明（章节口径；校验 issue 与生成前早退的失败原因共用）。 */
    fun coverageInsufficiencyMessage(): String {
        val total = totalSections
        if (total == 0) {
            return "章节覆盖不足：没有可用章节（低 OCR/禁止抽取或无正文），不能用 RAG top5 冒充全章"
        }
        val uncoveredRatio = uncoveredSections.size.toDouble() / total
        return buildString {
            append("章节覆盖不足：")
            if (uncoveredSections.isEmpty()) {
                append("章节覆盖 $total/$total（章节覆盖率 ${"%.2f".format(coveredRatio)}）")
            } else {
                append("未覆盖 ${uncoveredSections.size}/$total 个章节")
                append("（未覆盖比例 ${"%.2f".format(uncoveredRatio)}，")
                append("章节覆盖率 ${"%.2f".format(coveredRatio)} 低于 ${QuizPlanner.MIN_COVERAGE_RATIO}）")
            }
            if (coveredKnowledgePoints.isEmpty()) append("；覆盖章节内没有可用知识点")
        }
    }

    fun knowledgePointOf(ordinal: Int): String? = slots.firstOrNull { it.ordinal == ordinal }?.knowledgePoint

    fun coverageJson(): String = buildJsonObject {
        put("templateVersion", templateVersion)
        put("sourceRevision", sourceRevision)
        put(
            "coveredSections",
            buildJsonArray { coveredSections.forEach { add(it) } },
        )
        put(
            "uncoveredSections",
            buildJsonArray { uncoveredSections.forEach { add(it) } },
        )
        put(
            "coveredKnowledgePoints",
            buildJsonArray { coveredKnowledgePoints.forEach { add(it) } },
        )
        put(
            "uncoveredKnowledgePoints",
            buildJsonArray { uncoveredKnowledgePoints.forEach { add(it) } },
        )
        put(
            "excludedSections",
            buildJsonArray {
                excludedSections.forEach { (sectionId, reason) ->
                    addJsonObject {
                        put("sectionId", sectionId)
                        put("reason", reason)
                    }
                }
            },
        )
        put("coveredRatio", coveredRatio)
        put("knowledgePointCoverage", knowledgePointCoverage)
        put("truncated", truncated)
        put("sufficient", sufficient)
        put("notes", buildJsonArray { notes.forEach { add(it) } })
    }.toString()
}

/**
 * 出卷规划：固定 sourceRevision/scope → 覆盖矩阵 + 题槽。
 *
 * 规则（设计 §7.2/§14）：
 *  - 只用固定 revision 的授权章节；低 OCR/抽取禁用段落排除并记录原因；
 *  - 难度目标基础 5/理解 3/应用 2（按模板题数等比取整）；
 *  - 超预算时记录已覆盖/未覆盖章节与知识点，不假装覆盖全章；
 *  - 同一输入产生同一计划（排序与分配都确定）。
 */
class QuizPlanner {

    fun plan(request: QuizPlanRequest): QuizGenerationPlan {
        val template = request.template
        require(template.itemCount > 0) { "template must contain items" }
        require(request.sourceRevision.isNotBlank()) { "sourceRevision must be pinned" }
        require(request.maxPromptChars > 0) { "maxPromptChars must be positive" }

        require(request.sections.all { it.sectionId.isNotBlank() } && request.sections.map { it.sectionId }.distinct().size == request.sections.size) { "Section identities must be unique and stable" }
        val ordered = request.sections.sortedWith(compareBy({ it.order }, { it.sectionId }))
        val excluded = linkedMapOf<String, String>()
        val usable = ordered.filter { section ->
            when (section.quality) {
                SectionQuality.OK -> true
                SectionQuality.LOW_OCR_CONFIDENCE -> {
                    excluded[section.sectionId] = "LOW_OCR_CONFIDENCE"
                    false
                }

                SectionQuality.EXTRACTION_BLOCKED -> {
                    excluded[section.sectionId] = "EXTRACTION_BLOCKED"
                    false
                }
            }
        }

        val notes = mutableListOf<String>()
        val coveredSections = mutableListOf<String>()
        val uncoveredSections = mutableListOf<String>()
        var usedChars = 0
        for ((index, section) in usable.withIndex()) {
            val nextUsed = usedChars + section.text.length
            if (!section.fullyRead || nextUsed > request.maxPromptChars) {
                uncoveredSections += section.sectionId
                continue
            }
            usedChars = nextUsed
            coveredSections += section.sectionId
        }
        val truncated = uncoveredSections.isNotEmpty()
        if (truncated) {
            notes += "正文超出预算 ${request.maxPromptChars} 字符：未覆盖 ${uncoveredSections.size} 个章节"
        }
        if (excluded.isNotEmpty()) {
            notes += "排除 ${excluded.size} 个抽取质量不合格章节（低 OCR/禁止抽取）"
        }

        val coveredSectionObjects = usable.filter { it.sectionId in coveredSections }
        val allKnowledgePoints = linkedSetOf<String>()
        coveredSectionObjects.forEach { allKnowledgePoints.addAll(it.knowledgePoints.map(String::trim).filter(String::isNotEmpty)) }
        usable.filter { it.sectionId in uncoveredSections }.forEach { section ->
            section.knowledgePoints.map(String::trim).filter(String::isNotEmpty).forEach { allKnowledgePoints.add(it) }
        }

        val coveredCandidates = linkedSetOf<String>()
        coveredSectionObjects.forEach { coveredCandidates.addAll(it.knowledgePoints.map(String::trim).filter(String::isNotEmpty)) }
        if (coveredCandidates.isEmpty()) {
            notes += "覆盖范围内没有可用知识点，不能规划出卷"
        }

        val difficultyTargets = template.difficultyTargets.ifEmpty { QuizTemplate.difficultyTargetsFor(template.itemCount) }
        val difficulties = buildList {
            QuizDifficulty.entries.forEach { difficulty ->
                repeat(difficultyTargets[difficulty] ?: 0) { add(difficulty) }
            }
        }.toMutableList()
        val expectedItems = template.expectedItems()
        if (difficulties.size != expectedItems.size) {
            // 目标分布与题数不一致时补齐/截断，保证槽位数恒等于模板题数。
            while (difficulties.size < expectedItems.size) difficulties.add(QuizDifficulty.BASIC)
            while (difficulties.size > expectedItems.size) difficulties.removeAt(difficulties.lastIndex)
            notes += "难度目标与题数不一致，已按模板题数归一"
        }

        val candidates = coveredCandidates.toList()
        val slots = expectedItems.mapIndexed { index, planned ->
            val knowledgePoint = if (candidates.isEmpty()) null else candidates[index % candidates.size]
            PlannedSlot(
                ordinal = planned.ordinal,
                type = planned.type,
                score = planned.score,
                difficulty = difficulties[index],
                knowledgePoint = knowledgePoint,
                sectionIds = if (knowledgePoint == null) {
                    coveredSections
                } else {
                    coveredSectionObjects.filter { knowledgePoint in it.knowledgePoints }.map { it.sectionId }
                },
            )
        }
        val assignedKnowledgePoints = slots.mapNotNull { it.knowledgePoint }.distinct()
        val uncoveredKnowledgePoints = allKnowledgePoints.filter { it !in assignedKnowledgePoints }

        if (candidates.isNotEmpty() && candidates.size < expectedItems.size) {
            notes += "知识点不足：需要 ${expectedItems.size} 个不同知识点，可用 ${candidates.size} 个，存在重复风险"
        }
        if (uncoveredKnowledgePoints.isNotEmpty()) {
            notes += "未覆盖知识点 ${uncoveredKnowledgePoints.size} 个"
        }

        return QuizGenerationPlan(
            templateId = template.templateId,
            templateVersion = template.templateVersion,
            sourceRevision = request.sourceRevision,
            scopeJson = request.scopeJson,
            slots = slots,
            coveredSections = coveredSections,
            uncoveredSections = uncoveredSections,
            coveredKnowledgePoints = assignedKnowledgePoints,
            uncoveredKnowledgePoints = uncoveredKnowledgePoints,
            excludedSections = excluded,
            truncated = truncated,
            notes = notes,
        )
    }

    companion object {
        /** 章节覆盖率阈值：低于 80% 视为覆盖不足（COVERAGE_INSUFFICIENT）。 */
        const val MIN_COVERAGE_RATIO = 0.8
    }
}
