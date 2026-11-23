package com.example.myapplication.ui.knowledge.home.bookshelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.domain.reader.SourceReadBudget
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Composable
internal fun shelfCovers(entries: List<HomeShelfEntry>, anchor: Int, enabled: Boolean): Map<String, Bitmap> {
    val app = LocalContext.current.applicationContext as? LearningApplication
    val keys = shelfCoverWindow(entries, anchor).map(HomeShelfEntry::key).filter { it.startsWith("import:") }
    val covers by produceState(emptyMap<String, Bitmap>(), app, keys, enabled) {
        value = value.filterKeys { it in keys }
        if (!enabled || app == null) return@produceState
        for (key in keys) {
            if (key !in value) {
                val bitmap = withContext(Dispatchers.IO) { loadCover(app, key.removePrefix("import:")) }
                if (bitmap != null) value = value + (key to bitmap)
            }
        }
    }
    return covers
}

private suspend fun loadCover(app: LearningApplication, documentId: String): Bitmap? {
    val container = app.container
    val identity = container.sessionManager.identity.first()
    val owner = identity.userId ?: return null
    val catalog = container.database.readerCatalogDao()
    val doc = catalog.findDocument(documentId)?.takeIf { it.isCoverReadable(owner) } ?: return null
    if (container.database.readerStateDao().find(owner, documentId)?.isHidden == true) return null
    val coroutine = currentCoroutineContext()
    val budget = SourceReadBudget(64L * 1024 * 1024) { coroutine.ensureActive() }
    return try {
        val storage = container.documentStorage
        val published = storage.openRevision(doc.id, doc.contentRevision, sourceBudget = budget) ?: return null
        val manifest = published.manifest
        val declared = manifest.files.singleOrNull { it.kind == ManifestFileKind.COVER }
        val cover = when {
            declared != null -> {
                val file = storage.resolveFile(doc.id, doc.contentRevision, declared.relativePath)
                if (file.isFile && declared.bytes in 1..MaxRasterBytes && file.length() == declared.bytes &&
                    storage.sha256Of(file) == declared.sha256
                ) decodeRaster(file.readBytes()) else null
            }
            doc.format in setOf("EPUB", "PDF", "CBZ") -> {
                val entry = manifest.files.singleOrNull { it.kind == ManifestFileKind.SOURCE } ?: return null
                val file = storage.resolveFile(doc.id, doc.contentRevision, entry.relativePath)
                if (!file.isFile || entry.bytes !in 1..MaxSourceBytes || file.length() != entry.bytes ||
                    storage.sha256Of(file) != entry.sha256
                ) return null
                when (doc.format) {
                    "EPUB" -> ZipFile(file).use { zip ->
                        val image = epubCoverImagePath(zip) { coroutine.ensureActive() } ?: return@use null
                        readRaster(zip, image, budget)
                    }
                    "PDF" -> renderFirstPage(file)
                    else -> {
                        val firstSection = manifest.sections.minByOrNull { it.orderIndex } ?: return null
                        val page = manifest.files.singleOrNull {
                            it.relativePath == firstSection.resourceHref && it.kind == ManifestFileKind.IMAGE
                        } ?: return null
                        val image = storage.resolveFile(doc.id, doc.contentRevision, page.relativePath)
                        if (image.isFile && page.bytes in 1..MaxRasterBytes && image.length() == page.bytes &&
                            storage.sha256Of(image) == page.sha256
                        ) decodeRaster(image.readBytes()) else null
                    }
                }
            }
            else -> null
        } ?: return null
        val current = catalog.findDocument(documentId)
        if (container.sessionManager.identity.first() != identity ||
            current?.contentRevision != doc.contentRevision || current.accessEpoch != doc.accessEpoch ||
            !current.isCoverReadable(owner) || container.database.readerStateDao().find(owner, documentId)?.isHidden == true
        ) {
            cover.recycle()
            null
        } else cover
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun ReaderDocumentEntity.isCoverReadable(owner: Long) =
    kind == "IMPORTED" && ownerUserId == owner && importStatus == "READY" && deletedAt == null

private fun readRaster(zip: ZipFile, name: String, budget: SourceReadBudget): Bitmap? {
    val entry = zip.getEntry(name) ?: return null
    if (entry.isDirectory || entry.size !in 1..MaxRasterBytes) return null
    return zip.getInputStream(entry).use { stream ->
        val data = budget.readAll(stream, entry.size)
        if (stream.read() == -1) decodeRaster(data) else null
    }
}

private fun decodeRaster(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..16_384 || bounds.outHeight !in 1..16_384) return null
    var sample = 1
    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 768) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}

private fun renderFirstPage(file: File): Bitmap? =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount <= 0) return null
            renderer.openPage(0).use { page ->
                val ratio = minOf(512f / page.width.coerceAtLeast(1), 768f / page.height.coerceAtLeast(1), 1f)
                val width = (page.width * ratio).roundToInt().coerceAtLeast(1)
                val height = (page.height * ratio).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                    image.eraseColor(android.graphics.Color.WHITE)
                    page.render(image, Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

private const val MaxRasterBytes = 8L * 1024 * 1024
private const val MaxSourceBytes = 64L * 1024 * 1024
