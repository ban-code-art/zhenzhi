package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.File

class PlainTextDocumentParser : DocumentParser {
    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument {
        if (sourceFile.length() > ParserIO.MAX_TEXT_BYTES) {
            throw DocumentParseException("LIMIT_EXCEEDED", "文本文件超过 64 MiB 限制")
        }
        val text = ParserIO.decodeText(sourceFile.readBytes())
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (text.isBlank()) throw DocumentParseException("CORRUPT_INPUT", "文本文件没有可读正文")

        val chapters = splitChapters(text)
        val title = titleHint?.trim()?.takeIf { it.isNotEmpty() } ?: ParserIO.titleFromName(sourceFile.name)
        val files = mutableListOf<ParsedFile>()
        val sections = mutableListOf<ManifestSection>()
        val outline = mutableListOf<ManifestOutlineNode>()

        chapters.forEachIndexed { index, chapter ->
            val sectionId = "section-${index + 1}"
            val path = "content/${sectionId}-${ParserIO.slug(chapter.title)}.html"
            val paragraphs = chapter.body.split(Regex("\\n\\s*\\n"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString("") { "<p>${ParserIO.escapeHtml(it).replace("\n", "<br>")}</p>" }
            val html = ParserIO.htmlDocument(chapter.title, "<h1>${ParserIO.escapeHtml(chapter.title)}</h1>$paragraphs")
            ParserIO.writeText(outputDirectory, path, html)
            files += ParsedFile(path, ManifestFileKind.SECTION)
            sections += ManifestSection(
                id = sectionId,
                orderIndex = index,
                title = chapter.title,
                resourceHref = path,
                charCount = chapter.body.length,
            )
            outline += ManifestOutlineNode(
                nodeKey = "toc-${index + 1}",
                orderIndex = index,
                title = chapter.title,
                targetSectionId = sectionId,
            )
        }

        return ParsedDocument(
            title = title,
            author = null,
            format = ReaderFormat.TXT,
            files = files,
            sections = sections,
            outline = outline,
        )
    }

    private fun splitChapters(text: String): List<Chapter> {
        val matches = HEADING.findAll(text).toList()
        if (matches.isEmpty()) {
            return splitByLength(text).mapIndexed { index, body ->
                Chapter(if (index == 0) "正文" else "正文 ${index + 1}", body)
            }
        }
        val chapters = mutableListOf<Chapter>()
        val preface = text.substring(0, matches.first().range.first).trim()
        if (preface.isNotEmpty()) chapters += Chapter("前言", preface)
        matches.forEachIndexed { index, match ->
            val start = match.range.first
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            val body = text.substring(start, end).trim()
            val title = match.value.trim().take(80).ifBlank { "第 ${chapters.size + 1} 节" }
            chapters += Chapter(title, body)
        }
        return chapters.ifEmpty { listOf(Chapter("正文", text)) }
    }

    private fun splitByLength(text: String): List<String> {
        if (text.length <= MAX_SECTION_CHARS) return listOf(text)
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        paragraphs.forEach { paragraph ->
            if (current.isNotEmpty() && current.length + paragraph.length > MAX_SECTION_CHARS) {
                chunks += current.toString().trim()
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks
    }

    private data class Chapter(val title: String, val body: String)

    companion object {
        private const val MAX_SECTION_CHARS = 20_000
        private val HEADING = Regex(
            "(?m)^(第[一二三四五六七八九十百千万0-9]+[章节篇部]|Chapter\\s+\\d+)[^\\n]*$",
            RegexOption.IGNORE_CASE,
        )
    }
}