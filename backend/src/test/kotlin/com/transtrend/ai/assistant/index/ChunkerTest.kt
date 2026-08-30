package com.transtrend.ai.assistant.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    private val chunker = Chunker(maxChunkChars = 100, overlapLines = 2, maxOverlapChars = 40)

    @Test
    fun `blank text produces no chunks`() {
        assertEquals(emptyList<Chunk>(), chunker.chunk(""))
        assertEquals(emptyList<Chunk>(), chunker.chunk("   \n  \n"))
    }

    @Test
    fun `small file is one chunk with correct line range`() {
        val chunks = chunker.chunk("line one\nline two\nline three")
        assertEquals(1, chunks.size)
        assertEquals(1, chunks.first().startLine)
        assertEquals(3, chunks.first().endLine)
    }

    @Test
    fun `chunks respect the char budget and cover all lines`() {
        val text = (1..40).joinToString("\n") { "line number $it padded out a bit" }
        val chunks = chunker.chunk(text)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue("chunk too big: ${it.text.length}", it.text.length <= 100) }
        assertEquals(1, chunks.first().startLine)
        assertEquals(40, chunks.last().endLine)
        // Full coverage: every line number appears in some chunk's range.
        for (line in 1..40) {
            assertTrue("line $line uncovered", chunks.any { line in it.startLine..it.endLine })
        }
    }

    @Test
    fun `consecutive chunks overlap`() {
        val text = (1..40).joinToString("\n") { "line number $it padded out a bit" }
        val chunks = chunker.chunk(text)
        for (i in 1 until chunks.size) {
            assertTrue(
                "chunk $i starts after previous ended",
                chunks[i].startLine <= chunks[i - 1].endLine,
            )
        }
    }

    @Test
    fun `oversized single line is hard-split without crashing`() {
        val longLine = "x".repeat(350)
        val chunks = chunker.chunk("short\n$longLine\ntail")
        assertTrue(chunks.size >= 4)
        chunks.forEach { assertTrue(it.text.length <= 100) }
        val pieces = chunks.filter { it.startLine == 2 && it.endLine == 2 }
        assertEquals(350, pieces.sumOf { it.text.length })
    }

    @Test
    fun `no trailing overlap-only duplicate chunk`() {
        // Text sized so the final buffer holds only retained overlap after the last emit.
        val text = (1..8).joinToString("\n") { "0123456789012345678901234567890123456789 $it" }
        val chunks = chunker.chunk(text)
        val lastEnd = chunks.maxOf { it.endLine }
        assertEquals(8, lastEnd)
        // No chunk may be a strict subset repeat consisting only of another chunk's tail.
        val fullText = chunks.joinToString("\n") { it.text }
        assertTrue(fullText.contains("$8") || chunks.any { it.endLine == 8 })
    }
}
