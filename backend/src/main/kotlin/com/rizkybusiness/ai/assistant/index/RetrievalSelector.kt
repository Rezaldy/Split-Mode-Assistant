package com.rizkybusiness.ai.assistant.index

/**
 * Pure retrieval selection: brute-force similarity over the in-memory index (caps make
 * this milliseconds — see ProjectIndexService), score floor so off-corpus questions pull
 * nothing, and adjacent-chunk merging so one file region reads as one snippet.
 */
object RetrievalSelector {

    const val DEFAULT_TOP_K = 12
    const val DEFAULT_SCORE_FLOOR = 0.30f

    data class Hit(val path: String, val startLine: Int, val endLine: Int, val score: Float)

    fun select(
        query: FloatArray,
        entries: Map<String, ProjectIndexService.FileIndexEntry>,
        excludePaths: Set<String>,
        topK: Int = DEFAULT_TOP_K,
        scoreFloor: Float = DEFAULT_SCORE_FLOOR,
    ): List<Hit> {
        val scored = mutableListOf<Hit>()
        for ((path, entry) in entries) {
            if (path in excludePaths) continue
            entry.vectors.forEachIndexed { index, vector ->
                val score = VectorMath.dot(query, vector)
                if (score >= scoreFloor) {
                    val meta = entry.chunks[index]
                    scored += Hit(path, meta.startLine, meta.endLine, score)
                }
            }
        }
        return mergeAdjacent(scored.sortedByDescending { it.score }.take(topK))
    }

    /** Hits from the same file with touching/overlapping ranges merge; strongest score wins. */
    fun mergeAdjacent(hits: List<Hit>): List<Hit> {
        val merged = mutableListOf<Hit>()
        for (group in hits.groupBy { it.path }.values) {
            val sorted = group.sortedBy { it.startLine }
            var current = sorted.first()
            for (next in sorted.drop(1)) {
                current = if (next.startLine <= current.endLine + 1) {
                    current.copy(
                        endLine = maxOf(current.endLine, next.endLine),
                        score = maxOf(current.score, next.score),
                    )
                } else {
                    merged += current
                    next
                }
            }
            merged += current
        }
        return merged.sortedByDescending { it.score }
    }
}
