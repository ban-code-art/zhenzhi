package com.example.myapplication.ui.knowledge.reader

import java.io.File

/** Call only with the same immutable source and actual PdfRenderer.Page dimensions as the bitmap. */
interface PdfReaderSurface {
    suspend fun loadPage(source: File, pageIndex: Int, rendererWidth: Int, rendererHeight: Int): PdfTextPage
}
