package com.example.myapplication.ui.navigation

enum class BackPage { READER, CHILD, ROOT }

enum class BackSelection { TEXT, ANNOTATION_TOOLBAR, DRAWING }

/** The state owner supplies the actual top layer, including its stable local key. */
sealed interface BackLayer {
    val key: String

    data class Overlay(override val key: String) : BackLayer {
        init { require(key.isNotBlank()) { "Overlay key must not be blank" } }
    }

    data class Editor(override val key: String, val isDirty: Boolean) : BackLayer {
        init { require(key.isNotBlank()) { "Editor key must not be blank" } }
    }

    data class Panel(override val key: String) : BackLayer {
        init { require(key.isNotBlank()) { "Panel key must not be blank" } }
    }
}

/**
 * One current UI snapshot, not a collection of competing BackHandlers.
 * [topLayer] identifies the actual foremost overlay/editor/panel. A covered editor is
 * not acted on until its covering layer has closed and the owner supplies a new snapshot.
 */
data class BackState(
    val page: BackPage = BackPage.ROOT,
    val imeVisible: Boolean = false,
    val topLayer: BackLayer? = null,
    val selection: BackSelection? = null,
    val isSourceReader: Boolean = false,
    val isImmersiveReader: Boolean = false,
) {
    init {
        require(page == BackPage.READER || (!isSourceReader && !isImmersiveReader)) {
            "Source and immersive flags require a reader page"
        }
    }
}

/** One request produces one instruction; only the state owner executes it. */
sealed interface BackDecision {
    /** Let the system dismiss the IME; do not also navigate for this request. */
    data object SystemIme : BackDecision
    /** Save the draft or ask for save/discard/continue; never implicitly discard it. */
    data class ProtectDraft(val editor: BackLayer.Editor) : BackDecision
    data class CloseLayer(val layer: BackLayer) : BackDecision
    data class ClearSelection(val selection: BackSelection) : BackDecision
    /** End drawing according to its existing save rules, not the text-clear path. */
    data object FinishDrawing : BackDecision
    /** Save the source reader position and restore the caller via its return point. */
    data object ReturnToCaller : BackDecision
    data object ExitImmersive : BackDecision
    data object SaveReadingPositionAndLeave : BackDecision
    data object PopPage : BackDecision
    data object ActivityDefault : BackDecision

    companion object {
        /** Pure and deterministic: this neither updates state nor executes navigation/Stop. */
        fun resolve(state: BackState): BackDecision = when {
            state.imeVisible -> SystemIme
            state.topLayer is BackLayer.Editor && state.topLayer.isDirty -> ProtectDraft(state.topLayer)
            state.topLayer != null -> CloseLayer(state.topLayer)
            state.selection == BackSelection.DRAWING -> FinishDrawing
            state.selection != null -> ClearSelection(state.selection)
            state.isSourceReader -> ReturnToCaller
            state.isImmersiveReader -> ExitImmersive
            state.page == BackPage.READER -> SaveReadingPositionAndLeave
            state.page == BackPage.CHILD -> PopPage
            else -> ActivityDefault
        }
    }
}
