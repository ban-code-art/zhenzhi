@file:Suppress("UNUSED_PARAMETER")

package com.example.myapplication.ui.knowledge

import android.content.Context

// Keep ShelfScreen's existing course fallback until the production entry is ready.
internal fun defaultCourseBookLauncher(context: Context): ((String) -> Unit)? = null

internal fun defaultCourseLibraryLauncher(context: Context): (() -> Unit)? = null
