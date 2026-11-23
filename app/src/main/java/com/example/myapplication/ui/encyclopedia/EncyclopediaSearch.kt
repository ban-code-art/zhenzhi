package com.example.myapplication.ui.encyclopedia

import com.example.myapplication.data.encyclopedia.Acupoint
import com.example.myapplication.data.encyclopedia.AtlasViewId
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.Meridian
import com.example.myapplication.data.encyclopedia.SymptomLesson
import com.example.myapplication.ui.encyclopedia.atlas.ALL_MERIDIANS_CODE
import java.util.Locale

internal enum class EncyclopediaSearchKind(val label: String) {
    ACUPOINT("穴位"),
    MERIDIAN("经络"),
    SYMPTOM("病症"),
}

internal data class EncyclopediaSearchResult(
    val kind: EncyclopediaSearchKind,
    val stableId: String,
    val title: String,
    val subtitle: String,
    val exactCodeMatch: Boolean = false,
)

internal enum class AcupointFilterOption(val label: String) {
    ALL("全部"),
    CURRENT_MERIDIAN("本经"),
    CURRENT_VIEW("本面"),
    UPPER_LIMB("上肢"),
    LOWER_LIMB("下肢"),
    CHEST("胸部"),
    FAVORITES("收藏");
}

internal object EncyclopediaSearch {
    fun normalizeQuery(raw: String): String =
        raw.trim().replace(Regex("\\s+"), "").lowercase(Locale.ROOT)

    fun search(
        snapshot: EncyclopediaSnapshot,
        rawQuery: String,
        limit: Int = 12,
    ): List<EncyclopediaSearchResult> {
        val term = normalizeQuery(rawQuery)
        if (term.isEmpty()) return emptyList()

        data class Ranked(val rank: Int, val orderBias: Int, val result: EncyclopediaSearchResult)

        val candidates = ArrayList<Ranked>()

        snapshot.points.forEachIndexed { idx, point ->
            val codeNorm = normalizeQuery(point.code)
            val nameNorm = normalizeQuery(point.name)
            val pinyinNorm = normalizeQuery(point.pinyin)
            val rank = when {
                codeNorm == term -> 0
                nameNorm == term -> 1
                codeNorm.startsWith(term) -> 2
                pinyinNorm == term -> 3
                nameNorm.contains(term) || codeNorm.contains(term) || pinyinNorm.contains(term) -> 5
                else -> -1
            }
            if (rank >= 0) {
                val meridianShort = snapshot.meridiansByCode[point.meridianCode]?.shortName ?: point.meridianCode
                val regionLabel = snapshot.occludedByPointCode[point.code]?.regionLabel ?: point.region
                candidates.add(
                    Ranked(
                        rank = rank,
                        orderBias = idx,
                        result = EncyclopediaSearchResult(
                            kind = EncyclopediaSearchKind.ACUPOINT,
                            stableId = point.code,
                            title = "${point.name} ${point.code}",
                            subtitle = "$meridianShort · $regionLabel",
                            exactCodeMatch = rank == 0,
                        ),
                    ),
                )
            }
        }

        snapshot.meridians.forEachIndexed { idx, meridian ->
            val codeNorm = normalizeQuery(meridian.code)
            val nameNorm = normalizeQuery(meridian.name)
            val shortNorm = normalizeQuery(meridian.shortName)
            val rank = when {
                codeNorm == term -> 0
                nameNorm == term || shortNorm == term -> 1
                nameNorm.contains(term) || shortNorm.contains(term) || codeNorm.contains(term) -> 4
                else -> -1
            }
            if (rank >= 0) {
                candidates.add(
                    Ranked(
                        rank = rank,
                        orderBias = 1000 + idx,
                        result = EncyclopediaSearchResult(
                            kind = EncyclopediaSearchKind.MERIDIAN,
                            stableId = meridian.code,
                            title = meridian.name,
                            subtitle = "${meridian.code} · ${meridian.pointCount} 穴",
                            exactCodeMatch = rank == 0,
                        ),
                    ),
                )
            }
        }

        snapshot.learning.symptoms.forEachIndexed { idx, symptom ->
            val nameNorm = normalizeQuery(symptom.name)
            val aliasNorms = symptom.aliases.map(::normalizeQuery)
            val rank = when {
                nameNorm == term -> 1
                aliasNorms.any { it == term } -> 2
                nameNorm.contains(term) || aliasNorms.any { it.contains(term) } -> 5
                else -> -1
            }
            if (rank >= 0) {
                candidates.add(
                    Ranked(
                        rank = rank,
                        orderBias = 2000 + idx,
                        result = EncyclopediaSearchResult(
                            kind = EncyclopediaSearchKind.SYMPTOM,
                            stableId = symptom.id,
                            title = symptom.name,
                            subtitle = "${symptom.category} · ${symptom.links.size} 个关联穴位",
                            exactCodeMatch = false,
                        ),
                    ),
                )
            }
        }

        return candidates
            .sortedWith(compareBy<Ranked>({ it.rank }, { it.orderBias }))
            .take(limit)
            .map { it.result }
    }

    fun availablePointFilters(meridianCode: String, favoritesEnabled: Boolean): List<AcupointFilterOption> =
        buildList {
            add(AcupointFilterOption.ALL)
            if (meridianCode != ALL_MERIDIANS_CODE) add(AcupointFilterOption.CURRENT_MERIDIAN)
            add(AcupointFilterOption.CURRENT_VIEW)
            add(AcupointFilterOption.UPPER_LIMB)
            add(AcupointFilterOption.LOWER_LIMB)
            add(AcupointFilterOption.CHEST)
            if (favoritesEnabled) add(AcupointFilterOption.FAVORITES)
        }

    fun filterPoints(
        snapshot: EncyclopediaSnapshot,
        filter: AcupointFilterOption,
        meridianCode: String,
        viewId: AtlasViewId,
        favoriteCodes: Set<String> = emptySet(),
    ): List<Acupoint> {
        val resolvedFilter = if (meridianCode == ALL_MERIDIANS_CODE && filter == AcupointFilterOption.CURRENT_MERIDIAN) {
            AcupointFilterOption.ALL
        } else {
            filter
        }
        val viewCodes by lazy {
            buildSet {
                val meridianFilter = if (meridianCode == ALL_MERIDIANS_CODE) null else meridianCode
                val projected = if (meridianFilter == null) {
                    snapshot.projectionsByView[viewId].orEmpty()
                } else {
                    snapshot.atlas.projectionsByViewAndMeridian[viewId]?.get(meridianFilter).orEmpty()
                }
                projected.forEach { add(it.pointCode) }
                snapshot.occludedByView[viewId].orEmpty()
                    .filter { meridianFilter == null || snapshot.pointsByCode[it.pointCode]?.meridianCode == meridianFilter }
                    .forEach { add(it.pointCode) }
            }
        }
        return snapshot.points.filter { point ->
            when (resolvedFilter) {
                AcupointFilterOption.ALL -> true
                AcupointFilterOption.CURRENT_MERIDIAN -> meridianCode == ALL_MERIDIANS_CODE || point.meridianCode == meridianCode
                AcupointFilterOption.CURRENT_VIEW -> point.code in viewCodes
                AcupointFilterOption.UPPER_LIMB -> point.region == "上肢"
                AcupointFilterOption.LOWER_LIMB -> point.region == "下肢"
                AcupointFilterOption.CHEST -> point.region == "胸部"
                AcupointFilterOption.FAVORITES -> point.code in favoriteCodes
            }
        }
    }

    fun filterSymptoms(
        snapshot: EncyclopediaSnapshot,
        selectedCategory: String,
    ): List<SymptomLesson> =
        if (selectedCategory == "全部") snapshot.learning.symptoms
        else snapshot.symptomsByCategory[selectedCategory].orEmpty()
}
