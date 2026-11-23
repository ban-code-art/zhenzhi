package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.*

/** Pure logical sequence decision. Unknown units are invalid, never a book edge. */
class TurnPageCoordinator {
    fun resolve(sequence: ReadingSequence, current: ReaderTarget, direction: ReaderTurnDirection): TurnBoundary {
        if (sequence.indexOf(current) < 0) return TurnBoundary.Invalidated
        val target = when (direction) {
            ReaderTurnDirection.NEXT -> sequence.next(current)
            ReaderTurnDirection.PREVIOUS -> sequence.previous(current)
        } ?: return TurnBoundary.BookBoundary(direction)
        return TurnBoundary.Adjacent(target, if (direction == ReaderTurnDirection.NEXT) ReaderPlacement.START else ReaderPlacement.END)
    }
}

sealed interface TurnBoundary {
    data object Invalidated : TurnBoundary
    data class BookBoundary(val direction: ReaderTurnDirection) : TurnBoundary
    data class Adjacent(val target: ReaderTarget, val placement: ReaderPlacement) : TurnBoundary
}
