package com.rizkybusiness.ai.assistant.edit

import com.rizkybusiness.ai.assistant.edit.SearchReplaceApplier.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReplaceApplierTest {

    private fun hunk(search: String, replace: String) =
        "<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE\n"

    private fun ok(old: String?, block: String) = (SearchReplaceApplier.apply(old, block) as Result.Ok).newText

    private fun err(old: String?, block: String) = (SearchReplaceApplier.apply(old, block) as Result.Error).message

    @Test
    fun `exact single hunk`() {
        assertEquals("a\nB\nc\n", ok("a\nb\nc\n", hunk("b", "B")))
    }

    @Test
    fun `multiple hunks apply sequentially`() {
        assertEquals("A\nb\nC\n", ok("a\nb\nc\n", hunk("a", "A") + hunk("c", "C")))
    }

    @Test
    fun `whitespace tolerant when indent differs`() {
        assertEquals("x\n\tbar()\n", ok("x\n\tfoo()\n", hunk("  foo()", "\tbar()")))
    }

    @Test
    fun `not found`() {
        assertEquals("hunk 1: SEARCH text not found", err("a\n", hunk("zzz", "q")))
    }

    @Test
    fun `ambiguous`() {
        assertEquals("hunk 1: SEARCH text matches 2 places", err("a\nb\na\n", hunk("a", "q")))
    }

    @Test
    fun `malformed`() {
        assertEquals(
            "malformed SEARCH/REPLACE block near hunk 1",
            err("a\n", "<<<<<<< SEARCH\na\n=======\nb\n"),
        )
    }

    @Test
    fun `no markers is new file or replacement`() {
        assertEquals("hello\n", ok(null, "hello\n"))
        assertEquals("hello\n", ok("old\n", "hello\n"))
    }

    @Test
    fun `empty search on missing file creates it`() {
        assertEquals("new\n", ok(null, hunk("", "new").replace("SEARCH\n\n", "SEARCH\n")))
        assertEquals(
            "file does not exist, but hunk 1 has SEARCH text",
            err(null, hunk("a", "b")),
        )
    }

    @Test
    fun `crlf file keeps line endings`() {
        assertEquals("a\r\nB\r\nc\r\n", ok("a\r\nb\r\nc\r\n", hunk("b", "B")))
    }

    @Test
    fun `hasMarkers`() {
        assertTrue(SearchReplaceApplier.hasMarkers(hunk("a", "b")))
        assertTrue(!SearchReplaceApplier.hasMarkers("plain"))
    }
}
