package com.rizkybusiness.ai.assistant.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManifestParserTest {

    private fun parse(text: String, expectedDirName: String? = null) =
        SkillManifestParser.parse(text, expectedDirName)

    private fun ok(text: String, expectedDirName: String? = null): SkillManifest {
        val result = parse(text, expectedDirName)
        assertTrue("expected Ok but was $result", result is SkillParseResult.Ok)
        return (result as SkillParseResult.Ok).manifest
    }

    @Test
    fun `minimal valid manifest parses`() {
        val manifest = ok(
            """
            ---
            name: pdf-fill
            description: Fills PDF forms
            ---
            Body text.
            """.trimIndent()
        )
        assertEquals("pdf-fill", manifest.name)
        assertEquals("Fills PDF forms", manifest.description)
        assertEquals("Body text.", manifest.body)
        assertTrue(manifest.warnings.isEmpty())
    }

    @Test
    fun `quoted description containing a colon is unescaped`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: "Handles: colons and \"quotes\" fine"
            ---
            """.trimIndent()
        )
        assertEquals("Handles: colons and \"quotes\" fine", manifest.description)
    }

    @Test
    fun `single-quoted value is stripped`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: 'A simple description'
            ---
            """.trimIndent()
        )
        assertEquals("A simple description", manifest.description)
    }

    @Test
    fun `pipe block scalar keeps newlines`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: |
              line one
              line two
            ---
            """.trimIndent()
        )
        assertEquals("line one\nline two", manifest.description)
    }

    @Test
    fun `greater-than block scalar folds single newlines`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: >
              line one
              line two

              new paragraph
            ---
            """.trimIndent()
        )
        assertEquals("line one line two\nnew paragraph", manifest.description)
    }

    @Test
    fun `nested metadata map is parsed`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: bar
            metadata:
              author: Jane Doe
              version: "1.0"
            ---
            """.trimIndent()
        )
        assertEquals(mapOf("author" to "Jane Doe", "version" to "1.0"), manifest.metadata)
    }

    @Test
    fun `allowed-tools is split on whitespace`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: bar
            allowed-tools: read  write   exec
            ---
            """.trimIndent()
        )
        assertEquals(listOf("read", "write", "exec"), manifest.allowedTools)
    }

    @Test
    fun `unknown key produces a warning but still loads`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: bar
            author: someone
            ---
            """.trimIndent()
        )
        assertEquals(listOf(SkillWarning(SkillWarning.UNKNOWN_KEY, "author")), manifest.warnings)
    }

    @Test
    fun `missing description is skipped`() {
        val result = parse(
            """
            ---
            name: foo
            ---
            """.trimIndent()
        )
        assertTrue(result is SkillParseResult.Skipped)
        assertEquals("missing description", (result as SkillParseResult.Skipped).reason)
    }

    @Test
    fun `uppercase name is skipped`() {
        val result = parse(
            """
            ---
            name: Foo-Bar
            description: bar
            ---
            """.trimIndent()
        )
        assertTrue(result is SkillParseResult.Skipped)
    }

    @Test
    fun `name differing from directory warns name mismatch`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: bar
            ---
            """.trimIndent(),
            expectedDirName = "other-dir",
        )
        assertEquals(listOf(SkillWarning(SkillWarning.NAME_MISMATCH, "other-dir")), manifest.warnings)
    }

    @Test
    fun `no frontmatter is skipped`() {
        val result = parse("Just a plain markdown file.\nNo frontmatter here.")
        assertTrue(result is SkillParseResult.Skipped)
        assertEquals("no YAML frontmatter", (result as SkillParseResult.Skipped).reason)
    }

    @Test
    fun `CRLF input parses correctly`() {
        val text = "---\r\nname: foo\r\ndescription: bar\r\n---\r\nBody line.\r\n"
        val manifest = ok(text)
        assertEquals("foo", manifest.name)
        assertEquals("bar", manifest.description)
        assertEquals("Body line.", manifest.body)
    }

    @Test
    fun `body is extracted with frontmatter stripped`() {
        val manifest = ok(
            """
            ---
            name: foo
            description: bar
            ---

            # Heading

            Some body content.
            """.trimIndent()
        )
        assertEquals("# Heading\n\nSome body content.", manifest.body)
    }

    @Test
    fun `comment lines in frontmatter are ignored`() {
        val manifest = ok(
            """
            ---
            # this is a comment
            name: foo
            # another comment
            description: bar
            ---
            """.trimIndent()
        )
        assertEquals("foo", manifest.name)
        assertEquals("bar", manifest.description)
        assertNull(manifest.license)
    }
}
