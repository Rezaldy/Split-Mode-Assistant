package com.rizkybusiness.ai.assistant.index

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class VectorMathTest {

    @Test
    fun `normalize produces unit length`() {
        val v = VectorMath.normalizeInPlace(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
    }

    @Test
    fun `normalize of zero vector is a no-op`() {
        val v = VectorMath.normalizeInPlace(floatArrayOf(0f, 0f))
        assertEquals(0f, v[0], 0f)
    }

    @Test
    fun `topK orders by similarity descending`() {
        val query = VectorMath.normalizeInPlace(floatArrayOf(1f, 0f))
        val vectors = listOf(
            VectorMath.normalizeInPlace(floatArrayOf(0f, 1f)),      // orthogonal
            VectorMath.normalizeInPlace(floatArrayOf(1f, 0.1f)),    // near-identical
            VectorMath.normalizeInPlace(floatArrayOf(1f, 1f)),      // 45 degrees
        )
        val top = VectorMath.topK(query, vectors, 2)
        assertEquals(2, top.size)
        assertEquals(1, top[0].first)
        assertEquals(2, top[1].first)
        assert(top[0].second > top[1].second)
        assert(abs(top[0].second) <= 1.0001f)
    }
}
