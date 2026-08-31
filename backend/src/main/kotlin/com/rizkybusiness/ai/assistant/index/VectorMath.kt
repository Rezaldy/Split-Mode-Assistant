package com.rizkybusiness.ai.assistant.index

import kotlin.math.sqrt

/** Pure vector helpers. Vectors are L2-normalized at write time, so cosine = dot product. */
object VectorMath {

    fun normalizeInPlace(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v * v
        val norm = sqrt(sum)
        if (norm > 0.0) {
            for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        }
        return vector
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** Indices of the [k] highest-scoring vectors with their scores, descending. */
    fun topK(query: FloatArray, vectors: List<FloatArray>, k: Int): List<Pair<Int, Float>> {
        return vectors.asSequence()
            .mapIndexed { index, vector -> index to dot(query, vector) }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
    }
}
