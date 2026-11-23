package com.example.myapplication.ui.encyclopedia

import com.example.myapplication.data.encyclopedia.AtlasViewId
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.ui.encyclopedia.atlas.ALL_MERIDIANS_CODE
import com.example.myapplication.ui.encyclopedia.atlas.AtlasCameraMath
import com.example.myapplication.ui.encyclopedia.atlas.AtlasCameraState
import com.example.myapplication.ui.encyclopedia.atlas.AtlasFrames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal enum class EncyclopediaTab(val wireKey: String, val label: String) {
    MERIDIAN("meridian", "经络"),
    POINT("point", "穴位"),
    SYMPTOM("symptom", "病症");

    companion object {
        fun fromWire(raw: String?): EncyclopediaTab =
            entries.firstOrNull { it.wireKey == raw } ?: MERIDIAN
    }
}

internal enum class AcupointDetailTab(val wireKey: String, val label: String) {
    LOCATION("location", "定位"),
    MERIDIAN("meridian", "经属"),
    INDICATIONS("indications", "主治"),
    SOURCE("source", "来源");

    companion object {
        fun fromWire(raw: String?): AcupointDetailTab =
            entries.firstOrNull { it.wireKey == raw } ?: LOCATION
    }
}

internal sealed interface EncyclopediaPageFrame {
    data class Root(
        val tab: EncyclopediaTab = EncyclopediaTab.MERIDIAN,
        val meridianCode: String = ALL_MERIDIANS_CODE,
        val viewId: AtlasViewId = AtlasViewId.FRONT,
        val bodyCamera: AtlasCameraState = AtlasCameraState(),
        val peekPointCode: String? = null,
        val fullscreen: Boolean = false,
        val pointFilter: AcupointFilterOption = AcupointFilterOption.ALL,
        val pointListIndex: Int = 0,
        val pointListOffset: Int = 0,
        val symptomCategory: String = "全部",
        val symptomListIndex: Int = 0,
        val symptomListOffset: Int = 0,
    ) : EncyclopediaPageFrame

    data class Region(
        val pointCode: String,
        val selectedPointCode: String,
        val selectedInRegion: Boolean,
        val camera: AtlasCameraState = AtlasCameraState(),
        val scrollOffset: Int = 0,
    ) : EncyclopediaPageFrame

    data class PointDetail(
        val pointCode: String,
        val detailTab: AcupointDetailTab = AcupointDetailTab.LOCATION,
        val scrollOffset: Int = 0,
    ) : EncyclopediaPageFrame

    data class SymptomDetail(
        val symptomId: String,
        val openClues: Set<Int> = emptySet(),
        val scrollOffset: Int = 0,
    ) : EncyclopediaPageFrame
}

internal enum class EncyclopediaSheet {
    NONE,
    MERIDIAN_PICKER,
    SOURCE_REFERENCES,
}

internal data class EncyclopediaUiState(
    val root: EncyclopediaPageFrame.Root = EncyclopediaPageFrame.Root(),
    val backStack: List<EncyclopediaPageFrame> = emptyList(),
    val searchQuery: String = "",
    val activeSheet: EncyclopediaSheet = EncyclopediaSheet.NONE,
    val notice: String? = null,
) {
    val activePage: EncyclopediaPageFrame
        get() = backStack.lastOrNull() ?: root

    val activeRoot: EncyclopediaPageFrame.Root
        get() = (activePage as? EncyclopediaPageFrame.Root) ?: root

    val isAtRoot: Boolean
        get() = backStack.isEmpty()

    val showsBottomDockControls: Boolean
        get() = activePage is EncyclopediaPageFrame.Root && !activeRoot.fullscreen

    fun backButtonLabel(snapshot: EncyclopediaSnapshot?): String {
        val current = activePage
        val previous = if (backStack.size >= 2) backStack[backStack.size - 2] else root
        return when (current) {
            is EncyclopediaPageFrame.Root -> {
                val origin = atlasOriginSymptom(snapshot)
                if (origin != null) "返回$origin" else "返回"
            }
            is EncyclopediaPageFrame.Region ->
                if (previous is EncyclopediaPageFrame.PointDetail) "返回穴位详情" else "返回全身图"
            is EncyclopediaPageFrame.PointDetail -> "返回上一页"
            is EncyclopediaPageFrame.SymptomDetail -> when {
                previous is EncyclopediaPageFrame.PointDetail -> "返回穴位"
                previous is EncyclopediaPageFrame.Root && previous.tab == EncyclopediaTab.SYMPTOM -> "返回病症列表"
                else -> "返回百科"
            }
        }
    }

    fun atlasOriginSymptom(snapshot: EncyclopediaSnapshot?): String? {
        if (activePage !is EncyclopediaPageFrame.Root || backStack.isEmpty()) return null
        val previous = if (backStack.size >= 2) backStack[backStack.size - 2] else root
        val symptom = previous as? EncyclopediaPageFrame.SymptomDetail ?: return null
        return snapshot?.symptomsById[symptom.symptomId]?.name
    }
}

/**
 * 经学百科纯状态机与返回栈调度器。
 * - 统一管理页签、图谱相机、局部图、穴位详情与病症学习的双向跳转及现场恢复；
 * - 支持稳定 ID + 相机/展开项/滚动锚点往返序列化。
 */
internal class EncyclopediaNavigator(
    initialState: EncyclopediaUiState = EncyclopediaUiState(),
) {
    var state: EncyclopediaUiState = initialState
        private set

    fun clearNotice() {
        if (state.notice != null) state = state.copy(notice = null)
    }

    fun updateSearchQuery(query: String) {
        state = state.copy(searchQuery = query, notice = null)
    }

    fun switchTab(tab: EncyclopediaTab) {
        val updatedRoot = state.root.copy(
            tab = tab,
            peekPointCode = null,
            fullscreen = if (tab == EncyclopediaTab.MERIDIAN) state.root.fullscreen else false,
        )
        state = state.copy(
            root = updatedRoot,
            backStack = emptyList(),
            searchQuery = "",
            activeSheet = EncyclopediaSheet.NONE,
            notice = null,
        )
    }

    private fun updateActiveRoot(
        clearSheet: Boolean = false,
        clearNotice: Boolean = false,
        transform: (EncyclopediaPageFrame.Root) -> EncyclopediaPageFrame.Root,
    ) {
        val topRoot = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Root
        state = if (topRoot != null) {
            state.copy(
                backStack = state.backStack.dropLast(1) + transform(topRoot),
                activeSheet = if (clearSheet) EncyclopediaSheet.NONE else state.activeSheet,
                notice = if (clearNotice) null else state.notice,
            )
        } else {
            state.copy(
                root = transform(state.root),
                activeSheet = if (clearSheet) EncyclopediaSheet.NONE else state.activeSheet,
                notice = if (clearNotice) null else state.notice,
            )
        }
    }

    fun selectMeridian(snapshot: EncyclopediaSnapshot, meridianCode: String) {
        if (meridianCode != ALL_MERIDIANS_CODE && snapshot.meridiansByCode[meridianCode] == null) {
            state = state.copy(notice = "未找到指定经络条目", activeSheet = EncyclopediaSheet.NONE)
            return
        }
        val currentRoot = state.activeRoot
        val targetView = if (meridianCode == ALL_MERIDIANS_CODE || meridianCode == currentRoot.meridianCode) {
            currentRoot.viewId
        } else {
            snapshot.preferredViewForMeridian(meridianCode)
        }
        updateActiveRoot(clearSheet = true, clearNotice = true) { rootFrame ->
            rootFrame.copy(
                tab = EncyclopediaTab.MERIDIAN,
                meridianCode = meridianCode,
                viewId = targetView,
                bodyCamera = AtlasCameraMath.reset(),
                peekPointCode = null,
            )
        }
    }

    fun selectView(viewId: AtlasViewId) {
        val currentRoot = state.activeRoot
        if (currentRoot.viewId == viewId) return
        updateActiveRoot(clearNotice = true) { rootFrame ->
            rootFrame.copy(
                viewId = viewId,
                bodyCamera = AtlasCameraMath.reset(),
                peekPointCode = null,
            )
        }
    }

    fun updateBodyCamera(camera: AtlasCameraState) {
        val clamped = AtlasCameraMath.clamp(AtlasFrames.Body, camera.zoom, camera.panX, camera.panY)
        updateActiveRoot { it.copy(bodyCamera = clamped) }
    }

    fun zoomBody(zoomIn: Boolean) {
        val next = AtlasCameraMath.zoomStep(AtlasFrames.Body, state.activeRoot.bodyCamera, zoomIn)
        updateBodyCamera(next)
    }

    fun resetBodyCamera() {
        updateActiveRoot { it.copy(bodyCamera = AtlasCameraMath.reset()) }
    }

    fun toggleFullscreen(fullscreen: Boolean? = null) {
        val next = fullscreen ?: !state.activeRoot.fullscreen
        updateActiveRoot(clearSheet = true) { it.copy(fullscreen = next) }
    }

    fun dismissPeek() {
        if (state.activeRoot.peekPointCode != null) {
            updateActiveRoot { it.copy(peekPointCode = null) }
        }
    }

    fun tapAtlasPoint(snapshot: EncyclopediaSnapshot, pointCode: String) {
        if (snapshot.pointsByCode[pointCode] == null) {
            state = state.copy(notice = "未找到对应穴位条目")
            return
        }
        val hasRegion = snapshot.regionByPointCode[pointCode] != null
        if (hasRegion && pointCode != "LU5") {
            openRegion(snapshot, pointCode)
        } else {
            updateActiveRoot(clearNotice = true) { it.copy(peekPointCode = pointCode) }
        }
    }

    fun openPointDetail(
        snapshot: EncyclopediaSnapshot,
        pointCode: String,
        detailTab: AcupointDetailTab = AcupointDetailTab.LOCATION,
    ) {
        if (snapshot.pointsByCode[pointCode] == null) {
            state = state.copy(notice = "未找到穴位 $pointCode，请从当前列表选择其他穴位")
            return
        }
        val current = state.activePage
        val previous = if (state.backStack.size >= 2) state.backStack[state.backStack.size - 2] else null
        if (current is EncyclopediaPageFrame.Region &&
            previous is EncyclopediaPageFrame.PointDetail &&
            previous.pointCode == pointCode
        ) {
            back()
            return
        }
        val pushed = EncyclopediaPageFrame.PointDetail(
            pointCode = pointCode,
            detailTab = detailTab,
        )
        val topRoot = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Root
        when {
            state.isAtRoot -> {
                val restoredCaller = state.root
                state = state.copy(
                    root = state.root.copy(peekPointCode = null, fullscreen = false),
                    backStack = listOf(restoredCaller, pushed),
                    searchQuery = "",
                    activeSheet = EncyclopediaSheet.NONE,
                    notice = null,
                )
            }
            topRoot != null -> {
                state = state.copy(
                    backStack = state.backStack.dropLast(1) + topRoot.copy(fullscreen = false) + pushed,
                    searchQuery = "",
                    activeSheet = EncyclopediaSheet.NONE,
                    notice = null,
                )
            }
            else -> {
                state = state.copy(
                    backStack = state.backStack + pushed,
                    searchQuery = "",
                    activeSheet = EncyclopediaSheet.NONE,
                    notice = null,
                )
            }
        }
    }

    fun selectDetailTab(tab: AcupointDetailTab) {
        val current = state.backStack.lastOrNull() as? EncyclopediaPageFrame.PointDetail ?: return
        state = state.copy(
            backStack = state.backStack.dropLast(1) + current.copy(detailTab = tab),
        )
    }

    fun updateDetailScroll(scrollOffset: Int) {
        val current = state.backStack.lastOrNull() ?: return
        val updated = when (current) {
            is EncyclopediaPageFrame.PointDetail -> current.copy(scrollOffset = scrollOffset.coerceAtLeast(0))
            is EncyclopediaPageFrame.SymptomDetail -> current.copy(scrollOffset = scrollOffset.coerceAtLeast(0))
            is EncyclopediaPageFrame.Region -> current.copy(scrollOffset = scrollOffset.coerceAtLeast(0))
            is EncyclopediaPageFrame.Root -> return
        }
        state = state.copy(backStack = state.backStack.dropLast(1) + updated)
    }

    fun openRegion(snapshot: EncyclopediaSnapshot, pointCode: String) {
        if (snapshot.regionByPointCode[pointCode] == null) {
            state = state.copy(notice = "未找到穴位 $pointCode 的局部视图")
            return
        }
        val regionFrame = EncyclopediaPageFrame.Region(
            pointCode = pointCode,
            selectedPointCode = pointCode,
            selectedInRegion = pointCode != "LU5",
            camera = AtlasCameraMath.reset(),
        )
        val topRoot = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Root
        when {
            state.isAtRoot -> {
                val rootWithPeek = if (state.root.tab == EncyclopediaTab.MERIDIAN) {
                    state.root.copy(peekPointCode = pointCode)
                } else {
                    state.root
                }
                state = state.copy(
                    root = rootWithPeek.copy(fullscreen = false, peekPointCode = null),
                    backStack = listOf(rootWithPeek, regionFrame),
                    activeSheet = EncyclopediaSheet.NONE,
                    notice = null,
                )
            }
            topRoot != null -> {
                val rootWithPeek = if (topRoot.tab == EncyclopediaTab.MERIDIAN) {
                    topRoot.copy(peekPointCode = pointCode, fullscreen = false)
                } else {
                    topRoot.copy(fullscreen = false)
                }
                state = state.copy(
                    backStack = state.backStack.dropLast(1) + rootWithPeek + regionFrame,
                    activeSheet = EncyclopediaSheet.NONE,
                    notice = null,
                )
            }
            else -> {
                state = state.copy(
                    backStack = state.backStack + regionFrame,
                    activeSheet = EncyclopediaSheet.NONE,
                    notice = null,
                )
            }
        }
    }

    fun selectRegionPoint(snapshot: EncyclopediaSnapshot, pointCode: String) {
        val current = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Region ?: return
        if (snapshot.regionByPointCode[pointCode] == null) return
        state = state.copy(
            backStack = state.backStack.dropLast(1) + current.copy(
                selectedPointCode = pointCode,
                selectedInRegion = true,
            ),
        )
    }

    fun updateRegionCamera(camera: AtlasCameraState) {
        val current = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Region ?: return
        val clamped = AtlasCameraMath.clamp(AtlasFrames.Region, camera.zoom, camera.panX, camera.panY)
        state = state.copy(
            backStack = state.backStack.dropLast(1) + current.copy(camera = clamped),
        )
    }

    fun zoomRegion(zoomIn: Boolean) {
        val current = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Region ?: return
        val next = AtlasCameraMath.zoomStep(AtlasFrames.Region, current.camera, zoomIn)
        updateRegionCamera(next)
    }

    fun resetRegionCamera() {
        val current = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Region ?: return
        state = state.copy(
            backStack = state.backStack.dropLast(1) + current.copy(camera = AtlasCameraMath.reset()),
        )
    }

    fun openSymptom(snapshot: EncyclopediaSnapshot, symptomId: String) {
        if (snapshot.symptomsById[symptomId] == null) {
            state = state.copy(notice = "未找到对应病症条目，已留在当前页面")
            return
        }
        val frame = EncyclopediaPageFrame.SymptomDetail(symptomId = symptomId)
        val nextStack = if (state.isAtRoot) {
            listOf(state.root, frame)
        } else {
            state.backStack + frame
        }
        state = state.copy(
            backStack = nextStack,
            searchQuery = "",
            activeSheet = EncyclopediaSheet.NONE,
            notice = null,
        )
    }

    fun toggleClue(clueIndex: Int) {
        val current = state.backStack.lastOrNull() as? EncyclopediaPageFrame.SymptomDetail ?: return
        val nextClues = if (clueIndex in current.openClues) current.openClues - clueIndex else current.openClues + clueIndex
        state = state.copy(
            backStack = state.backStack.dropLast(1) + current.copy(openClues = nextClues),
        )
    }

    fun openMeridianFromSymptom(snapshot: EncyclopediaSnapshot, meridianCode: String) {
        if (snapshot.meridiansByCode[meridianCode] == null) {
            state = state.copy(notice = "未找到对应经络条目")
            return
        }
        val targetView = snapshot.preferredViewForMeridian(meridianCode)
        val atlasFrame = EncyclopediaPageFrame.Root(
            tab = EncyclopediaTab.MERIDIAN,
            meridianCode = meridianCode,
            viewId = targetView,
            bodyCamera = AtlasCameraMath.reset(),
            pointFilter = state.root.pointFilter,
            symptomCategory = state.root.symptomCategory,
        )
        val nextStack = if (state.isAtRoot) listOf(state.root, atlasFrame) else state.backStack + atlasFrame
        state = state.copy(
            backStack = nextStack,
            activeSheet = EncyclopediaSheet.NONE,
            notice = null,
        )
    }

    fun locatePointOnAtlas(snapshot: EncyclopediaSnapshot, pointCode: String) {
        val point = snapshot.pointsByCode[pointCode]
        if (point == null) {
            state = state.copy(notice = "未找到穴位 $pointCode，无法定位到图谱")
            return
        }
        // Check if an atlas history frame already has this point peeked (matching prototype.js:222)
        val originIdx = state.backStack.indexOfLast { frame ->
            frame is EncyclopediaPageFrame.Root &&
                frame.tab == EncyclopediaTab.MERIDIAN &&
                frame.peekPointCode == pointCode
        }
        if (originIdx >= 0) {
            val restored = state.backStack[originIdx] as EncyclopediaPageFrame.Root
            val remaining = if (originIdx == 0) emptyList() else state.backStack.take(originIdx)
            state = state.copy(
                root = restored,
                backStack = remaining,
                activeSheet = EncyclopediaSheet.NONE,
                notice = null,
            )
            return
        }

        val targetView = snapshot.viewsContainingPoint(pointCode).firstOrNull()
            ?: snapshot.preferredViewForMeridian(point.meridianCode)
        val targetAtlas = EncyclopediaPageFrame.Root(
            tab = EncyclopediaTab.MERIDIAN,
            meridianCode = point.meridianCode,
            viewId = targetView,
            bodyCamera = AtlasCameraMath.reset(),
            peekPointCode = pointCode,
            pointFilter = state.root.pointFilter,
            symptomCategory = state.root.symptomCategory,
        )
        // Return cleanly to interactive root atlas focused on the target point
        state = state.copy(
            root = targetAtlas,
            backStack = emptyList(),
            searchQuery = "",
            activeSheet = EncyclopediaSheet.NONE,
            notice = null,
        )
    }

    fun selectPointFilter(filter: AcupointFilterOption) {
        state = state.copy(
            root = state.root.copy(
                pointFilter = filter,
                pointListIndex = 0,
                pointListOffset = 0,
            ),
        )
    }

    fun updatePointListScroll(index: Int, offset: Int) {
        state = state.copy(
            root = state.root.copy(
                pointListIndex = index.coerceAtLeast(0),
                pointListOffset = offset.coerceAtLeast(0),
            ),
        )
    }

    fun selectSymptomCategory(category: String) {
        state = state.copy(
            root = state.root.copy(
                symptomCategory = category,
                symptomListIndex = 0,
                symptomListOffset = 0,
            ),
        )
    }

    fun updateSymptomListScroll(index: Int, offset: Int) {
        state = state.copy(
            root = state.root.copy(
                symptomListIndex = index.coerceAtLeast(0),
                symptomListOffset = offset.coerceAtLeast(0),
            ),
        )
    }

    fun openPointsTabWithFilter(filter: AcupointFilterOption) {
        state = state.copy(
            root = state.root.copy(
                tab = EncyclopediaTab.POINT,
                pointFilter = filter,
                pointListIndex = 0,
                pointListOffset = 0,
                peekPointCode = null,
                fullscreen = false,
            ),
            backStack = emptyList(),
            activeSheet = EncyclopediaSheet.NONE,
            notice = null,
        )
    }

    fun openSheet(sheet: EncyclopediaSheet) {
        state = state.copy(activeSheet = sheet)
    }

    fun dismissSheet() {
        if (state.activeSheet != EncyclopediaSheet.NONE) {
            state = state.copy(activeSheet = EncyclopediaSheet.NONE)
        }
    }

    /**
     * 统一返回分发：优先级依次为 弹层 -> 非首页栈（还原先前的全景/标签/展开项/滚动位置） -> 全屏图谱 -> 快速预览。
     * 返回 true 表示已消费本次 Back；false 表示已在百科根界面（可交回外层主导航）。
     */
    fun back(): Boolean {
        if (state.activeSheet != EncyclopediaSheet.NONE) {
            state = state.copy(activeSheet = EncyclopediaSheet.NONE)
            return true
        }
        if (state.backStack.isNotEmpty()) {
            val activeRootOnTop = state.backStack.lastOrNull() as? EncyclopediaPageFrame.Root
            if (activeRootOnTop != null) {
                if (activeRootOnTop.fullscreen) {
                    updateActiveRoot { it.copy(fullscreen = false) }
                    return true
                }
                if (activeRootOnTop.peekPointCode != null) {
                    updateActiveRoot { it.copy(peekPointCode = null) }
                    return true
                }
            }
            val poppedStack = state.backStack.dropLast(1)
            if (poppedStack.size == 1 && poppedStack.first() is EncyclopediaPageFrame.Root) {
                val restoredRoot = poppedStack.first() as EncyclopediaPageFrame.Root
                state = state.copy(
                    root = restoredRoot,
                    backStack = emptyList(),
                    notice = null,
                )
            } else {
                state = state.copy(
                    backStack = poppedStack,
                    notice = null,
                )
            }
            return true
        }
        if (state.root.fullscreen) {
            state = state.copy(root = state.root.copy(fullscreen = false))
            return true
        }
        if (state.root.peekPointCode != null) {
            state = state.copy(root = state.root.copy(peekPointCode = null))
            return true
        }
        return false
    }

    fun exportCheckpointJson(): JsonObject = buildJsonObject {
        put("encTab", state.root.tab.wireKey)
        put("encMeridian", state.root.meridianCode)
        put("encView", state.root.viewId.wireKey)
        put("encZoom", state.root.bodyCamera.zoom)
        put("encPanX", state.root.bodyCamera.panX)
        put("encPanY", state.root.bodyCamera.panY)
        state.root.peekPointCode?.let { put("encPeek", it) }
        put("encFull", state.root.fullscreen)
        put("encPointFilter", state.root.pointFilter.name)
        put("encPointListIndex", state.root.pointListIndex)
        put("encPointListOffset", state.root.pointListOffset)
        put("encSymptomCategory", state.root.symptomCategory)
        put("encSymptomListIndex", state.root.symptomListIndex)
        put("encSymptomListOffset", state.root.symptomListOffset)
        put("encStack", JsonArray(state.backStack.map(::encodeFrame)))
    }

    fun restoreFromCheckpointJson(snapshot: EncyclopediaSnapshot, json: JsonObject) {
        val tab = EncyclopediaTab.fromWire((json["encTab"] as? JsonPrimitive)?.contentOrNull)
        val rawMeridian = (json["encMeridian"] as? JsonPrimitive)?.contentOrNull ?: ALL_MERIDIANS_CODE
        val meridian = if (rawMeridian == ALL_MERIDIANS_CODE || snapshot.meridiansByCode.containsKey(rawMeridian)) {
            rawMeridian
        } else ALL_MERIDIANS_CODE
        val viewId = (json["encView"] as? JsonPrimitive)?.contentOrNull?.let(AtlasViewId::fromWire) ?: AtlasViewId.FRONT
        val zoom = (json["encZoom"] as? JsonPrimitive)?.floatOrNull ?: 1f
        val panX = (json["encPanX"] as? JsonPrimitive)?.floatOrNull ?: 0f
        val panY = (json["encPanY"] as? JsonPrimitive)?.floatOrNull ?: 0f
        val peek = (json["encPeek"] as? JsonPrimitive)?.contentOrNull?.takeIf { snapshot.pointsByCode.containsKey(it) }
        val fullscreen = (json["encFull"] as? JsonPrimitive)?.booleanOrNull ?: false
        val pointFilter = (json["encPointFilter"] as? JsonPrimitive)?.contentOrNull
            ?.let { raw -> AcupointFilterOption.entries.firstOrNull { it.name == raw } }
            ?: AcupointFilterOption.ALL
        val symptomCat = (json["encSymptomCategory"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it == "全部" || snapshot.symptomsByCategory.containsKey(it) }
            ?: "全部"
        val restoredRoot = EncyclopediaPageFrame.Root(
            tab = tab,
            meridianCode = meridian,
            viewId = viewId,
            bodyCamera = AtlasCameraMath.clamp(AtlasFrames.Body, zoom, panX, panY),
            peekPointCode = peek,
            fullscreen = fullscreen,
            pointFilter = pointFilter,
            pointListIndex = ((json["encPointListIndex"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
            pointListOffset = ((json["encPointListOffset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
            symptomCategory = symptomCat,
            symptomListIndex = ((json["encSymptomListIndex"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
            symptomListOffset = ((json["encSymptomListOffset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
        )
        val rawStack = (json["encStack"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { obj -> decodeFrame(snapshot, obj) } }
            .orEmpty()
        state = EncyclopediaUiState(
            root = restoredRoot,
            backStack = rawStack,
            activeSheet = EncyclopediaSheet.NONE,
        )
    }

    private fun encodeFrame(frame: EncyclopediaPageFrame): JsonObject = when (frame) {
        is EncyclopediaPageFrame.Root -> buildJsonObject {
            put("type", "root")
            put("tab", frame.tab.wireKey)
            put("meridian", frame.meridianCode)
            put("view", frame.viewId.wireKey)
            put("zoom", frame.bodyCamera.zoom)
            put("panX", frame.bodyCamera.panX)
            put("panY", frame.bodyCamera.panY)
            frame.peekPointCode?.let { put("peek", it) }
            put("full", frame.fullscreen)
            put("pointFilter", frame.pointFilter.name)
            put("pointListIndex", frame.pointListIndex)
            put("pointListOffset", frame.pointListOffset)
            put("symptomCategory", frame.symptomCategory)
            put("symptomListIndex", frame.symptomListIndex)
            put("symptomListOffset", frame.symptomListOffset)
        }
        is EncyclopediaPageFrame.Region -> buildJsonObject {
            put("type", "region")
            put("pointCode", frame.pointCode)
            put("selectedPointCode", frame.selectedPointCode)
            put("selectedInRegion", frame.selectedInRegion)
            put("zoom", frame.camera.zoom)
            put("panX", frame.camera.panX)
            put("panY", frame.camera.panY)
            put("scroll", frame.scrollOffset)
        }
        is EncyclopediaPageFrame.PointDetail -> buildJsonObject {
            put("type", "point")
            put("pointCode", frame.pointCode)
            put("detailTab", frame.detailTab.wireKey)
            put("scroll", frame.scrollOffset)
        }
        is EncyclopediaPageFrame.SymptomDetail -> buildJsonObject {
            put("type", "symptom")
            put("symptomId", frame.symptomId)
            put("openClues", JsonArray(frame.openClues.sorted().map(::JsonPrimitive)))
            put("scroll", frame.scrollOffset)
        }
    }

    private fun decodeFrame(snapshot: EncyclopediaSnapshot, obj: JsonObject): EncyclopediaPageFrame? {
        return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "root" -> {
                val meridian = (obj["meridian"] as? JsonPrimitive)?.contentOrNull ?: ALL_MERIDIANS_CODE
                if (meridian != ALL_MERIDIANS_CODE && !snapshot.meridiansByCode.containsKey(meridian)) return null
                val viewId = (obj["view"] as? JsonPrimitive)?.contentOrNull?.let(AtlasViewId::fromWire) ?: AtlasViewId.FRONT
                val zoom = (obj["zoom"] as? JsonPrimitive)?.floatOrNull ?: 1f
                val panX = (obj["panX"] as? JsonPrimitive)?.floatOrNull ?: 0f
                val panY = (obj["panY"] as? JsonPrimitive)?.floatOrNull ?: 0f
                EncyclopediaPageFrame.Root(
                    tab = EncyclopediaTab.fromWire((obj["tab"] as? JsonPrimitive)?.contentOrNull),
                    meridianCode = meridian,
                    viewId = viewId,
                    bodyCamera = AtlasCameraMath.clamp(AtlasFrames.Body, zoom, panX, panY),
                    peekPointCode = (obj["peek"] as? JsonPrimitive)?.contentOrNull?.takeIf { snapshot.pointsByCode.containsKey(it) },
                    fullscreen = (obj["full"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    pointFilter = (obj["pointFilter"] as? JsonPrimitive)?.contentOrNull
                        ?.let { raw -> AcupointFilterOption.entries.firstOrNull { it.name == raw } }
                        ?: AcupointFilterOption.ALL,
                    pointListIndex = ((obj["pointListIndex"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                    pointListOffset = ((obj["pointListOffset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                    symptomCategory = (obj["symptomCategory"] as? JsonPrimitive)?.contentOrNull ?: "全部",
                    symptomListIndex = ((obj["symptomListIndex"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                    symptomListOffset = ((obj["symptomListOffset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                )
            }
            "region" -> {
                val code = (obj["pointCode"] as? JsonPrimitive)?.contentOrNull ?: return null
                if (!snapshot.regionByPointCode.containsKey(code)) return null
                val selected = (obj["selectedPointCode"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { snapshot.regionByPointCode.containsKey(it) } ?: code
                val zoom = (obj["zoom"] as? JsonPrimitive)?.floatOrNull ?: 1f
                val panX = (obj["panX"] as? JsonPrimitive)?.floatOrNull ?: 0f
                val panY = (obj["panY"] as? JsonPrimitive)?.floatOrNull ?: 0f
                EncyclopediaPageFrame.Region(
                    pointCode = code,
                    selectedPointCode = selected,
                    selectedInRegion = (obj["selectedInRegion"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    camera = AtlasCameraMath.clamp(AtlasFrames.Region, zoom, panX, panY),
                    scrollOffset = ((obj["scroll"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                )
            }
            "point" -> {
                val code = (obj["pointCode"] as? JsonPrimitive)?.contentOrNull ?: return null
                if (!snapshot.pointsByCode.containsKey(code)) return null
                EncyclopediaPageFrame.PointDetail(
                    pointCode = code,
                    detailTab = AcupointDetailTab.fromWire((obj["detailTab"] as? JsonPrimitive)?.contentOrNull),
                    scrollOffset = ((obj["scroll"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                )
            }
            "symptom" -> {
                val id = (obj["symptomId"] as? JsonPrimitive)?.contentOrNull ?: return null
                if (!snapshot.symptomsById.containsKey(id)) return null
                val clues = (obj["openClues"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    ?.toSet()
                    .orEmpty()
                EncyclopediaPageFrame.SymptomDetail(
                    symptomId = id,
                    openClues = clues,
                    scrollOffset = ((obj["scroll"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                )
            }
            else -> null
        }
    }
}
