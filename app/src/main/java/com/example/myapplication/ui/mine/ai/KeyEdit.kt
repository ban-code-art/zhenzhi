package com.example.myapplication.ui.mine.ai

/** Editing intent only. Saved plaintext is never placed in editor state. */
sealed interface KeyEdit {
    data object Keep : KeyEdit
    data object Clear : KeyEdit
    data class Replace(val value: String) : KeyEdit {
        init { require(value.isNotEmpty()) }
        override fun toString(): String = "Replace([redacted])"
    }
}
