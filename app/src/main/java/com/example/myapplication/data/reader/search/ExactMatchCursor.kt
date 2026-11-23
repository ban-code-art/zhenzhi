package com.example.myapplication.data.reader.search

import java.text.Normalizer
import java.util.Locale

/** TERMS requires every whitespace-separated term in a block; at each original start the
 * longest matching term wins. PHRASE keeps the complete normalized phrase. Both overlap. */
enum class ExactMatchMode { PHRASE, TERMS }

data class ExactMatchOptions(
    val ignoreCase: Boolean = true,
    val collapseWhitespace: Boolean = true,
    val mode: ExactMatchMode = ExactMatchMode.PHRASE,
    val normalizeWidth: Boolean = true,
)

/** Immutable continuation, never an authorization grant. Every fetch checks the current
 * user/document/revision/access epoch. pageCursor is the INPUT cursor of the retained page.
 * utf16Offset is the next original candidate, and blockState retains an unfinished probe.
 * Fetch sequence is deliberately absent: a continuation fetch has its own sequence gate. */
class ExactMatchCursor internal constructor(
    internal val userId: Long,
    internal val documentId: String,
    internal val contentRevision: String,
    internal val accessEpoch: Long,
    internal val query: String,
    internal val options: ExactMatchOptions,
    internal val sectionIndex: Int,
    internal val sectionId: String,
    internal val pageCursor: String?,
    internal val blockIndex: Int,
    internal val blockId: String?,
    internal val utf16Offset: Int,
    internal val totalHitsSoFar: Int,
    internal val version: Int = 1,
    internal val blockState: ExactBlockScanState? = null,
)

internal data class ExactTextMatch(val startUtf16: Int, val endUtf16: Int)

/** Metadata only: no source block or snapshot is retained by a cursor. Probe strings are
 * bounded by the query plus one bounded normalization cluster, not the book or block size. */
internal data class ExactProbeState(
    val readOffset: Int,
    val clusterStart: Int,
    val clusterEnd: Int,
    val pendingCodepointBytes: Int = 0,
    val normalized: String = "",
    val originalEnds: List<Int> = emptyList(),
    val normalizedStarts: List<Int> = emptyList(),
    val matchedEnds: Map<Int, Int> = emptyMap(),
)
internal data class ExactBlockScanState(
    val candidateStart: Int = 0,
    val verifyingTerms: Boolean = false,
    val seenTerms: Set<Int> = emptySet(),
    val probe: ExactProbeState? = null,
)
internal data class ExactBlockScan(
    val matches: List<ExactTextMatch>,
    val next: ExactBlockScanState?,
    /** Actual UTF-8 work, including repeated prefix probes and the TERMS verification pass. */
    val scannedBytes: Long,
)
internal class ExactNormalizationException : IllegalArgumentException("EXACT_NORMALIZATION_LIMIT")

internal class ExactCompiledQuery(val needles: List<String>, val options: ExactMatchOptions) {
    val maxLength: Int = needles.maxOf { it.length }
}

/** Resumable original-position probes. A small byte budget can interrupt a grapheme or a
 * phrase prefix without losing it. NFKC/case expansion maps back to the whole source cluster;
 * whitespace contraction keeps the full original run. Surrogate halves are never candidates. */
internal object ExactTextMatcher {
    const val MAX_QUERY_UTF16 = 8192
    private const val MAX_CLUSTER_UTF16 = 8192
    private const val MAX_NORMALIZED_CLUSTER_UTF16 = 65536

    fun compile(query: String, options: ExactMatchOptions): ExactCompiledQuery? {
        if (query.length > MAX_QUERY_UTF16 || hasUnpairedSurrogate(query)) return null
        val normalized = normalizeQuery(query, options)
        if (normalized.isBlank() || normalized.length > MAX_QUERY_UTF16) return null
        val needles = if (options.mode == ExactMatchMode.PHRASE) listOf(normalized)
            else normalized.split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }.distinct()
        return needles.takeIf { it.isNotEmpty() }?.let { ExactCompiledQuery(it, options) }
    }

    fun initial(compiled: ExactCompiledQuery, fromUtf16: Int = 0) = ExactBlockScanState(
        candidateStart = fromUtf16.coerceAtLeast(0),
        verifyingTerms = compiled.options.mode == ExactMatchMode.TERMS && compiled.needles.size > 1,
    )

    fun matches(text: String, query: String, options: ExactMatchOptions = ExactMatchOptions(),
        fromUtf16: Int = 0, limit: Int = 100): List<ExactTextMatch> {
        if (limit <= 0) return emptyList()
        val compiled = compile(query, options) ?: return emptyList()
        // TERMS verifies the WHOLE block even when enumeration resumes partway through it.
        var state = initial(compiled)
        val result = ArrayList<ExactTextMatch>()
        do {
            val scanned = scan(text, compiled, state, Long.MAX_VALUE, limit, {})
            result += scanned.matches.filter { it.startUtf16 >= fromUtf16.coerceAtLeast(0) }
            val next = scanned.next ?: break
            state = next
        } while (result.size < limit)
        return result.take(limit)
    }

    fun scan(text: String, compiled: ExactCompiledQuery, initial: ExactBlockScanState,
        byteBudget: Long, hitLimit: Int, checkActive: () -> Unit): ExactBlockScan {
        require(byteBudget >= 0)
        require(hitLimit > 0)
        var state = initial
        var used = 0L
        val matches = ArrayList<ExactTextMatch>()
        while (true) {
            checkActive()
            if (state.candidateStart >= text.length) {
                return ExactBlockScan(matches, null, used)
            }
            require(state.candidateStart >= 0 && !Character.isLowSurrogate(text[state.candidateStart]))
            if (used >= byteBudget || matches.size >= hitLimit) {
                return ExactBlockScan(matches, state, used)
            }
            val start = state.candidateStart
            val seed = state.probe ?: ExactProbeState(start, start,
                nextBoundary(text, start, compiled.options.normalizeWidth))
            val advanced = probe(text, compiled, seed, byteBudget - used, checkActive)
            used += advanced.bytes
            if (!advanced.complete) {
                return ExactBlockScan(matches, state.copy(probe = advanced.state), used)
            }
            val found = advanced.state.matchedEnds
            val nextCandidate = nextBoundary(text, start, compiled.options.normalizeWidth)
            if (state.verifyingTerms) {
                val seen = state.seenTerms + found.keys
                state = if (seen.size == compiled.needles.size) {
                    // Independent second pass: do not emit preflight observations as results.
                    ExactBlockScanState(candidateStart = 0, verifyingTerms = false)
                } else state.copy(candidateStart = nextCandidate, seenTerms = seen, probe = null)
            } else {
                val best = found.keys.sortedWith(compareByDescending<Int> { compiled.needles[it].length }
                    .thenBy { compiled.needles[it] }).firstOrNull()
                if (best != null) matches += ExactTextMatch(start, found.getValue(best))
                state = state.copy(candidateStart = nextCandidate, probe = null)
            }
        }
    }

    private data class ProbeAdvance(val state: ExactProbeState, val bytes: Long, val complete: Boolean)

    private fun probe(text: String, compiled: ExactCompiledQuery,
        initial: ExactProbeState, budget: Long, checkActive: () -> Unit): ProbeAdvance {
        var state = initial
        var used = 0L
        while (true) {
            checkActive()
            if (state.clusterEnd - state.clusterStart > MAX_CLUSTER_UTF16) throw ExactNormalizationException()
            var read = state.readOffset
            var pending = state.pendingCodepointBytes
            while (read < state.clusterEnd && used < budget) {
                checkActive()
                val cp = text.codePointAt(read)
                val remaining = if (pending > 0) pending else utf8Bytes(cp)
                val consumed = minOf(remaining.toLong(), budget - used).toInt()
                used += consumed
                pending = remaining - consumed
                if (pending == 0) read += Character.charCount(cp)
            }
            state = state.copy(readOffset = read, pendingCodepointBytes = pending)
            if (read < state.clusterEnd) return ProbeAdvance(state, used, false)
            val raw = text.substring(state.clusterStart, state.clusterEnd)
            val cluster = normalizeCluster(raw, compiled.options)
            if (cluster.length > MAX_NORMALIZED_CLUSTER_UTF16) throw ExactNormalizationException()
            val normalized = StringBuilder(state.normalized)
            val ends = state.originalEnds.toMutableList()
            var at = 0
            while (at < cluster.length) {
                val cp = cluster.codePointAt(at)
                if (compiled.options.collapseWhitespace && isSpace(cp)) {
                    if (normalized.isNotEmpty() && normalized.last() == ' ') ends[ends.lastIndex] = state.clusterEnd
                    else { normalized.append(' '); ends += state.clusterEnd }
                } else {
                    normalized.appendCodePoint(cp)
                    repeat(Character.charCount(cp)) { ends += state.clusterEnd }
                }
                at += Character.charCount(cp)
            }
            val flat = normalized.toString()
            val starts = if (state.normalizedStarts.isNotEmpty()) state.normalizedStarts else
                flat.indices.filter { it == 0 || !Character.isLowSurrogate(flat[it]) }
            val found = state.matchedEnds.toMutableMap()
            var potential = false
            for ((index, needle) in compiled.needles.withIndex()) {
                for (offset in starts) {
                    val available = flat.length - offset
                    val compared = minOf(needle.length, available)
                    if (!flat.regionMatches(offset, needle, 0, compared)) continue
                    if (available >= needle.length) {
                        val end = ends[offset + needle.length - 1]
                        found[index] = minOf(found[index] ?: end, end)
                    } else potential = true
                }
            }
            state = state.copy(normalized = flat, originalEnds = ends.toList(), normalizedStarts = starts,
                matchedEnds = found.toMap())
            if (!potential || state.clusterEnd == text.length) return ProbeAdvance(state, used, true)
            val nextStart = state.clusterEnd
            state = state.copy(clusterStart = nextStart,
                clusterEnd = nextBoundary(text, nextStart, compiled.options.normalizeWidth))
            if (used >= budget) return ProbeAdvance(state, used, false)
        }
    }

    /** Group only characters whose canonical normalization may compose. Emoji, flags and
     * ZWJ sequences keep independent codepoint positions instead of collapsing a whole glyph
     * into a single search occurrence. Variation selectors do not participate in NFKC. */
    private fun nextBoundary(text: String, start: Int, normalizeWidth: Boolean): Int {
        if (start >= text.length) return text.length
        val first = text.codePointAt(start)
        var end = start + Character.charCount(first)
        var hangul = if (normalizeWidth) hangulNormalizationUnit(first) else -1
        while (end < text.length) {
            val next = text.codePointAt(end)
            val type = Character.getType(next)
            val mark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
            val variation = next in 0xFE00..0xFE0F || next in 0xE0100..0xE01EF
            val composed = if (hangul >= 0) composeHangul(hangul, hangulNormalizationUnit(next)) else -1
            if ((!mark || variation) && next !in 0xFF9E..0xFF9F && composed < 0) break
            // Only a legal modern L+V or LV+T pair extends the Hangul unit. A mark
            // blocks further Hangul composition; a second syllable has its own source range.
            hangul = composed
            end += Character.charCount(next)
            if (end - start > MAX_CLUSTER_UTF16) throw ExactNormalizationException()
        }
        return end
    }
    /** Compatibility/halfwidth Jamo must be classified after their individual NFKC
     * mapping: compatibility KIYEOK is L, so it cannot act as a trailing T. */
    private fun hangulNormalizationUnit(cp: Int): Int = when (cp) {
        in 0x1100..0x11FF, in 0xAC00..0xD7A3 -> cp
        in 0x3130..0x318F, in 0xFFA0..0xFFDC -> {
            val normalized = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
            if (normalized.length == 1) normalized[0].code else -1
        }
        else -> -1
    }
    /** Unicode Hangul composition uses 19 L, 21 V and 27 nonempty T Jamo.
     * Archaic Jamo, L+L, V+V, LVT+T and the next L never extend this unit. */
    private fun composeHangul(first: Int, next: Int): Int = when {
        first in 0x1100..0x1112 && next in 0x1161..0x1175 ->
            0xAC00 + ((first - 0x1100) * 21 + next - 0x1161) * 28
        first in 0xAC00..0xD7A3 && (first - 0xAC00) % 28 == 0 && next in 0x11A8..0x11C2 ->
            first + next - 0x11A7
        else -> -1
    }

    private fun normalizeCluster(raw: String, options: ExactMatchOptions): String {
        val width = if (options.normalizeWidth) Normalizer.normalize(raw, Normalizer.Form.NFKC) else raw
        return if (options.ignoreCase) foldCase(width) else width
    }
    /** Each normalized codepoint has the same fold in a query and in a source cluster.
     * Whole-string lowercasing is contextual (Greek final sigma); applying the expanding
     * ROOT upper/lower fold independently avoids that context while keeping ß/İ expansion. */
    private fun foldCase(value: String): String = buildString(value.length) {
        var at = 0
        while (at < value.length) {
            val next = at + Character.charCount(value.codePointAt(at))
            append(value.substring(at,next).uppercase(Locale.ROOT).lowercase(Locale.ROOT))
            at = next
        }
    }
    private fun normalizeQuery(raw: String, options: ExactMatchOptions): String {
        val value = normalizeCluster(raw, options)
        if (!options.collapseWhitespace) return value
        val result = StringBuilder(value.length)
        var pending = false
        var at = 0
        while (at < value.length) {
            val cp = value.codePointAt(at)
            if (isSpace(cp)) pending = result.isNotEmpty()
            else {
                if (pending) result.append(' ')
                result.appendCodePoint(cp)
                pending = false
            }
            at += Character.charCount(cp)
        }
        return result.toString()
    }
    private fun hasUnpairedSurrogate(value: String): Boolean {
        var at = 0
        while (at < value.length) {
            val ch = value[at]
            if (Character.isLowSurrogate(ch)) return true
            if (Character.isHighSurrogate(ch)) {
                if (at + 1 >= value.length || !Character.isLowSurrogate(value[at + 1])) return true
                at += 1
            }
            at += 1
        }
        return false
    }
    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
    private fun utf8Bytes(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
