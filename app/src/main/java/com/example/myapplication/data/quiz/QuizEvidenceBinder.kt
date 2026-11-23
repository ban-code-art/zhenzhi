package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizEvidence
import com.example.myapplication.domain.quiz.QuizEvidenceRef
import com.example.myapplication.domain.quiz.QuizPaperSnapshot
import com.example.myapplication.domain.quiz.QuizValidationCode
import com.example.myapplication.domain.quiz.ValidationIssue
import com.example.myapplication.domain.reader.SourceEvidence

/**
 * 出处绑定（设计 §7/§14；07-随堂测完整结构样例 §1/§3）。
 *
 * 唯一合法的出处来源是注入的证据解析端口（应用侧 SourceEvidence registry）：
 *  - 模型只能给出 S 编号；chunkId、书名、磁盘路径、坐标一律拒绝；
 *  - 编号必须能被 registry 解析，伪造编号 → EVIDENCE_UNVERIFIED；
 *  - registry 的 quote 与 sha256 必须自洽，改 1 字符而未改 hash → 核验失败；
 *  - 出处的 contentRevision 必须是固定 sourceRevision，否则不能发布；
 *  - 模型若附带了 quote 声明，必须逐字（忽略空白）出现在原文中。
 */
class QuizEvidenceBinder(
    private val resolve: (String) -> SourceEvidence?,
    private val pinnedSourceRevision: String? = null,
) {
    data class BoundItem(
        val itemIndex: Int,
        val refs: List<QuizEvidenceRef>,
        val issues: List<ValidationIssue>,
    ) {
        val isValid: Boolean get() = issues.isEmpty()
    }

    data class BoundPaper(
        val byItem: Map<Int, BoundItem>,
        val issues: List<ValidationIssue>,
    ) {
        val isValid: Boolean get() = issues.isEmpty()
    }

    /**
     * 绑定单题出处。
     *
     * [claimedQuotes] 是模型文本里对某个 evidenceId 的引用原文（可选）；不是权威字段，
     * 只用于校验模型是否在编造 quote。
     */
    fun bindItem(
        itemIndex: Int,
        evidenceIds: List<String>,
        claimedQuotes: Map<String, String> = emptyMap(),
    ): BoundItem {
        val issues = mutableListOf<ValidationIssue>()
        val refs = mutableListOf<QuizEvidenceRef>()
        val seen = linkedSetOf<String>()

        if (evidenceIds.isEmpty()) {
            issues += issue(
                itemIndex = itemIndex,
                field = "evidenceIds",
                message = "题目缺少出处：每道题都必须绑定至少一个 evidenceId",
            )
        }

        evidenceIds.forEachIndexed { position, raw ->
            val field = "evidenceIds[$position]"
            val evidenceId = raw.trim()
            if (!QuizEvidence.isValidEvidenceId(evidenceId)) {
                issues += issue(
                    itemIndex = itemIndex,
                    field = field,
                    message = "不接受非应用 S 编号的出处：$evidenceId（chunkId/书名不算出处）",
                )
                return@forEachIndexed
            }
            if (!seen.add(evidenceId)) {
                // 同一题重复引用同一出处不报错：绑定结果去重即可。
                return@forEachIndexed
            }
            val evidence = try {
                resolve(evidenceId)
            } catch (error: Throwable) {
                issues += issue(itemIndex = itemIndex, field = field, message = "出处解析失败：${error.javaClass.simpleName}")
                return@forEachIndexed
            }
            if (evidence == null) {
                issues += issue(
                    itemIndex = itemIndex,
                    field = field,
                    message = "伪造或不存在的 evidenceId：$evidenceId",
                )
                return@forEachIndexed
            }
            val ref = QuizEvidence.fromSource(evidence)
            if (!QuizEvidence.isSelfConsistent(ref)) {
                issues += issue(
                    itemIndex = itemIndex,
                    field = field,
                    message = "出处 quote 与 quoteSha256 不一致：$evidenceId（来源核验失败）",
                )
                return@forEachIndexed
            }
            if (!ref.matchesPinnedRevision(pinnedSourceRevision)) {
                issues += issue(
                    itemIndex = itemIndex,
                    field = field,
                    message = "出处 revision=${ref.contentRevision} 与固定 sourceRevision=$pinnedSourceRevision 不一致",
                )
                return@forEachIndexed
            }
            val claim = claimedQuotes[evidenceId]?.trim().orEmpty()
            if (claim.isNotEmpty() && !normalize(claim).let { normalize(ref.quote).contains(it) }) {
                issues += issue(
                    itemIndex = itemIndex,
                    field = field,
                    message = "模型引用的 quote 与原文不符：$evidenceId",
                )
                return@forEachIndexed
            }
            refs += ref
        }

        return BoundItem(itemIndex = itemIndex, refs = refs, issues = issues)
    }

    /** 绑定整卷：逐题绑定并按题号汇总（生成器可只重写失败题）。 */
    fun bindPaper(
        paper: QuizPaperSnapshot,
        claimedQuotes: Map<Int, Map<String, String>> = emptyMap(),
    ): BoundPaper {
        val byItem = linkedMapOf<Int, BoundItem>()
        paper.items.forEachIndexed { index, item ->
            byItem[index] = bindItem(
                itemIndex = index,
                evidenceIds = item.evidenceIds,
                claimedQuotes = claimedQuotes[index].orEmpty(),
            )
        }
        return BoundPaper(byItem = byItem, issues = byItem.values.flatMap { it.issues })
    }

    private fun issue(itemIndex: Int, field: String, message: String) = ValidationIssue(
        code = QuizValidationCode.EVIDENCE_UNVERIFIED,
        itemIndex = itemIndex,
        field = field,
        message = message,
    )

    private fun normalize(text: String): String = text.replace(WHITESPACE, "")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
