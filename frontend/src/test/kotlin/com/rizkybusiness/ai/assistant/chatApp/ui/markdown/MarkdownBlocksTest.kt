package com.rizkybusiness.ai.assistant.chatApp.ui.markdown

import com.rizkybusiness.ai.assistant.chatApp.ui.markdown.MarkdownBlocks.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    private fun paragraphHtml(md: String): String {
        val blocks = MarkdownBlocks.parse(md)
        assertEquals(1, blocks.size)
        return (blocks[0] as Block.Paragraph).html
    }

    @Test
    fun `plain text renders as a paragraph`() {
        val html = paragraphHtml("hello world")
        assertTrue(html.contains("hello world"))
    }

    @Test
    fun `fenced code block with language splits out`() {
        val blocks = MarkdownBlocks.parse("intro\n```kotlin\nval x = 1\n```\noutro")
        assertEquals(3, blocks.size)
        val code = blocks[1] as Block.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.text)
        assertTrue((blocks[2] as Block.Paragraph).html.contains("outro"))
    }

    @Test
    fun `unclosed fence streams as code`() {
        val blocks = MarkdownBlocks.parse("text\n```python\nprint(1)")
        assertEquals(2, blocks.size)
        val code = blocks[1] as Block.Code
        assertEquals("python", code.language)
        assertEquals("print(1)", code.text)
    }

    @Test
    fun `raw html in source text cannot inject markup`() {
        val html = paragraphHtml("a <script>alert(1)</script> & b")
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("alert(1)"))
    }

    @Test
    fun `inline styling renders`() {
        val html = paragraphHtml("**bold** and *em* and `span` and [x](https://x.dev)")
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>em</em>"))
        assertTrue(html.contains("<code>span</code>"))
        assertTrue(html.contains("href=\"https://x.dev\""))
    }

    @Test
    fun `gfm strikethrough renders`() {
        val html = paragraphHtml("~~gone~~")
        assertTrue(html.contains("gone") && ("<s" in html || "<del" in html))
    }

    @Test
    fun `tables render with borders`() {
        val html = paragraphHtml("| Name | Age |\n|------|-----|\n| Ada | 36 |")
        assertTrue(html.contains("<table border=\"1\""))
        assertTrue(html.contains("<th"))
        assertTrue(html.contains("Ada"))
    }

    @Test
    fun `nested lists render`() {
        val html = paragraphHtml("- top\n  - nested\n- next")
        assertTrue(Regex("<ul").findAll(html).count() >= 2)
        assertTrue(html.contains("nested"))
    }

    @Test
    fun `blockquotes render`() {
        val html = paragraphHtml("> quoted wisdom")
        assertTrue(html.contains("<blockquote>"))
    }

    @Test
    fun `task lists render as glyphs not form widgets`() {
        val html = paragraphHtml("- [x] done\n- [ ] todo")
        assertFalse(html.contains("<input"))
        assertTrue(html.contains("☑") && html.contains("☐"))
    }

    @Test
    fun `images become placeholders instead of remote fetches`() {
        val html = paragraphHtml("![alt](https://example.com/x.png)")
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("[image]"))
    }

    @Test
    fun `blank-only input yields no blocks`() {
        assertEquals(emptyList<Block>(), MarkdownBlocks.parse("  \n\n"))
    }
}
