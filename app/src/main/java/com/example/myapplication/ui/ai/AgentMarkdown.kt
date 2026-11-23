package com.example.myapplication.ui.ai

import android.icu.text.BreakIterator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.theme.ReaderTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private data class MarkdownCitations(val labels: Set<String>, val sources: Map<String, AgentSourceUi> = emptyMap(), val onClick: (String) -> Unit)
private val LocalMarkdownCitations = staticCompositionLocalOf { MarkdownCitations(emptySet()) {} }
private val LocalMarkdownOnPage = staticCompositionLocalOf { false }

internal data class AgentAnswerPresentation(
    val blocks: List<AgentMarkdownBlock>?,
    val revealing: Boolean,
)

private data class ParsedAnswer(val source: String, val plan: AgentMarkdownRevealPlan)

/**
 * Model deltas can arrive in bursts. Parse snapshots off the main thread while an independent
 * presentation cursor advances by one Unicode grapheme on a display frame.
 */
@Composable
internal fun rememberAgentAnswerPresentation(markdown: String, animate: Boolean): AgentAnswerPresentation {
    val newestContent = rememberUpdatedState(markdown)
    val parsed = produceState<ParsedAnswer?>(null) {
        snapshotFlow { newestContent.value }.collect { input ->
            value = if (input.isBlank()) null else withContext(Dispatchers.Default) {
                ParsedAnswer(input, AgentMarkdownRevealPlan.create(AgentMarkdownParser.parse(input)))
            }
            // Coalesce provider bursts while the visual cursor continues through the previous plan.
            delay(80)
        }
    }
    var through by rememberSaveable { mutableIntStateOf(0) }
    var motionOff by remember { mutableStateOf(false) }
    val target = parsed.value
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val motionScale = currentCoroutineContext()[MotionDurationScale]
        val iterator = BreakIterator.getCharacterInstance()
        var lastPlan: AgentMarkdownRevealPlan? = null
        while (true) {
            if (motionScale?.scaleFactor == 0f) {
                motionOff = true
                break
            }
            if ((parsed.value?.plan?.text?.length ?: 0) <= through) {
                snapshotFlow { parsed.value?.plan }.first { plan -> plan != null && plan.text.length > through }
            }
            withFrameNanos { }
            val plan = parsed.value?.plan ?: continue
            if (through >= plan.text.length) continue
            if (lastPlan !== plan) {
                iterator.setText(plan.text)
                lastPlan = plan
            }
            val next = iterator.following(through)
            if (next == BreakIterator.DONE) break
            val firstCharacter = through == 0
            through = next
            // The first character replaces the waiting card and needs time for that layout to settle.
            delay(when {
                firstCharacter -> 72L
                plan.text.length - through > 100 -> 8L
                else -> 24L
            })
        }
    }
    val paced = animate && !motionOff
    val showing = target?.plan?.visibleBlocks(if (paced) through else Int.MAX_VALUE)
    return AgentAnswerPresentation(
        blocks = showing,
        revealing = paced && markdown.isNotBlank() &&
            (target == null || target.source != markdown || through < target.plan.text.length),
    )
}

/**
 * Native answer rendering. The surrounding message list owns vertical scrolling; only code and
 * tables scroll horizontally. Keying by message id prevents one answer's parsed state leaking into
 * another, including when a conversation changes while a background parse is running.
 */
@Composable
fun AgentMarkdown(
    messageId: String,
    markdown: String,
    modifier: Modifier = Modifier,
    citationLabels: Set<String> = emptySet(),
    citationSources: Map<String, AgentSourceUi> = emptyMap(),
    onPageBackground: Boolean = false,
    onCitationClick: (String) -> Unit = {},
) {
    key(messageId) {
        val document by produceState<AgentMarkdownDocument?>(null, markdown) {
            value = withContext(Dispatchers.Default) { AgentMarkdownParser.parse(markdown) }
        }
        val presentation = AgentAnswerPresentation(document?.blocks, revealing = false)
        AgentPresentedMarkdown(messageId, presentation, modifier, citationLabels, citationSources,
            onPageBackground, onCitationClick)
    }
}

@Composable
internal fun AgentPresentedMarkdown(
    messageId: String,
    presentation: AgentAnswerPresentation,
    modifier: Modifier = Modifier,
    citationLabels: Set<String> = emptySet(),
    citationSources: Map<String, AgentSourceUi> = emptyMap(),
    onPageBackground: Boolean = false,
    onCitationClick: (String) -> Unit = {},
) {
    CompositionLocalProvider(
        LocalMarkdownCitations provides MarkdownCitations(citationLabels, citationSources, onCitationClick),
        LocalMarkdownOnPage provides onPageBackground,
    ) {
        Column(modifier.fillMaxWidth().testTag("agent-markdown-" + messageId)) {
            val blocks = presentation.blocks
            if (blocks == null) {
                Box(Modifier.fillMaxWidth().heightIn(min = 24.dp))
            } else {
                SelectionContainer {
                    MarkdownBlocks(blocks, messageId)
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlocks(
    blocks: List<AgentMarkdownBlock>,
    blockKey: String,
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    depth: Int = 0,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEachIndexed { index, block ->
            key(index) {
                val childKey = blockKey + "-" + index
                when (block) {
                    is AgentMarkdownBlock.Paragraph -> MarkdownInline(block.text)
                    is AgentMarkdownBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            3 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.bodyLarge
                        }.copy(fontWeight = FontWeight.SemiBold)
                        MarkdownInline(block.text, Modifier.semantics { heading() }, style)
                    }
                    is AgentMarkdownBlock.Quote -> {
                        val accent = ReaderTokens.Primary.copy(alpha = 0.65f)
                        MarkdownBlocks(
                            block.blocks, childKey,
                            Modifier.background(ReaderTokens.SurfaceMuted, RoundedCornerShape(8.dp))
                                .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                            depth = depth,
                        )
                    }
                    is AgentMarkdownBlock.ListBlock -> {
                        Column(verticalArrangement = Arrangement.spacedBy(if (block.tight) 4.dp else 10.dp)) {
                            block.items.forEachIndexed { itemIndex, item ->
                                Row(Modifier.fillMaxWidth()) {
                                    val marker = if (block.ordered) {
                                        (block.startNumber.toLong() + itemIndex).toString() + "."
                                    } else if (depth % 2 == 0) "•" else "◦"
                                    Text(
                                        marker, Modifier.widthIn(min = 24.dp).padding(end = 6.dp),
                                        color = ReaderTokens.Primary,
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                                    )
                                    MarkdownBlocks(
                                        item, childKey + "-" + itemIndex, Modifier.weight(1f),
                                        spacing = if (block.tight) 4.dp else 8.dp, depth = depth + 1,
                                    )
                                }
                            }
                        }
                    }
                    is AgentMarkdownBlock.CodeBlock -> MarkdownCode(block, childKey)
                    is AgentMarkdownBlock.Table -> MarkdownTable(block)
                    AgentMarkdownBlock.Divider -> HorizontalDivider(color = ReaderTokens.Divider)
                }
            }
        }
    }
}

@Composable
private fun MarkdownInline(
    text: AgentMarkdownText,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
    textAlign: TextAlign? = null,
) {
    val onPage = LocalMarkdownOnPage.current
    val colors = MaterialTheme.colorScheme
    val foreground = if (onPage) colors.onBackground else ReaderTokens.TextPrimary
    val linkColor = if (onPage) foreground else ReaderTokens.Primary
    val codeBackground = if (onPage) foreground.copy(alpha = .12f) else ReaderTokens.SurfaceMuted
    val uriHandler = LocalUriHandler.current
    val citations = LocalMarkdownCitations.current
    val onCitationClick by rememberUpdatedState(citations.onClick)
    val replacements = remember(text, citations.sources) { text.spans.filterIsInstance<AgentMarkdownSpan.Citation>()
        .filter { it.label in citations.sources }.associateBy { it.start } }
    val inline = replacements.values.associate { span ->
        val source = citations.sources.getValue(span.label)
        val title = source.documentTitle.let { if (it.length > 10) it.take(9) + "…" else it }
        val units = title.sumOf { if (it.code > 255) 13 else 7 }
        "cite-${span.start}" to InlineTextContent(Placeholder((units + 31).sp, 24.sp, PlaceholderVerticalAlign.Center)) {
            Row(Modifier.fillMaxWidth().background(foreground.copy(alpha = .08f), RoundedCornerShape(20.dp))
                .clickable(role = Role.Button) { onCitationClick(span.label) }.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AiDocumentBadge(source.documentTitle, Modifier.size(17.dp), neutral = true)
                Spacer(Modifier.width(3.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    color = if (onPage) foreground else colors.onSurfaceVariant,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
    val annotated = remember(text, linkColor, codeBackground, uriHandler, citations.labels, citations.sources) {
        buildAnnotatedString {
            val offsets = IntArray(text.text.length + 1)
            var cursor = 0
            while (cursor < text.text.length) {
                offsets[cursor] = length
                val replacement = replacements[cursor]
                if (replacement == null) { append(text.text[cursor]); cursor++ }
                else {
                    val begin = length
                    appendInlineContent("cite-${replacement.start}", citations.sources.getValue(replacement.label).documentTitle)
                    for (i in cursor until replacement.end) offsets[i] = begin
                    cursor = replacement.end
                }
            }
            offsets[text.text.length] = length
            for (span in text.spans) {
                when (span) {
                    is AgentMarkdownSpan.Style -> addStyle(
                        when (span.kind) {
                            AgentMarkdownStyle.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
                            AgentMarkdownStyle.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
                            AgentMarkdownStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                        }, offsets[span.start], offsets[span.end],
                    )
                    is AgentMarkdownSpan.Link -> addLink(
                        LinkAnnotation.Url(
                            span.destination,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        ) { link ->
                            if (link is LinkAnnotation.Url) runCatching { uriHandler.openUri(link.url) }
                        },
                        offsets[span.start], offsets[span.end],
                    )
                    is AgentMarkdownSpan.Citation -> if (span.label in citations.labels && span.start !in replacements) addLink(
                        LinkAnnotation.Clickable(
                            tag = "source-${span.label}",
                            styles = TextLinkStyles(SpanStyle(
                                color = linkColor, fontWeight = FontWeight.SemiBold,
                                background = codeBackground, textDecoration = TextDecoration.Underline,
                            )),
                        ) { onCitationClick(span.label) },
                        offsets[span.start], offsets[span.end],
                    )
                }
            }
        }
    }
    Text(annotated, modifier, style = style, color = foreground, textAlign = textAlign, inlineContent = inline)
}

@Suppress("DEPRECATION")
@Composable
private fun MarkdownCode(block: AgentMarkdownBlock.CodeBlock, blockKey: String) {
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(ReaderTokens.SurfaceMuted)
            .border(1.dp, ReaderTokens.Divider, shape),
    ) {
        DisableSelection {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    block.language?.take(40) ?: "代码", Modifier.padding(top = 14.dp),
                    color = ReaderTokens.TextSecondary, style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(block.code)) },
                    modifier = Modifier.testTag("agent-code-copy-" + blockKey),
                ) { Text("复制代码", style = MaterialTheme.typography.labelMedium) }
            }
        }
        HorizontalDivider(color = ReaderTokens.Divider)
        Text(
            block.code.removeSuffix("\n"),
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            softWrap = false,
            color = ReaderTokens.TextPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
        )
    }
}

@Composable
private fun MarkdownTable(table: AgentMarkdownBlock.Table) {
    val rows = listOf(table.header) + table.rows
    val columnCount = rows.maxOfOrNull { it.size } ?: 0
    if (columnCount == 0) return
    val borderColor = ReaderTokens.Divider
    val headerColor = ReaderTokens.Primary.copy(alpha = 0.10f)
    val alternateColor = ReaderTokens.SurfaceMuted.copy(alpha = 0.45f)
    val surfaceColor = ReaderTokens.Surface
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidths = remember(table, maxWidth) {
            (0 until columnCount).map { column ->
                val characters = rows.maxOfOrNull { row ->
                    row.getOrNull(column)?.text?.lineSequence()?.maxOfOrNull { it.length } ?: 0
                } ?: 0
                maxOf(maxWidth / columnCount, (characters * 9 + 24).dp.coerceIn(112.dp, 260.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                rows.forEachIndexed { rowIndex, row ->
                    val background = when {
                        rowIndex == 0 -> headerColor
                        rowIndex % 2 == 0 -> alternateColor
                        else -> surfaceColor
                    }
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        (0 until columnCount).forEach { column ->
                            Box(
                                Modifier.width(columnWidths[column]).fillMaxHeight()
                                    .background(background).border(0.5.dp, borderColor),
                            ) {
                                MarkdownInline(
                                    row.getOrNull(column) ?: AgentMarkdownText(""),
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 22.sp,
                                        fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    when (table.alignments.getOrNull(column)) {
                                        AgentMarkdownAlignment.CENTER -> TextAlign.Center
                                        AgentMarkdownAlignment.RIGHT -> TextAlign.End
                                        else -> TextAlign.Start
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
