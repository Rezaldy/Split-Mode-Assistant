package com.rizkybusiness.ai.assistant.index

/**
 * Pure retrieval selection: brute-force similarity over the in-memory index (caps make
 * this milliseconds — see ProjectIndexService), score floor so off-corpus questions pull
 * nothing, and adjacent-chunk merging so one file region reads as one snippet.
 *
 * Hybrid scoring: identifier-like terms in the question (CamelCase, snake_case, dotted
 * names, file names) are matched lexically against each chunk's file name and the identifier
 * tokens stored with the chunk. Embeddings blur exact names; a lexical hit lifts the chunk
 * and keeps it eligible even below the cosine floor. A question with no such terms scores
 * by cosine alone, exactly as before.
 */
object RetrievalSelector {

    const val DEFAULT_TOP_K = 12
    const val DEFAULT_SCORE_FLOOR = 0.30f
    const val COSINE_WEIGHT = 0.7f
    const val LEXICAL_WEIGHT = 0.3f
    /** Share of the lexical score a file-name match earns; the rest is split over term hits in the chunk. */
    const val FILE_NAME_SHARE = 0.6f
    const val MAX_CHUNK_TOKENS = 128

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
    private val QUESTION_TERM = Regex("[A-Za-z_][A-Za-z0-9_.]*")

    data class Hit(val path: String, val startLine: Int, val endLine: Int, val score: Float)

    fun select(
        query: FloatArray,
        entries: Map<String, ProjectIndexService.FileIndexEntry>,
        excludePaths: Set<String>,
        topK: Int = DEFAULT_TOP_K,
        scoreFloor: Float = DEFAULT_SCORE_FLOOR,
        question: String = "",
    ): List<Hit> {
        val terms = questionTokens(question)
        val scored = mutableListOf<Hit>()
        for ((path, entry) in entries) {
            if (path in excludePaths) continue
            entry.vectors.forEachIndexed { index, vector ->
                val cosine = VectorMath.dot(query, vector)
                val meta = entry.chunks[index]
                val lexical = lexicalScore(terms, path, meta.tokens)
                if (cosine < scoreFloor && lexical == 0f) return@forEachIndexed
                val score = if (terms.isEmpty()) cosine else COSINE_WEIGHT * cosine + LEXICAL_WEIGHT * lexical
                scored += Hit(path, meta.startLine, meta.endLine, score)
            }
        }
        return mergeAdjacent(scored.sortedByDescending { it.score }.take(topK))
    }

    /** Lowercased identifiers of a chunk, stored in the index for [lexicalScore]. */
    fun chunkTokens(text: String): List<String> =
        // ponytail: first 128 distinct identifiers per ≤2k-char chunk is nearly all of them;
        // keywords (val, fun, return) waste a few slots. A stop list is the upgrade if it matters.
        IDENTIFIER.findAll(text).map { it.value.lowercase() }.distinct().take(MAX_CHUNK_TOKENS).toList()

    /**
     * Identifier-like terms of a question, lowercased: words with a capital, underscore or digit,
     * plus every part (and the whole) of a dotted name such as `Foo.bar` or `Foo.kt`. Plain
     * lowercase words are dropped so "the file" never matches an identifier named `file`.
     */
    fun questionTokens(question: String): List<String> =
        QUESTION_TERM.findAll(question).flatMap { match ->
            val term = match.value.trim('.')
            val parts = term.split('.').filter { it.length >= 3 }
            if (term.contains('.')) parts + term else parts.filter(::looksLikeIdentifier)
        }.map { it.lowercase() }.distinct().toList()

    // ponytail: a lowercase single word ("chunker") is never a term; telling it from English
    // needs a project symbol table, which is the upgrade path.
    private fun looksLikeIdentifier(word: String) =
        word.length >= 3 && word.any { it.isUpperCase() || it == '_' || it.isDigit() }

    /** 0..1: [FILE_NAME_SHARE] when a term equals the file name or its stem, plus the share of terms found in the chunk. */
    fun lexicalScore(terms: List<String>, path: String, chunkTokens: List<String>): Float {
        if (terms.isEmpty()) return 0f
        val fileName = path.substringAfterLast('/').lowercase()
        val stem = fileName.substringBeforeLast('.')
        val fileHit = terms.any { it == fileName || it == stem }
        val hits = terms.count { it in chunkTokens }
        return (if (fileHit) FILE_NAME_SHARE else 0f) + (1f - FILE_NAME_SHARE) * hits / terms.size
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
