package com.example.myapplication.data.reader.importer

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException

/**
 * M04：把系统选择器给出的授权转成 Markdown 附件解析器。
 *
 * - `file://` 文档：只允许读取 MD 所在目录（含子目录）内的文件。
 * - SAF 单文档授权（`content://`）拿不到同目录兄弟文件；只能使用用户在同一次选择中一并选中的图片（按文件名精确匹配）。
 * 两者都没有时返回 null，采集器会把每个相对图片显式标为 NOT_ACCESSIBLE，不猜测、不伪造。
 */
object SafImportAttachments {
    data class Selected(val uri: Uri, val displayName: String?, val mimeType: String?)

    fun resolverFor(
        contentResolver: ContentResolver,
        document: Selected,
        companions: List<Selected>,
    ): ImportAttachmentResolver? {
        val resolvers = mutableListOf<ImportAttachmentResolver>()
        if (document.uri.scheme == ContentResolver.SCHEME_FILE) {
            document.uri.path?.let { File(it).parentFile }?.let { resolvers += DirectoryAttachmentResolver(it) }
        }
        val selected = companions.mapNotNull { companion ->
            val name = companion.displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            name to {
                contentResolver.openInputStream(companion.uri) ?: throw FileNotFoundException("无法打开所选图片")
            }
        }
        if (selected.isNotEmpty()) resolvers += SelectedFilesAttachmentResolver(selected)
        return when (resolvers.size) {
            0 -> null
            1 -> resolvers.single()
            else -> CompositeAttachmentResolver(resolvers)
        }
    }
}
