package com.example.myapplication.data.encyclopedia

import java.util.Collections

/**
 * 经学百科离线资料包契约常量。
 */
object EncyclopediaPackConstants {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val EXPECTED_PACK_ID = "meridian-encyclopedia"
    const val ASSET_ROOT = "encyclopedia"
    const val MANIFEST_FILE = "manifest.json"
    const val CATALOG_FILE = "catalog.json"
    const val ATLAS_FILE = "atlas.json"
    const val REGIONS_FILE = "regions.json"
    const val LEARNING_FILE = "learning.json"

    const val EXPECTED_MERIDIAN_COUNT = 14
    const val EXPECTED_POINT_COUNT = 361
    const val EXPECTED_SYMPTOM_CATEGORY_COUNT = 9
    const val EXPECTED_SYMPTOM_COUNT = 45
    const val EXPECTED_ASSOCIATION_COUNT = 495
    const val EXPECTED_ATLAS_VIEW_COUNT = 3
    const val EXPECTED_FRONT_PROJECTION_COUNT = 171
    const val EXPECTED_BACK_PROJECTION_COUNT = 121
    const val EXPECTED_SIDE_PROJECTION_COUNT = 86
    const val EXPECTED_UNIQUE_PROJECTED_POINT_COUNT = 357
    const val EXPECTED_OCCLUDED_POINT_COUNT = 4
    const val EXPECTED_ILLUSTRATION_COUNT = 62
    const val ST17_HEADING = "教材说明"
    const val DEFAULT_POINT_LEARNING_HEADING = "传统主治"
}

data class EncyclopediaSize(
    val width: Double,
    val height: Double,
) {
    val widthFloat: Float get() = width.toFloat()
    val heightFloat: Float get() = height.toFloat()
}

data class EncyclopediaRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val xFloat: Float get() = x.toFloat()
    val yFloat: Float get() = y.toFloat()
    val widthFloat: Float get() = width.toFloat()
    val heightFloat: Float get() = height.toFloat()
}

data class EncyclopediaSourceRef(
    val title: String,
    val url: String,
)

data class EncyclopediaReviewStatus(
    val overall: String,
    val catalog: String,
    val atlas: String,
    val regions: String,
    val learning: String,
    val illustrations: String,
)

data class StandardAttribution(
    val title: String,
    val url: String,
    val scope: String,
)

data class CoordinateSeedAttribution(
    val title: String,
    val license: String,
    val url: String,
    val licenseFileSha256: String,
    val note: String,
)

data class CoursewareAttribution(
    val title: String,
    val url: String,
    val scope: String,
)

data class EncyclopediaUpstreamAttribution(
    val standard: StandardAttribution,
    val coordinateSeed: CoordinateSeedAttribution,
    val courseware: CoursewareAttribution,
)

data class EncyclopediaPackCounts(
    val meridians: Int,
    val points: Int,
    val regionViews: Int,
    val pointLearning: Int,
    val symptomCategories: Int,
    val symptoms: Int,
    val associations: Int,
    val atlasViews: Int,
    val frontProjections: Int,
    val backProjections: Int,
    val sideProjections: Int,
    val uniqueProjectedPoints: Int,
    val occludedPoints: Int,
    val illustrations: Int,
)

data class EncyclopediaPackFileEntry(
    val path: String,
    val sha256: String,
    val bytes: Long,
)

data class EncyclopediaManifest(
    val schemaVersion: Int,
    val packId: String,
    val contentRevision: String,
    val coordinateVersion: String,
    val artworkVersion: String,
    val reviewStatus: EncyclopediaReviewStatus,
    val upstreamAttribution: EncyclopediaUpstreamAttribution,
    val counts: EncyclopediaPackCounts,
    val files: List<EncyclopediaPackFileEntry>,
)

data class Meridian(
    val code: String,
    val order: Int,
    val shortName: String,
    val name: String,
    val pointCount: Int,
    val direction: String,
    val description: String?,
)

data class Acupoint(
    val code: String,
    val meridianCode: String,
    val orderInMeridian: Int,
    val name: String,
    val pinyin: String,
    val clause: String,
    val pdfPage: Int,
    val region: String,
    val special: String,
    val location: String,
    val projectionNote: String?,
)

enum class AtlasViewId(val wireKey: String) {
    FRONT("front"),
    BACK("back"),
    SIDE("side");

    companion object {
        fun fromWire(raw: String): AtlasViewId? = entries.firstOrNull { it.wireKey == raw }
    }
}

enum class AtlasProjectionRating(val wireKey: String) {
    LANDMARK("landmark"),
    COVERED("covered"),
    VERTEBRA("vertebra"),
    FACE("face"),
    PROJECTED("projected");

    companion object {
        fun fromWire(raw: String): AtlasProjectionRating? = entries.firstOrNull { it.wireKey == raw }
    }
}

data class AtlasView(
    val id: AtlasViewId,
    val name: String,
    val artwork: String,
)

data class AtlasProjectionPoint(
    val pointCode: String,
    val meridianCode: String,
    val view: AtlasViewId,
    val x: Double,
    val y: Double,
    val rating: AtlasProjectionRating,
) {
    val xFloat: Float get() = x.toFloat()
    val yFloat: Float get() = y.toFloat()
}

data class AtlasOccludedPoint(
    val pointCode: String,
    val view: AtlasViewId,
    val regionLabel: String,
)

data class LungFrontLabelAnchor(
    val pointCode: String,
    val x: Double,
    val y: Double,
    val labelX: Double,
    val labelY: Double,
)

data class LungFrontSpec(
    val mainPath: String,
    val directionPath: String,
    val branchPath: String,
    val featuredPointCodes: List<String>,
    val wristPointCodes: List<String>,
    val labels: List<LungFrontLabelAnchor>,
    val textbookUrl: String,
    val standardUrl: String,
)

data class AtlasData(
    val bodyFrame: EncyclopediaSize,
    val artworkRect: EncyclopediaRect,
    val views: List<AtlasView>,
    val projectionNotes: Map<AtlasProjectionRating, String>,
    val projectionsByViewAndMeridian: Map<AtlasViewId, Map<String, List<AtlasProjectionPoint>>>,
    val occluded: List<AtlasOccludedPoint>,
    val lungFront: LungFrontSpec,
)

data class RegionMarker(
    val x: Double,
    val y: Double,
    val labelX: Double,
    val labelY: Double,
) {
    val xFloat: Float get() = x.toFloat()
    val yFloat: Float get() = y.toFloat()
    val labelXFloat: Float get() = labelX.toFloat()
    val labelYFloat: Float get() = labelY.toFloat()
}

data class RegionLandmarkLabel(
    val text: String,
    val x: Double,
    val y: Double,
    val leaderPath: String,
)

data class RegionView(
    val pointCode: String,
    val view: String,
    val title: String,
    val pose: String,
    val artwork: String,
    val marker: RegionMarker,
    val points: List<String>,
    val labels: List<RegionLandmarkLabel>,
    val guide: String?,
    val hint: String?,
    val focus: String?,
    val compactLabels: Boolean,
    val location: String,
)

data class RegionCatalog(
    val regionFrame: EncyclopediaSize,
    val artworkRect: EncyclopediaRect,
    val regions: List<RegionView>,
)

data class StudySectionEntry(
    val title: String,
    val content: String,
)

data class PointLearning(
    val pointCode: String,
    val heading: String?,
    val groups: List<StudySectionEntry>,
    val source: EncyclopediaSourceRef,
) {
    val displayHeading: String
        get() = heading ?: EncyclopediaPackConstants.DEFAULT_POINT_LEARNING_HEADING
}

data class SymptomPointAssociation(
    val symptomId: String,
    val pointCode: String,
    val reason: String,
    val dedicatedSource: EncyclopediaSourceRef?,
) {
    fun resolveSource(learning: PointLearning): EncyclopediaSourceRef = dedicatedSource ?: learning.source
}

data class SymptomLesson(
    val id: String,
    val name: String,
    val category: String,
    val aliases: List<String>,
    val summary: String,
    val signals: List<StudySectionEntry>,
    val clues: List<StudySectionEntry>,
    val source: EncyclopediaSourceRef,
    val additionalSource: EncyclopediaSourceRef?,
    val links: List<SymptomPointAssociation>,
)

data class LearningReferences(
    val standardPdfUrl: String,
    val acupunctureCourseBaseUrl: String,
    val lungTextbookUrl: String,
    val studyHandbookUrl: String,
    val lungTextbookPreviewUrl: String,
)

data class LearningData(
    val references: LearningReferences,
    val pointLearning: List<PointLearning>,
    val symptomCategories: List<String>,
    val symptoms: List<SymptomLesson>,
)

/**
 * 经学百科只读资料快照及内存派生双向索引。
 * 仅在资料包完整性与引用约束全部校验通过后构造并原子发布。
 */
class EncyclopediaSnapshot internal constructor(
    val manifest: EncyclopediaManifest,
    val meridians: List<Meridian>,
    val points: List<Acupoint>,
    val atlas: AtlasData,
    val regions: RegionCatalog,
    val learning: LearningData,
) {
    val meridiansByCode: Map<String, Meridian>
    val pointsByCode: Map<String, Acupoint>
    val pointsByMeridianCode: Map<String, List<Acupoint>>
    val atlasViewById: Map<AtlasViewId, AtlasView>
    val projectionsByView: Map<AtlasViewId, List<AtlasProjectionPoint>>
    val projectionsByPointCode: Map<String, Map<AtlasViewId, AtlasProjectionPoint>>
    val occludedByPointCode: Map<String, AtlasOccludedPoint>
    val occludedByView: Map<AtlasViewId, List<AtlasOccludedPoint>>
    val regionByPointCode: Map<String, RegionView>
    val regionsByArtworkPath: Map<String, List<RegionView>>
    val meridiansByRegionArtworkPath: Map<String, Set<String>>
    val pointLearningByCode: Map<String, PointLearning>
    val symptomsById: Map<String, SymptomLesson>
    val symptomsByCategory: Map<String, List<SymptomLesson>>
    val associationsByKey: Map<Pair<String, String>, SymptomPointAssociation>
    val associationsByPointCode: Map<String, List<SymptomPointAssociation>>
    val symptomsByPointCode: Map<String, List<SymptomLesson>>
    val meridianCodesBySymptomId: Map<String, List<String>>
    val manifestFilesByPath: Map<String, EncyclopediaPackFileEntry>

    init {
        meridiansByCode = Collections.unmodifiableMap(meridians.associateBy { it.code })
        pointsByCode = Collections.unmodifiableMap(points.associateBy { it.code })
        pointsByMeridianCode = Collections.unmodifiableMap(
            points.groupBy { it.meridianCode }.mapValues { (_, list) -> Collections.unmodifiableList(list) },
        )
        atlasViewById = Collections.unmodifiableMap(atlas.views.associateBy { it.id })

        val byView = linkedMapOf<AtlasViewId, List<AtlasProjectionPoint>>()
        val byPoint = linkedMapOf<String, LinkedHashMap<AtlasViewId, AtlasProjectionPoint>>()
        for (viewId in AtlasViewId.entries) {
            val channelMap = atlas.projectionsByViewAndMeridian[viewId].orEmpty()
            val flat = channelMap.values.flatten()
            byView[viewId] = Collections.unmodifiableList(flat)
            for (proj in flat) {
                byPoint.getOrPut(proj.pointCode) { linkedMapOf() }[viewId] = proj
            }
        }
        projectionsByView = Collections.unmodifiableMap(byView)
        projectionsByPointCode = Collections.unmodifiableMap(
            byPoint.mapValues { (_, map) -> Collections.unmodifiableMap(map) },
        )

        occludedByPointCode = Collections.unmodifiableMap(atlas.occluded.associateBy { it.pointCode })
        occludedByView = Collections.unmodifiableMap(
            atlas.occluded.groupBy { it.view }.mapValues { (_, list) -> Collections.unmodifiableList(list) },
        )

        regionByPointCode = Collections.unmodifiableMap(regions.regions.associateBy { it.pointCode })
        regionsByArtworkPath = Collections.unmodifiableMap(
            regions.regions.groupBy { it.artwork }.mapValues { (_, list) -> Collections.unmodifiableList(list) },
        )
        meridiansByRegionArtworkPath = Collections.unmodifiableMap(
            regionsByArtworkPath.mapValues { (_, regionList) ->
                val set = LinkedHashSet<String>()
                for (r in regionList) {
                    pointsByCode[r.pointCode]?.let { set.add(it.meridianCode) }
                }
                Collections.unmodifiableSet(set)
            },
        )

        pointLearningByCode = Collections.unmodifiableMap(learning.pointLearning.associateBy { it.pointCode })
        symptomsById = Collections.unmodifiableMap(learning.symptoms.associateBy { it.id })
        symptomsByCategory = Collections.unmodifiableMap(
            learning.symptoms.groupBy { it.category }.mapValues { (_, list) -> Collections.unmodifiableList(list) },
        )

        val assocKeyMap = linkedMapOf<Pair<String, String>, SymptomPointAssociation>()
        val assocByPoint = linkedMapOf<String, MutableList<SymptomPointAssociation>>()
        val symByPoint = linkedMapOf<String, MutableList<SymptomLesson>>()
        val merBySymptom = linkedMapOf<String, List<String>>()

        for (symptom in learning.symptoms) {
            val meridianOrder = LinkedHashSet<String>()
            for (link in symptom.links) {
                assocKeyMap[symptom.id to link.pointCode] = link
                assocByPoint.getOrPut(link.pointCode) { mutableListOf() }.add(link)
                symByPoint.getOrPut(link.pointCode) { mutableListOf() }.add(symptom)
                pointsByCode[link.pointCode]?.let { meridianOrder.add(it.meridianCode) }
            }
            merBySymptom[symptom.id] = Collections.unmodifiableList(meridianOrder.toList())
        }

        associationsByKey = Collections.unmodifiableMap(assocKeyMap)
        associationsByPointCode = Collections.unmodifiableMap(
            assocByPoint.mapValues { (_, list) -> Collections.unmodifiableList(list) },
        )
        symptomsByPointCode = Collections.unmodifiableMap(
            symByPoint.mapValues { (_, list) -> Collections.unmodifiableList(list) },
        )
        meridianCodesBySymptomId = Collections.unmodifiableMap(merBySymptom)
        manifestFilesByPath = Collections.unmodifiableMap(manifest.files.associateBy { it.path })
    }

    fun meridianOf(pointCode: String): Meridian? =
        pointsByCode[pointCode]?.let { meridiansByCode[it.meridianCode] }

    fun pointByCode(code: String): Acupoint? = pointsByCode[code]

    fun pointsForMeridian(meridianCode: String): List<Acupoint> =
        pointsByMeridianCode[meridianCode].orEmpty()

    fun regionForPoint(pointCode: String): RegionView? = regionByPointCode[pointCode]

    fun learningForPoint(pointCode: String): PointLearning? = pointLearningByCode[pointCode]

    fun symptomsForPoint(pointCode: String): List<SymptomLesson> =
        symptomsByPointCode[pointCode].orEmpty()

    fun pointsForSymptom(symptomId: String): List<Acupoint> =
        symptomsById[symptomId]?.links?.mapNotNull { pointsByCode[it.pointCode] }.orEmpty()

    fun preferredViewForMeridian(meridianCode: String): AtlasViewId {
        return AtlasViewId.entries.maxByOrNull { viewId ->
            atlas.projectionsByViewAndMeridian[viewId]?.get(meridianCode)?.size ?: 0
        } ?: AtlasViewId.FRONT
    }

    fun viewsContainingPoint(pointCode: String): List<AtlasViewId> {
        val occludedView = occludedByPointCode[pointCode]?.view
        if (occludedView != null) return listOf(occludedView)
        return projectionsByPointCode[pointCode]?.keys?.toList().orEmpty()
    }

    fun allReferencedArtworkPaths(): Set<String> {
        val paths = LinkedHashSet<String>()
        for (view in atlas.views) paths.add(view.artwork)
        for (region in regions.regions) paths.add(region.artwork)
        return Collections.unmodifiableSet(paths)
    }

    internal fun withContentRevision(revision: String): EncyclopediaSnapshot = EncyclopediaSnapshot(
        manifest = manifest.copy(contentRevision = revision),
        meridians = meridians,
        points = points,
        atlas = atlas,
        regions = regions,
        learning = learning,
    )
}
