package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizDifficulty
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizPaperSnapshot
import com.example.myapplication.domain.quiz.QuizTemplateSpec
import com.example.myapplication.domain.quiz.QuizText
import com.example.myapplication.domain.quiz.QuizValidationCode
import com.example.myapplication.domain.quiz.QuizValidationReport
import com.example.myapplication.domain.quiz.RubricPoint
import com.example.myapplication.domain.quiz.ValidationIssue
import com.example.myapplication.domain.reader.SourceEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 模型原始出卷 JSON 的解析器（07-随堂测完整结构样例 §2 的字段形状）。
 *
 * 解析只负责“能不能变成 typed 草稿”：JSON 结构/字段类型/枚举值错误记为
 * STRUCTURE_INVALID。数量/分值/选项/rubric/证据/覆盖由 [QuizValidator] 分层校验。
 * 解析失败不会抛异常给调用方，返回可定位的 issues（itemIndex + field）。
 */
object QuizDraftParser {

    data class Parsed(
        val paper: QuizPaperSnapshot?,
        val issues: List<ValidationIssue>,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    fun parse(
        rawJson: String,
        template: QuizTemplateSpec,
        sourceRevision: String = "",
        scopeJson: String = "{}",
    ): Parsed {
        val issues = mutableListOf<ValidationIssue>()
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrElse {
            return Parsed(null, listOf(structure(null, "root", "出卷结果不是合法 JSON 对象")))
        }
        val templateId = root.string("templateId")
        if (templateId != null && templateId != template.templateId) {
            issues += structure(
                itemIndex = null,
                field = "templateId",
                message = "templateId=$templateId 与当前模板 ${template.templateId} 不一致",
            )
        }
        val itemsArray = root["items"] as? JsonArray
        if (itemsArray == null) {
            issues += structure(null, "items", "缺少 items 数组")
            return Parsed(null, issues)
        }
        val items = itemsArray.mapIndexedNotNull { index, element ->
            parseItem(index = index, element = element, issues = issues)
        }
        if (items.isEmpty() && itemsArray.isNotEmpty()) {
            return Parsed(null, issues)
        }
        val paper = QuizPaperSnapshot(
            templateId = templateId ?: template.templateId,
            templateVersion = root.string("templateVersion") ?: template.templateVersion,
            sourceRevision = sourceRevision,
            scopeJson = scopeJson,
            questionCount = items.size,
            maxScore = items.sumOf { it.score },
            items = items,
            generatedBy = template.generatedBy,
        )
        return Parsed(paper = paper, issues = issues)
    }

    private fun parseItem(
        index: Int,
        element: JsonElement,
        issues: MutableList<ValidationIssue>,
    ): QuizItemSnapshot? {
        val obj = element as? JsonObject
        if (obj == null) {
            issues += structure(index, "-", "题目不是 JSON 对象")
            return null
        }
        var valid = true

        val typeRaw = obj.string("type")
        val type = QuizItemType.entries.firstOrNull { it.name == typeRaw }
        if (type == null) {
            issues += structure(index, "type", "未知题型：$typeRaw")
            valid = false
        }

        val stem = obj.string("stem")?.trim().orEmpty()
        if (stem.isEmpty()) {
            issues += structure(index, "stem", "题干为空")
            valid = false
        }
        val score = obj.intOrNull("score")
        if (score == null || score <= 0) {
            issues += structure(index, "score", "score 缺失或不是正整数")
            valid = false
        }
        val analysis = obj.string("analysis")?.trim().orEmpty()
        if (analysis.isEmpty()) {
            issues += structure(index, "analysis", "缺少解析：不能只给答案")
            valid = false
        }
        val knowledgePoint = obj.string("knowledgePoint")?.trim().orEmpty()
        if (knowledgePoint.isEmpty()) {
            issues += structure(index, "knowledgePoint", "缺少知识点")
            valid = false
        }

        val options = parseStringList(obj, "options", index, issues) { valid = false }
        val answer = parseAnswer(obj["answer"], type, index, issues) { valid = false }
        val rubric = parseRubric(obj["rubric"], index, issues) { valid = false }
        val evidenceIds = parseStringList(obj, "evidenceIds", index, issues) { valid = false }

        val difficultyRaw = obj.string("difficulty")
        val difficulty = if (difficultyRaw == null) {
            QuizDifficulty.BASIC
        } else {
            QuizDifficulty.entries.firstOrNull { it.name == difficultyRaw } ?: run {
                issues += structure(index, "difficulty", "未知难度：$difficultyRaw")
                valid = false
                QuizDifficulty.BASIC
            }
        }

        if (!valid || type == null || score == null) return null
        return QuizItemSnapshot(
            ordinal = index + 1,
            type = type,
            stem = stem,
            options = options,
            answer = answer,
            score = score,
            analysis = analysis,
            knowledgePoint = knowledgePoint,
            difficulty = difficulty,
            rubric = rubric,
            evidenceIds = evidenceIds,
        )
    }

    private fun parseStringList(
        obj: JsonObject,
        field: String,
        index: Int,
        issues: MutableList<ValidationIssue>,
        onError: () -> Unit,
    ): List<String> {
        val element = obj[field] ?: return emptyList()
        val array = element as? JsonArray
        if (array == null) {
            issues += structure(index, field, "$field 必须是字符串数组")
            onError()
            return emptyList()
        }
        val values = array.map { (it as? JsonPrimitive)?.contentOrNull }
        if (values.any { it == null }) {
            issues += structure(index, field, "$field 含非字符串元素")
            onError()
            return emptyList()
        }
        return values.filterNotNull().map(String::trim).filter(String::isNotEmpty)
    }

    private fun parseAnswer(
        element: JsonElement?,
        type: QuizItemType?,
        index: Int,
        issues: MutableList<ValidationIssue>,
        onError: () -> Unit,
    ): List<String> {
        val answers = when (element) {
            null -> {
                issues += structure(index, "answer", "缺少 answer")
                onError()
                return emptyList()
            }

            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            is JsonArray -> element.map { (it as? JsonPrimitive)?.contentOrNull }
                .also { values ->
                    if (values.any { it == null }) {
                        issues += structure(index, "answer", "answer 数组含非字符串元素")
                        onError()
                    }
                }
                .filterNotNull()

            else -> {
                issues += structure(index, "answer", "answer 必须是字符串或字符串数组")
                onError()
                return emptyList()
            }
        }.map(String::trim).filter(String::isNotEmpty)

        if (type == QuizItemType.CHOICE && answers.size != 1) {
            issues += structure(index, "answer", "选择题必须且只能有一个答案，实际 ${answers.size} 个")
            onError()
        }
        if (type == QuizItemType.ESSAY && answers.isEmpty()) {
            issues += structure(index, "answer", "简答题缺少参考要点")
            onError()
        }
        return answers
    }

    private fun parseRubric(
        element: JsonElement?,
        index: Int,
        issues: MutableList<ValidationIssue>,
        onError: () -> Unit,
    ): List<RubricPoint> {
        if (element == null) return emptyList()
        val array = element as? JsonArray
        if (array == null) {
            issues += structure(index, "rubric", "rubric 必须是数组")
            onError()
            return emptyList()
        }
        val points = mutableListOf<RubricPoint>()
        array.forEachIndexed { position, raw ->
            val obj = raw as? JsonObject
            if (obj == null) {
                issues += structure(index, "rubric[$position]", "rubric 要点必须是对象")
                onError()
                return@forEachIndexed
            }
            val key = obj.string("key")?.trim().orEmpty()
            val weight = obj.intOrNull("weight")
            if (key.isEmpty()) {
                issues += structure(index, "rubric[$position].key", "rubric 缺少 key")
                onError()
            }
            if (weight == null) {
                issues += structure(index, "rubric[$position].weight", "rubric 缺少整型 weight")
                onError()
            }
            if (key.isEmpty() || weight == null) return@forEachIndexed
            points += RubricPoint(
                key = key,
                weight = weight,
                accepted = parseStringList(obj, "accepted", index, issues) { onError() },
                negatedBy = parseStringList(obj, "negatedBy", index, issues) { onError() },
            )
        }
        return points
    }

    private fun structure(itemIndex: Int?, field: String, message: String) = ValidationIssue(
        code = QuizValidationCode.STRUCTURE_INVALID,
        itemIndex = itemIndex,
        field = field,
        message = message,
    )

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
}

/** 草稿解析 + 分层校验的结果。 */
data class QuizDraftValidation(
    val paper: QuizPaperSnapshot?,
    val report: QuizValidationReport,
) {
    val isValid: Boolean get() = report.isValid
}

/**
 * 随堂测校验器（第四期详细设计 §14 校验表；FX-15/Q-01/Q-02）。
 *
 * 分层按序执行，任一层失败立即返回该层 issues（便于定位是哪条规则真正生效）：
 *  结构/类型 → 题数/题型 → score → 选项/答案 → rubric → 题干/知识点重复 →
 *  evidence/source → 全章覆盖。
 *
 * 关键语义：
 *  - 禁止“裁掉不合格题后当 10 题通过”（题数层先于分值层）；
 *  - evidence 必须能经注入的 registry 解析且 quote/hash/revision 都核验；
 *  - coverage 只在传入 [QuizGenerationPlan] 时校验，未规划的知识点直接失败。
 */
class QuizValidator(
    private val template: QuizTemplateSpec,
    /** 出处解析端口：应用侧 S 编号 → 固定 revision 的原文证据。 */
    private val evidenceResolver: ((String) -> SourceEvidence?)? = null,
    /** 规划结果：用于知识点覆盖与超预算的覆盖校验。 */
    private val plan: QuizGenerationPlan? = null,
    /** 固定 sourceRevision：卷/出处都必须与它一致，否则 SOURCE_CHANGED。 */
    private val expectedSourceRevision: String? = null,
) {

    fun validateDraft(
        rawJson: String,
        sourceRevision: String = expectedSourceRevision.orEmpty(),
        scopeJson: String = "{}",
    ): QuizDraftValidation {
        val parsed = QuizDraftParser.parse(rawJson, template, sourceRevision, scopeJson)
        val report = if (parsed.issues.isNotEmpty()) {
            QuizValidationReport(parsed.issues)
        } else {
            validate(parsed.paper ?: return QuizDraftValidation(null, QuizValidationReport(listOf(
                ValidationIssue(QuizValidationCode.STRUCTURE_INVALID, null, "root", "无法解析出卷草稿"),
            ))))
        }
        return QuizDraftValidation(parsed.paper, report)
    }

    fun validate(paper: QuizPaperSnapshot): QuizValidationReport {
        validateStructure(paper)?.let { return it }
        validateCount(paper)?.let { return it }
        validateScores(paper)?.let { return it }
        validateOptionsAndAnswers(paper)?.let { return it }
        validateRubric(paper)?.let { return it }
        validateDuplicates(paper)?.let { return it }
        validateEvidence(paper)?.let { return it }
        validateCoverage(paper)?.let { return it }
        return QuizValidationReport.VALID
    }

    // ---- 1. 结构与类型 ----

    private fun validateStructure(paper: QuizPaperSnapshot): QuizValidationReport? {
        val issues = mutableListOf<ValidationIssue>()
        if (paper.items.isEmpty()) {
            issues += issue(QuizValidationCode.STRUCTURE_INVALID, null, "items", "试卷没有题目")
        }
        if (paper.templateId != template.templateId) {
            issues += issue(
                QuizValidationCode.STRUCTURE_INVALID,
                null,
                "templateId",
                "试卷 templateId=${paper.templateId} 与模板 ${template.templateId} 不一致",
            )
        }
        if (paper.templateVersion != template.templateVersion) {
            issues += issue(
                QuizValidationCode.STRUCTURE_INVALID,
                null,
                "templateVersion",
                "试卷 templateVersion=${paper.templateVersion} 与模板 ${template.templateVersion} 不一致",
            )
        }
        if (paper.generatedBy != template.generatedBy) {
            issues += issue(
                QuizValidationCode.STRUCTURE_INVALID,
                null,
                "generatedBy",
                "生成来源 ${paper.generatedBy} 与模板 ${template.generatedBy} 不一致（离线卷不得冒充 AI 卷）",
            )
        }
        val seenOrdinals = mutableSetOf<Int>()
        paper.items.forEachIndexed { index, item ->
            val ordinal = item.ordinal
            if (ordinal != index + 1) {
                issues += issue(
                    QuizValidationCode.STRUCTURE_INVALID,
                    index,
                    "ordinal",
                    "ordinal=$ordinal 与位置 ${index + 1} 不一致",
                )
            }
            if (!seenOrdinals.add(ordinal)) {
                issues += issue(QuizValidationCode.STRUCTURE_INVALID, index, "ordinal", "ordinal=$ordinal 重复")
            }
            if (QuizText.isBlankOrPlaceholder(item.stem)) {
                issues += issue(QuizValidationCode.STRUCTURE_INVALID, index, "stem", "题干为空")
            }
            if (QuizText.isBlankOrPlaceholder(item.analysis)) {
                issues += issue(QuizValidationCode.STRUCTURE_INVALID, index, "analysis", "缺少解析：不能只给答案字母")
            }
            if (QuizText.isBlankOrPlaceholder(item.knowledgePoint)) {
                issues += issue(QuizValidationCode.STRUCTURE_INVALID, index, "knowledgePoint", "缺少知识点")
            }
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- 2. 题数与题型 ----

    private fun validateCount(paper: QuizPaperSnapshot): QuizValidationReport? {
        val issues = mutableListOf<ValidationIssue>()
        if (paper.items.size != template.itemCount) {
            issues += issue(
                QuizValidationCode.COUNT_MISMATCH,
                null,
                "items",
                "实际 ${paper.items.size} 题，模板期望 ${template.itemCount} 题（${template.choiceCount} CHOICE + ${template.essayCount} ESSAY）；不允许裁题后仍当合格卷",
            )
        }
        if (paper.questionCount != paper.items.size) {
            issues += issue(
                QuizValidationCode.COUNT_MISMATCH,
                null,
                "questionCount",
                "声明的 questionCount=${paper.questionCount} 与实际 ${paper.items.size} 题不一致",
            )
        }
        template.expectedItems().forEachIndexed { index, expected ->
            val actual = paper.items.getOrNull(index) ?: return@forEachIndexed
            if (actual.type != expected.type) {
                issues += issue(
                    QuizValidationCode.COUNT_MISMATCH,
                    index,
                    "type",
                    "第 ${index + 1} 题题型应为 ${expected.type}，实际 ${actual.type}",
                )
            }
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- 3. 分值分配 ----

    private fun validateScores(paper: QuizPaperSnapshot): QuizValidationReport? {
        val issues = mutableListOf<ValidationIssue>()
        template.expectedItems().forEachIndexed { index, expected ->
            val actual = paper.items.getOrNull(index) ?: return@forEachIndexed
            if (actual.score != expected.score) {
                issues += issue(
                    QuizValidationCode.SCORE_SUM_MISMATCH,
                    index,
                    "score",
                    "第 ${index + 1} 题分值 ${actual.score} 与模板 ${expected.score} 不一致",
                )
            }
        }
        if (paper.maxScore != paper.scoreSum) {
            issues += issue(
                QuizValidationCode.SCORE_SUM_MISMATCH,
                null,
                "maxScore",
                "声明满分 ${paper.maxScore} 与题目分值合计 ${paper.scoreSum} 不一致",
            )
        }
        if (paper.maxScore != template.maxScore) {
            issues += issue(
                QuizValidationCode.SCORE_SUM_MISMATCH,
                null,
                "maxScore",
                "满分 ${paper.maxScore} 与模板 ${template.maxScore} 不一致",
            )
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- 4. 选项与答案 ----

    private fun validateOptionsAndAnswers(paper: QuizPaperSnapshot): QuizValidationReport? {
        val issues = mutableListOf<ValidationIssue>()
        paper.items.forEachIndexed { index, item ->
            when (item.type) {
                QuizItemType.CHOICE -> {
                    if (item.options.size != template.optionsPerChoice) {
                        issues += issue(
                            QuizValidationCode.OPTIONS_INVALID,
                            index,
                            "options",
                            "单选题需要 ${template.optionsPerChoice} 个选项，实际 ${item.options.size} 个",
                        )
                    }
                    if (item.options.any { QuizText.isBlankOrPlaceholder(it) }) {
                        issues += issue(QuizValidationCode.OPTIONS_INVALID, index, "options", "存在空白选项")
                    }
                    val normalized = item.options.map { QuizText.normalize(it) }
                    if (normalized.size != normalized.distinct().size) {
                        issues += issue(QuizValidationCode.OPTIONS_INVALID, index, "options", "选项内容重复")
                    }
                    val prefixes = optionLetters(item.options)
                    if (prefixes != null && prefixes != prefixes.indices.map { ('A' + it).toString() }) {
                        issues += issue(
                            QuizValidationCode.OPTIONS_INVALID,
                            index,
                            "options",
                            "选项标识必须稳定为 A/B/C/D，实际 ${prefixes.joinToString("")}",
                        )
                    }
                    if (item.answer.size != 1) {
                        issues += issue(
                            QuizValidationCode.ANSWER_OUT_OF_RANGE,
                            index,
                            "answer",
                            "单选题必须且只能有一个答案，实际 ${item.answer.size} 个",
                        )
                    } else {
                        val letter = item.answer.first().trim().uppercase()
                        val targetIndex = letter.singleOrNull()?.let { it - 'A' }
                        if (letter.length != 1 || targetIndex == null || targetIndex !in item.options.indices) {
                            issues += issue(
                                QuizValidationCode.ANSWER_OUT_OF_RANGE,
                                index,
                                "answer",
                                "答案 $letter 不在选项范围 A..${optionLetterAt(item.options.size - 1)} 内",
                            )
                        } else if (prefixes != null && prefixes[targetIndex] != letter) {
                            issues += issue(
                                QuizValidationCode.ANSWER_OUT_OF_RANGE,
                                index,
                                "answer",
                                "答案 $letter 与选项标识 ${prefixes[targetIndex]} 不匹配",
                            )
                        }
                    }
                }

                QuizItemType.ESSAY -> {
                    if (item.options.isNotEmpty()) {
                        issues += issue(QuizValidationCode.OPTIONS_INVALID, index, "options", "简答题不应带选项")
                    }
                    if (item.answer.isEmpty()) {
                        issues += issue(
                            QuizValidationCode.ANSWER_OUT_OF_RANGE,
                            index,
                            "answer",
                            "简答题缺少参考要点",
                        )
                    }
                }
            }
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- 5. rubric ----

    private fun validateRubric(paper: QuizPaperSnapshot): QuizValidationReport? {
        val issues = mutableListOf<ValidationIssue>()
        paper.items.forEachIndexed { index, item ->
            when (item.type) {
                QuizItemType.ESSAY -> {
                    if (item.rubric.size !in template.rubricMinPoints..template.rubricMaxPoints) {
                        issues += issue(
                            QuizValidationCode.RUBRIC_INVALID,
                            index,
                            "rubric",
                            "简答题 rubric 需要 ${template.rubricMinPoints}–${template.rubricMaxPoints} 个要点，实际 ${item.rubric.size} 个",
                        )
                    }
                    if (item.rubric.map { it.key }.distinct().size != item.rubric.size) {
                        issues += issue(QuizValidationCode.RUBRIC_INVALID, index, "rubric", "rubric key 重复")
                    }
                    item.rubric.forEachIndexed { position, point ->
                        if (point.weight <= 0) {
                            issues += issue(
                                QuizValidationCode.RUBRIC_INVALID,
                                index,
                                "rubric[$position].weight",
                                "rubric 权重必须为正，实际 ${point.weight}",
                            )
                        }
                        if (point.accepted.isEmpty()) {
                            issues += issue(
                                QuizValidationCode.RUBRIC_INVALID,
                                index,
                                "rubric[$position].accepted",
                                "rubric 要点缺少可判定表达",
                            )
                        }
                    }
                    val weightSum = item.rubric.sumOf { it.weight }
                    if (weightSum != item.score) {
                        issues += issue(
                            QuizValidationCode.RUBRIC_INVALID,
                            index,
                            "rubric",
                            "rubric 权重和 $weightSum 与题目分值 ${item.score} 不一致",
                        )
                    }
                }

                QuizItemType.CHOICE -> {
                    if (item.rubric.isNotEmpty()) {
                        issues += issue(QuizValidationCode.RUBRIC_INVALID, index, "rubric", "单选题不应带 rubric")
                    }
                }
            }
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- 6. 题干/知识点重复 ----

    private fun validateDuplicates(paper: QuizPaperSnapshot): QuizValidationReport? {
        val issues = mutableListOf<ValidationIssue>()
        val stems = mutableMapOf<String, Int>()
        val points = mutableMapOf<String, Int>()
        paper.items.forEachIndexed { index, item ->
            val stemKey = QuizText.normalize(item.stem)
            val previousStem = stems.putIfAbsent(stemKey, index)
            if (previousStem != null) {
                issues += issue(
                    QuizValidationCode.DUPLICATE_ITEM,
                    index,
                    "stem",
                    "题干与第 ${previousStem + 1} 题重复",
                )
            }
            val pointKey = QuizText.normalize(item.knowledgePoint)
            val previousPoint = points.putIfAbsent(pointKey, index)
            if (previousPoint != null) {
                issues += issue(
                    QuizValidationCode.DUPLICATE_ITEM,
                    index,
                    "knowledgePoint",
                    "知识点「${item.knowledgePoint}」与第 ${previousPoint + 1} 题重复",
                )
            }
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- 7. 证据与来源 ----

    private fun validateEvidence(paper: QuizPaperSnapshot): QuizValidationReport? {
        val issues = mutableListOf<ValidationIssue>()
        val pinned = expectedSourceRevision ?: plan?.sourceRevision
        if (pinned != null && paper.sourceRevision != pinned) {
            issues += issue(
                QuizValidationCode.SOURCE_CHANGED,
                null,
                "sourceRevision",
                "试卷 sourceRevision=${paper.sourceRevision} 已不是固定 revision=$pinned，必须终止或重启新 draft",
            )
            return report(issues)
        }
        val resolver = evidenceResolver
        if (resolver == null) {
            issues += issue(
                QuizValidationCode.EVIDENCE_UNVERIFIED,
                null,
                "evidenceIds",
                "未注入证据解析端口：无法核验出处，不能发布",
            )
            return report(issues)
        }
        val bound = QuizEvidenceBinder(resolver, pinned).bindPaper(paper)
        issues += bound.issues
        paper.items.forEachIndexed { index, item ->
            if (item.evidenceIds.isEmpty() && bound.byItem[index]?.issues.isNullOrEmpty()) {
                issues += issue(
                    QuizValidationCode.EVIDENCE_UNVERIFIED,
                    index,
                    "evidenceIds",
                    "题目缺少出处",
                )
            }
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- 8. 全章覆盖 ----

    private fun validateCoverage(paper: QuizPaperSnapshot): QuizValidationReport? {
        val plan = plan ?: return null
        val issues = mutableListOf<ValidationIssue>()
        if (plan.sourceRevision != paper.sourceRevision) {
            issues += issue(
                QuizValidationCode.SOURCE_CHANGED,
                null,
                "sourceRevision",
                "计划 revision=${plan.sourceRevision} 与试卷 revision=${paper.sourceRevision} 不一致",
            )
        }
        val covered = plan.coveredKnowledgePoints.toSet()
        paper.items.forEachIndexed { index, item ->
            if (item.knowledgePoint !in covered) {
                issues += issue(
                    QuizValidationCode.COVERAGE_INSUFFICIENT,
                    index,
                    "knowledgePoint",
                    "知识点「${item.knowledgePoint}」不在计划覆盖范围内",
                )
            }
        }
        if (!plan.sufficient) {
            issues += issue(
                QuizValidationCode.COVERAGE_INSUFFICIENT,
                null,
                "coverage",
                plan.coverageInsufficiencyMessage(),
            )
        }
        return issues.takeIf { it.isNotEmpty() }?.let(::report)
    }

    // ---- helpers ----

    private fun issue(code: QuizValidationCode, itemIndex: Int?, field: String, message: String) =
        ValidationIssue(code = code, itemIndex = itemIndex, field = field, message = message)

    private fun report(issues: List<ValidationIssue>) = QuizValidationReport(issues)

    /** 选项的稳定标识（A/B/C/D…）：任一选项带标识则必须全部带且顺序一致。 */
    private fun optionLetters(options: List<String>): List<String>? {
        val letters = options.map { option ->
            val trimmed = option.trim()
            if (trimmed.length >= 2 && trimmed[0] in 'A'..'Z' && trimmed[1] in OPTION_SEPARATORS) {
                trimmed[0].toString()
            } else {
                null
            }
        }
        if (letters.all { it == null }) return null
        return letters.map { it ?: "" }
    }

    private fun optionLetterAt(index: Int): Char = 'A' + index

    private companion object {
        val OPTION_SEPARATORS = setOf('.', '、', '．', ':', '：', ')', '）')
    }
}
