package com.rizkybusiness.ai.assistant.chatApp.ui.markdown

import com.rizkybusiness.ai.assistant.chatApp.ui.markdown.MarkdownBlocks.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun `plain text is one paragraph`() {
        val blocks = MarkdownBlocks.parse("hello\nworld")
        assertEquals(1, blocks.size)
        assertEquals("hello<br>world", (blocks[0] as Block.Paragraph).html)
    }

    @Test
    fun `fenced code block with language splits out`() {
        val blocks = MarkdownBlocks.parse("intro\n```kotlin\nval x = 1\n```\noutro")
        assertEquals(3, blocks.size)
        val code = blocks[1] as Block.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.text)
        assertEquals("outro", (blocks[2] as Block.Paragraph).html)
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
    fun `html is escaped everywhere`() {
        val paragraph = MarkdownBlocks.parse("a <b> & c")[0] as Block.Paragraph
        assertEquals("a &lt;b&gt; &amp; c", paragraph.html)
        val inline = MarkdownBlocks.inlineToHtml("`<script>`")
        assertEquals("<code>&lt;script&gt;</code>", inline)
    }

    @Test
    fun `inline styling applies outside code spans only`() {
        assertEquals("<b>bold</b>", MarkdownBlocks.inlineToHtml("**bold**"))
        assertEquals("<i>it</i>", MarkdownBlocks.inlineToHtml("*it*"))
        assertEquals("<code>**not bold**</code>", MarkdownBlocks.inlineToHtml("`**not bold**`"))
        assertEquals(
            "<a href=\"https://x.dev\">x</a>",
            MarkdownBlocks.inlineToHtml("[x](https://x.dev)"),
        )
    }

    @Test
    fun `unbalanced trailing backtick renders literally`() {
        assertEquals("start &#96;code".replace("&#96;", "`"), MarkdownBlocks.inlineToHtml("start `code"))
    }

    @Test
    fun `headers and lists transform`() {
        assertEquals("<b>Title</b>", MarkdownBlocks.lineToHtml("## Title"))
        assertTrue(MarkdownBlocks.lineToHtml("- item").contains("•"))
        assertEquals("1. first", MarkdownBlocks.lineToHtml("1. first"))
    }

    @Test
    fun `blank-only input yields no blocks`() {
        assertEquals(emptyList<Block>(), MarkdownBlocks.parse("  \n\n"))
    }

    @Test
    fun `pipe table with separator becomes a table block`() {
        val md = "intro\n| Name | Age |\n|------|-----|\n| Ada | 36 |\n| Bob | 40 |\noutro"
        val blocks = MarkdownBlocks.parse(md)
        assertEquals(3, blocks.size)
        val table = blocks[1] as Block.Table
        assertTrue(table.html.startsWith("<table"))
        assertTrue(table.html.contains("<th align=\"left\">Name</th>"))
        assertTrue(table.html.contains("<td>Ada</td>"))
        assertEquals("outro", (blocks[2] as Block.Paragraph).html)
    }

    @Test
    fun `table cells get inline styling and escaping`() {
        val md = "| Col |\n|-----|\n| **b** & `<x>` |"
        val table = MarkdownBlocks.parse(md)[0] as Block.Table
        assertTrue(table.html.contains("<b>b</b>"))
        assertTrue(table.html.contains("<code>&lt;x&gt;</code>"))
    }

    @Test
    fun `pipe lines without separator stay plain text`() {
        val blocks = MarkdownBlocks.parse("| just | one | line |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Paragraph)
    }
}
