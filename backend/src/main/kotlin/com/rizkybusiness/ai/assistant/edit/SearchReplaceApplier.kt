package com.rizkybusiness.ai.assistant.edit

/** Applies SEARCH/REPLACE blocks to file text; pure string logic, no platform APIs. */
object SearchReplaceApplier {

    sealed interface Result {
        data class Ok(val newText: String) : Result
        data class Error(val message: String) : Result
    }

    private const val SEARCH = "<<<<<<< SEARCH"
    private const val DIVIDER = "======="
    private const val REPLACE = ">>>>>>> REPLACE"

    private class Hunk(val search: List<String>, val replace: List<String>)

    private class Malformed(val hunkNo: Int) : Exception()

    fun hasMarkers(block: String): Boolean = lines(block).any { it.trimEnd() == SEARCH }

    /** [oldText] == null means the target file does not exist. */
    fun apply(oldText: String?, block: String): Result {
        if (!hasMarkers(block)) return Result.Ok(block)
        val hunks = try {
            parse(block)
        } catch (e: Malformed) {
            return Result.Error("malformed SEARCH/REPLACE block near hunk ${e.hunkNo}")
        }
        if (oldText == null) {
            hunks.forEachIndexed { i, h ->
                if (h.search.isNotEmpty()) return Result.Error("file does not exist, but hunk ${i + 1} has SEARCH text")
            }
            return Result.Ok(hunks.joinToString("") { h -> h.replace.joinToString("") { "$it\n" } })
        }
        val crlf = "\r\n" in oldText
        var text = if (crlf) oldText.replace("\r\n", "\n") else oldText
        hunks.forEachIndexed { i, h ->
            when (val r = applyHunk(text, h, i + 1)) {
                is Result.Ok -> text = r.newText
                is Result.Error -> return r
            }
        }
        return Result.Ok(if (crlf) text.replace("\n", "\r\n") else text)
    }

    private fun lines(s: String) = s.split("\n").map { it.removeSuffix("\r") }

    private fun parse(block: String): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        var state = 0 // 0 outside, 1 in SEARCH, 2 in REPLACE
        var search = mutableListOf<String>()
        var replace = mutableListOf<String>()
        for (line in lines(block)) {
            val t = line.trimEnd()
            when {
                t == SEARCH -> {
                    if (state != 0) throw Malformed(hunks.size + 1)
                    state = 1; search = mutableListOf(); replace = mutableListOf()
                }
                t == DIVIDER && state == 1 -> state = 2
                t == REPLACE -> {
                    if (state != 2) throw Malformed(hunks.size + 1)
                    hunks += Hunk(search, replace); state = 0
                }
                state == 1 -> search += line
                state == 2 -> replace += line
            }
        }
        if (state != 0) throw Malformed(hunks.size + 1)
        return hunks
    }

    private fun applyHunk(text: String, h: Hunk, n: Int): Result {
        val replacement = h.replace.joinToString("\n")
        if (h.search.isEmpty()) {
            val sep = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
            return Result.Ok("$text$sep$replacement\n")
        }
        val search = h.search.joinToString("\n")
        val i = text.indexOf(search)
        if (i >= 0) {
            var count = 1
            var next = text.indexOf(search, i + 1)
            while (next >= 0) { count++; next = text.indexOf(search, next + 1) }
            if (count > 1) return Result.Error("hunk $n: SEARCH text matches $count places")
            var end = i + search.length
            if (h.replace.isEmpty() && text.startsWith("\n", end)) end++
            return Result.Ok(text.substring(0, i) + replacement + text.substring(end))
        }
        val src = text.split("\n")
        val want = h.search.map { it.trim() }
        val starts = (0..src.size - want.size).filter { s -> want.indices.all { src[s + it].trim() == want[it] } }
        if (starts.size > 1) return Result.Error("hunk $n: SEARCH text matches ${starts.size} places")
        if (starts.isEmpty()) return Result.Error("hunk $n: SEARCH text not found")
        val s = starts[0]
        return Result.Ok((src.subList(0, s) + h.replace + src.subList(s + want.size, src.size)).joinToString("\n"))
    }
}
