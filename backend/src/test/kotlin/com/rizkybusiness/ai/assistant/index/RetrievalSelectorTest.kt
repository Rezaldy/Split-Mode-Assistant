package com.rizkybusiness.ai.assistant.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalSelectorTest {

    private fun unit(x: Float, y: Float) = VectorMath.normalizeInPlace(floatArrayOf(x, y))

    private fun entry(vararg chunks: Triple<Int, Int, FloatArray>) =
        ProjectIndexService.FileIndexEntry(
            contentHash = "h",
            chunks = chunks.map { ChunkMeta(it.first, it.second) },
            vectors = chunks.map { it.third },
        )

    @Test
    fun `score floor filters weak matches and topK orders by similarity`() {
        val query = unit(1f, 0f)
        val entries = mapOf(
            "strong.kt" to entry(Triple(1, 10, unit(1f, 0.05f))),
            "medium.kt" to entry(Triple(1, 10, unit(1f, 1f))),
            "weak.kt" to entry(Triple(1, 10, unit(0.05f, 1f))),
        )
        val hits = RetrievalSelector.select(query, entries, excludePaths = emptySet(), topK = 5)
        assertEquals(listOf("strong.kt", "medium.kt"), hits.map { it.path })
        assertTrue(hits.all { it.score >= RetrievalSelector.DEFAULT_SCORE_FLOOR })
    }

    @Test
    fun `excluded paths never appear`() {
        val query = unit(1f, 0f)
        val entries = mapOf(
            "open.kt" to entry(Triple(1, 10, unit(1f, 0f))),
            "closed.kt" to entry(Triple(1, 10, unit(1f, 0.1f))),
        )
        val hits = RetrievalSelector.select(query, entries, excludePaths = setOf("open.kt"))
        assertEquals(listOf("closed.kt"), hits.map { it.path })
    }

    @Test
    fun `adjacent chunks from one file merge into a single range with max score`() {
        val query = unit(1f, 0f)
        val entries = mapOf(
            "a.kt" to entry(
                Triple(1, 10, unit(1f, 0.2f)),
                Triple(8, 20, unit(1f, 0.1f)),
                Triple(40, 50, unit(1f, 0.3f)),
            ),
        )
        val hits = RetrievalSelector.select(query, entries, excludePaths = emptySet())
        assertEquals(2, hits.size)
        val mergedHit = hits.first { it.startLine == 1 }
        assertEquals(20, mergedHit.endLine)
        val separate = hits.first { it.startLine == 40 }
        assertEquals(50, separate.endLine)
    }

    @Test
    fun `topK caps the result count before merging`() {
        val query = unit(1f, 0f)
        val entries = (1..30).associate { i ->
            "f$i.kt" to entry(Triple(1, 5, unit(1f, i * 0.01f)))
        }
        val hits = RetrievalSelector.select(query, entries, excludePaths = emptySet(), topK = 12)
        assertEquals(12, hits.size)
    }
}
