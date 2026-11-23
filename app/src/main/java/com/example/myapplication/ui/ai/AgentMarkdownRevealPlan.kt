package com.example.myapplication.ui.ai

/** A parsed answer with the offsets of its visible text. Only the frontier block is rebuilt per step. */
internal class AgentMarkdownRevealPlan private constructor(
    val text: String,
    private val blocks: ListSlice<AgentMarkdownBlock>,
) {
    fun visibleBlocks(through: Int): List<AgentMarkdownBlock> = blocks.prefix(through)

    companion object {
        fun create(document: AgentMarkdownDocument): AgentMarkdownRevealPlan {
            val builder = SliceBuilder()
            val blocks = builder.list(document.blocks, builder::block)
            return AgentMarkdownRevealPlan(builder.text.toString(), blocks)
        }
    }
}

private class Slice<T>(
    private val start: Int,
    val end: Int,
    val value: T,
    private val partial: (Int) -> T,
) {
    fun prefix(through: Int): T? = when {
        through >= end -> value
        through <= start -> null
        else -> partial(through)
    }
}

private class ListSlice<T>(private val values: List<T>, private val slices: List<Slice<T>>) {
    fun prefix(through: Int): List<T> {
        if (slices.isEmpty() || through >= slices.last().end) return values
        var first = 0
        var last = slices.size
        while (first < last) {
            val middle = (first + last) / 2
            if (slices[middle].end <= through) first = middle + 1 else last = middle
        }
        val complete = values.subList(0, first)
        val partial = slices[first].prefix(through) ?: return complete
        return complete + partial
    }
}

private class SliceBuilder {
    val text = StringBuilder()

    fun <T> list(values: List<T>, slice: (T) -> Slice<T>): ListSlice<T> {
        return ListSlice(values, values.mapIndexed { index, value ->
            // A boundary prevents combining marks from joining glyphs in different blocks/cells.
            if (index > 0) text.append('\n')
            slice(value)
        })
    }

    private fun <T> segment(value: T, width: () -> Unit, partial: (Int) -> T): Slice<T> {
        val start = text.length
        width()
        return Slice(start, text.length, value, partial)
    }

    private fun inline(value: AgentMarkdownText): Slice<AgentMarkdownText> {
        val start = text.length
        return segment(value, { text.append(value.text) }) { end ->
            val length = end - start
            val spans = value.spans.mapNotNull { span ->
                if (span.start >= length) null
                else if (span.end <= length) span
                else if (span is AgentMarkdownSpan.Style) span.copy(end = length)
                else null
            }
            AgentMarkdownText(value.text.substring(0, length), spans)
        }
    }

    private fun item(blocks: List<AgentMarkdownBlock>): Slice<List<AgentMarkdownBlock>> {
        val start = text.length
        val list = list(blocks, ::block)
        return Slice(start, text.length, blocks, list::prefix)
    }

    private fun row(cells: List<AgentMarkdownText>): Slice<List<AgentMarkdownText>> {
        val start = text.length
        val list = list(cells, ::inline)
        return Slice(start, text.length, cells, list::prefix)
    }

    fun block(value: AgentMarkdownBlock): Slice<AgentMarkdownBlock> {
        val start = text.length
        val partial: (Int) -> AgentMarkdownBlock = when (value) {
            is AgentMarkdownBlock.Paragraph -> {
                val child = inline(value.text)
                ({ end -> value.copy(text = checkNotNull(child.prefix(end))) })
            }
            is AgentMarkdownBlock.Heading -> {
                val child = inline(value.text)
                ({ end -> value.copy(text = checkNotNull(child.prefix(end))) })
            }
            is AgentMarkdownBlock.Quote -> {
                val nested = item(value.blocks)
                ({ end -> value.copy(blocks = checkNotNull(nested.prefix(end))) })
            }
            is AgentMarkdownBlock.ListBlock -> {
                val items = list(value.items, ::item)
                ({ end -> value.copy(items = items.prefix(end)) })
            }
            is AgentMarkdownBlock.CodeBlock -> {
                text.append(value.code)
                ({ end -> value.copy(code = value.code.substring(0, end - start)) })
            }
            is AgentMarkdownBlock.Table -> {
                val rows = list(listOf(value.header) + value.rows, ::row)
                ({ end ->
                    val shown = rows.prefix(end)
                    value.copy(header = shown.firstOrNull().orEmpty(), rows = shown.drop(1))
                })
            }
            AgentMarkdownBlock.Divider -> {
                text.append('\n')
                ({ _ -> value })
            }
        }
        return Slice(start, text.length, value, partial)
    }
}
