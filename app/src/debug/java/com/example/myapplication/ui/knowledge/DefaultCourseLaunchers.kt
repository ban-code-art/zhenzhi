package com.example.myapplication.ui.knowledge

import android.content.Context
import android.content.Intent
import com.example.myapplication.ui.IsolatedCourseBookActivity
import com.example.myapplication.ui.IsolatedCourseLibraryActivity

internal fun defaultCourseBookLauncher(context: Context): ((String) -> Unit)? = { bookId ->
    context.startActivity(
        Intent(context, IsolatedCourseBookActivity::class.java)
            .putExtra(IsolatedCourseBookActivity.EXTRA_BOOK_ID, bookId)
    )
}

internal fun defaultCourseLibraryLauncher(context: Context): (() -> Unit)? = {
    context.startActivity(
        Intent(context, IsolatedCourseLibraryActivity::class.java)
    )
}
