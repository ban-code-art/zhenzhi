package com.example.myapplication.ui.knowledge.course

import com.example.myapplication.data.reader.ReaderHtmlBlockParser
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

/** Presentation only. The archive bytes and source text remain the authority for identity and practice. */
internal object CourseDraftDisplayHtml {
    private val tables = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(tables).build()
    private val renderer = HtmlRenderer.builder().extensions(tables).escapeHtml(true).sanitizeUrls(true).build()

    fun sourceText(body: ByteArray): String = ReaderHtmlBlockParser.parse(
        body.toString(Charsets.UTF_8), "course-draft-source-text"
    ).canonicalText

    fun render(
        body: ByteArray,
        background: Int = 0xfffaf9f6.toInt(),
        foreground: Int = 0xff252a32.toInt(),
        secondary: Int = 0xff596273.toInt(),
        accent: Int = 0xff3b6258.toInt(),
        surface: Int = 0xffeeefea.toInt(),
        onAccent: Int = 0xffffffff.toInt(),
        bundledSelection: Boolean = false,
    ): String {
        val document = Jsoup.parse(body.toString(Charsets.UTF_8))
        document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        if (bundledSelection) {
            document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
                .attr("content", "default-src 'none'; script-src 'none'; style-src 'unsafe-inline' https://private-draft.invalid;" +
                    " img-src https://private-draft.invalid data:; font-src https://private-draft.invalid; base-uri 'none'; form-action 'none'")
        }
        document.select("p").forEach { paragraph ->
            val text = paragraph.wholeText().trim()
            if (text.startsWith("|") && text.contains('\n') && text.contains("---")) {
                val parsed = parser.parse(text)
                if (parsed.firstChild is TableBlock && parsed.firstChild.next == null) {
                    val replacement = Element("div").addClass("course-draft-table")
                    if (paragraph.id().isNotBlank()) replacement.attr("id", paragraph.id())
                    replacement.html(renderer.render(parsed))
                    paragraph.replaceWith(replacement)
                }
            }
        }
        document.select("table").forEach { table ->
            if (table.parent()?.hasClass("course-draft-table") != true) table.wrap("<div class=\"course-draft-table\"></div>")
        }
        document.select("meta[name=viewport]").remove()
        document.head().appendElement("meta").attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1")
        document.select("#course-draft-display-style").remove()
        document.head().appendElement("style").attr("id", "course-draft-display-style").text("""
            :root { color-scheme: ${if ((foreground and 0x00ffffff) > (background and 0x00ffffff)) "dark" else "light"}; }
            html { background: ${css(background)}; -webkit-text-size-adjust: 100%; }
            body { margin: 0 auto; padding: 20px 18px 48px; max-width: 70ch; color: ${css(foreground)};
                background: ${css(background)}; font: 18px/1.85 system-ui, -apple-system, "Noto Sans", sans-serif;
                overflow-wrap: anywhere; }
            h1, h2, h3, h4 { line-height: 1.4; font-weight: 650; color: ${css(foreground)}; }
            h1 { font-size: 1.5em; margin: 0 0 1em; }
            h2 { font-size: 1.2em; margin: 1.8em 0 .65em; padding-bottom: .4em; border-bottom: 1px solid ${css(surface)}; }
            h3 { font-size: 1.05em; margin: 1.5em 0 .6em; }
            p, ul, ol { margin: 0 0 1em; }
            li { margin: .45em 0; }
            a { color: ${css(accent)}; text-underline-offset: .18em; }
            blockquote { margin: 1.2em 0; padding: .8em 1em; border-left: 3px solid ${css(accent)};
                color: ${css(secondary)}; background: ${css(surface)}; }
            .course-draft-table { width: 100%; overflow-x: auto; margin: 1.2em 0; border-radius: 10px; }
            table { border-collapse: collapse; width: 100%; font-size: .9em; line-height: 1.6; }
            th, td { min-width: 7em; padding: .7em .8em; border: 1px solid ${css(surface)}; text-align: left; vertical-align: top; }
            th { color: ${css(foreground)}; background: ${css(surface)}; font-weight: 650; }
            img, svg { max-width: 100%; height: auto; }
            pre { padding: 1em; overflow-x: auto; border-radius: 10px; background: ${css(surface)}; }
            code { font-family: ui-monospace, monospace; font-size: .9em; }
            mark[data-course-highlight] { padding: 0; color: #252a32; }
            ::selection { background: ${css(accent)}; color: ${css(onAccent)}; }
        """.trimIndent())
        return document.outerHtml()
    }

    private fun css(color: Int) = String.format(Locale.ROOT, "#%06x", color and 0x00ffffff)
}
