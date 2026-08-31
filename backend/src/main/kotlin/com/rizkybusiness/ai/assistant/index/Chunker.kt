package com.rizkybusiness.ai.assistant.index

/** One embeddable slice of a file. Lines are 1-based and inclusive. */
data class Chunk(val text: String, val startLine: Int, val endLine: Int)

/**
 * Line-based chunker. Pure Kotlin on purpose: no PSI (multi-IDE rule) and unit-testable
 * without the platform. Chunks accumulate whole lines up to [maxChunkChars]; consecutive
 * chunks share a small line overlap for continuity; a single line longer than the budget
 * (minified/generated content) is hard-split without overlap.
 */
class Chunker(
    private val maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
    private val overlapLines: Int = DEFAULT_OVERLAP_LINES,
    private val maxOverlapChars: Int = DEFAULT_MAX_OVERLAP_CHARS,
) {
    companion object {
        const val DEFAULT_MAX_CHUNK_CHARS = 2_000
        const val DEFAULT_OVERLAP_LINES = 8
        const val DEFAULT_MAX_OVERLAP_CHARS = 200
    }

    fun chunk(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<Chunk>()
        val buffer = ArrayDeque<Pair<Int, String>>()
        var bufferChars = 0
        var lastEmittedEndLine = 0

        fun emitBuffer(keepOverlap: Boolean) {
            if (buffer.isEmpty()) return
            chunks += Chunk(
                text = buffer.joinToString("\n") { it.second },
                startLine = buffer.first().first,
                endLine = buffer.last().first,
            )
            lastEmittedEndLine = buffer.last().first
            val overlap = if (keepOverlap) takeOverlap(buffer) else emptyList()
            buffer.clear()
            buffer.addAll(overlap)
            bufferChars = overlap.sumOf { it.second.length + 1 }
        }

        text.lines().forEachIndexed { index, line ->
            val lineNo = index + 1
            if (line.length > maxChunkChars) {
                emitBuffer(keepOverlap = false)
                var offset = 0
                while (offset < line.length) {
                    val end = minOf(offset + maxChunkChars, line.length)
                    chunks += Chunk(line.substring(offset, end), lineNo, lineNo)
                    offset = end
                }
                lastEmittedEndLine = lineNo
                return@forEachIndexed
            }
            if (buffer.isNotEmpty() && bufferChars + line.length + 1 > maxChunkChars) {
                emitBuffer(keepOverlap = true)
            }
            buffer.addLast(lineNo to line)
            bufferChars += line.length + 1
        }

        // Trailing content — but never a chunk that is only the retained overlap.
        if (buffer.isNotEmpty() && buffer.last().first > lastEmittedEndLine) {
            chunks += Chunk(
                text = buffer.joinToString("\n") { it.second },
                startLine = buffer.first().first,
                endLine = buffer.last().first,
            )
        }
        return chunks.filter { it.text.isNotBlank() }
    }

    private fun takeOverlap(buffer: ArrayDeque<Pair<Int, String>>): List<Pair<Int, String>> {
        val overlap = ArrayDeque<Pair<Int, String>>()
        var chars = 0
        for ((lineNo, line) in buffer.asReversed()) {
            if (overlap.size >= overlapLines || chars + line.length + 1 > maxOverlapChars) break
            overlap.addFirst(lineNo to line)
            chars += line.length + 1
        }
        return overlap
    }
}
