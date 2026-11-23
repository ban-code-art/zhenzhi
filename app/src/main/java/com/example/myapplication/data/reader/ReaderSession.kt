package com.example.myapplication.data.reader

import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.local.entity.ReaderStateEntity
import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.data.reader.storage.ContentManifest
import java.io.File

data class ReaderSession(
    val document: ReaderDocumentEntity,
    val revision: ReaderContentRevisionEntity,
    val sections: List<ReaderSectionEntity>,
    val outline: List<ReaderOutlineNodeEntity>,
    val state: ReaderStateEntity?,
    val manifest: ContentManifest?,
    val contentRoot: File?,
    val format: ReaderFormat,
)

data class ReaderShelfItem(
    val documentId: String,
    val title: String,
    val author: String?,
    val format: ReaderFormat,
    val kind: String,
    val legacyChapterId: Long?,
    val isCompleted: Boolean,
    val lastReadAt: Long,
    val locatorJson: String?,
)

data class ReaderSectionContent(
    val sectionId: String,
    val title: String,
    val html: String,
    val baseUrl: String,
    val fileUrl: String,
    val pdfFile: File? = null,
    val pageIndex: Int = 0,
)

data class WriterLease(val epoch: Long)
