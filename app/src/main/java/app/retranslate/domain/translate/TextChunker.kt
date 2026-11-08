package app.retranslate.domain.translate

/**
 * Splits chapter text into provider-sized chunks (PLAN.md §5.1).
 *
 * Chunks are **contiguous slices** of the input: concatenating them
 * reproduces the input byte-for-byte, so reassembly can never lose or
 * duplicate a character (the §5.1 byte-exact rule). Boundaries prefer
 * paragraph breaks (paragraphs are separated by a blank line, "\n\n",
 * in every text this app stores); a single paragraph longer than the
 * hard maximum is sliced mid-paragraph at a sentence/space break when
 * one is nearby, else at the hard limit itself.
 */
object TextChunker {

    /** Aim for chunks around this size (§5.1). */
    const val TARGET_CHARS = 6_000

    /** Never emit a chunk larger than this (§5.1 hard max). */
    const val MAX_CHARS = 8_000

    /** How far back from the hard limit to look for a soft break. */
    private const val SOFT_BREAK_WINDOW = 800

    private const val PARAGRAPH_SEPARATOR = "\n\n"

    fun chunk(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= TARGET_CHARS) return listOf(text)

        // Paragraph units: paragraph text + its trailing separator, so
        // units concatenate back to the input exactly.
        val units = paragraphUnits(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString()
                current.setLength(0)
            }
        }
        for (unit in units) {
            when {
                unit.length > MAX_CHARS -> {
                    flush()
                    chunks += sliceOversized(unit)
                }
                current.length + unit.length <= TARGET_CHARS -> current.append(unit)
                current.length + unit.length <= MAX_CHARS && current.isEmpty() ->
                    current.append(unit)
                else -> {
                    flush()
                    current.append(unit)
                }
            }
        }
        flush()
        return chunks
    }

    /** Paragraphs of a text (blank-line separated, empties dropped). */
    fun paragraphsOf(text: String): List<String> =
        text.split(PARAGRAPH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    fun countParagraphs(text: String): Int = paragraphsOf(text).size

    /** Units of `paragraph + separator` covering [text] exactly. */
    private fun paragraphUnits(text: String): List<String> {
        val units = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val sep = text.indexOf(PARAGRAPH_SEPARATOR, start)
            if (sep < 0) {
                units += text.substring(start)
                break
            }
            val end = sep + PARAGRAPH_SEPARATOR.length
            units += text.substring(start, end)
            start = end
        }
        return units
    }

    /** Contiguous slices of an oversized unit, each ≤ [MAX_CHARS]. */
    private fun sliceOversized(unit: String): List<String> {
        val slices = mutableListOf<String>()
        var start = 0
        while (unit.length - start > MAX_CHARS) {
            val hardEnd = start + MAX_CHARS
            val breakAt = findSoftBreak(unit, start, hardEnd)
            slices += unit.substring(start, breakAt)
            start = breakAt
        }
        if (start < unit.length) slices += unit.substring(start)
        return slices
    }

    /**
     * The last sentence end / space / newline in the window before
     * [hardEnd]; [hardEnd] itself when the window has no break.
     */
    private fun findSoftBreak(text: String, start: Int, hardEnd: Int): Int {
        val windowStart = maxOf(start + 1, hardEnd - SOFT_BREAK_WINDOW)
        var i = hardEnd - 1
        while (i >= windowStart) {
            val c = text[i]
            if (c == '\n' || c == ' ') return i + 1
            if ((c == '.' || c == '!' || c == '?' || c == '。') &&
                i + 1 < text.length && text[i + 1] == ' '
            ) {
                return i + 2
            }
            i--
        }
        return hardEnd
    }
}
