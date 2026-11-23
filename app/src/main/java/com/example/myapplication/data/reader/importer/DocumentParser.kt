package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ContentManifestValidator
import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

data class ParsedFile(
    val relativePath: String,
    val kind: ManifestFileKind,
)

data class ParsedDocument(
    val title: String,
    val author: String?,
    val format: ReaderFormat,
    val files: List<ParsedFile>,
    val sections: List<ManifestSection>,
    val outline: List<ManifestOutlineNode>,
)

interface DocumentParser {
    fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument
}

class DocumentParseException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object ParserIO {
    const val MAX_TEXT_BYTES = 64L * 1024 * 1024

    fun writeBytes(root: File, relativePath: String, bytes: ByteArray): File {
        val target = outputTarget(root, relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target
    }

    /** 与 [writeBytes] 相同的路径校验，但流式复制已存在的私有文件（大图片不整读进内存）。 */
    fun copyFile(root: File, relativePath: String, source: File): File {
        val target = outputTarget(root, relativePath)
        target.parentFile?.mkdirs()
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }

    private fun outputTarget(root: File, relativePath: String): File {
        val errors = mutableListOf<String>()
        ContentManifestValidator.validateRelativePath(relativePath, "relativePath", errors)
        if (errors.isNotEmpty()) throw DocumentParseException("INVALID_ARGUMENT", errors.joinToString("; "))
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, relativePath.replace('/', File.separatorChar)).canonicalFile
        val prefix = rootCanonical.path + File.separator
        if (!target.path.startsWith(prefix)) {
            throw DocumentParseException("INVALID_ARGUMENT", "parser output escapes its revision directory")
        }
        return target
    }

    fun writeText(root: File, relativePath: String, text: String): File =
        writeBytes(root, relativePath, text.toByteArray(StandardCharsets.UTF_8))

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val utf8 = strictDecode(bytes, offset, Charsets.UTF_8)
        if (utf8 != null) return utf8
        return strictDecode(bytes, 0, Charset.forName("GB18030"))
            ?: String(bytes, 0, bytes.size, StandardCharsets.UTF_8)
    }

    fun htmlDocument(title: String, bodyHtml: String, extraCss: String = ""): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${escapeHtml(title)}</title>
          <style>
            :root { color-scheme: light dark; }
            body { margin: 0 auto; padding: 18px 20px 96px; max-width: 760px;
                   font-family: -apple-system, "Noto Serif SC", "Songti SC", serif;
                   line-height: 1.75; font-size: 18px; color: #2f3b36; background: transparent; }
            h1, h2, h3 { line-height: 1.35; color: #24483c; }
            h1 { font-size: 1.45em; margin: 0 0 1em; }
            h2 { font-size: 1.2em; margin: 1.4em 0 .7em; }
            p { margin: 0 0 1em; text-align: justify; }
            img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
            table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: .92em; }
            th, td { border: 1px solid #cfded7; padding: 6px 8px; }
            blockquote { border-left: 3px solid #8ac7ad; margin: 1em 0; padding-left: 14px; color: #5f6f68; }
            pre, code { font-family: ui-monospace, monospace; background: rgb(128 128 128 / 12%); border-radius: 4px; }
            pre { overflow-x: auto; padding: 10px; }
            a { color: #3b846a; }${extraCss.replace('\n', ' ')}
          </style>
        </head>
        <body>$bodyHtml</body>
        </html>
    """.trimIndent()

    fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    fun slug(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
        return normalized.take(48).ifBlank { "section" }
    }

    fun titleFromName(name: String?): String {
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.')
        return base?.trim()?.takeIf { it.isNotEmpty() } ?: "未命名书籍"
    }

    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}