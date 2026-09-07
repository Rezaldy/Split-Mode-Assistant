package com.rizkybusiness.ai.assistant.skills

import com.rizkybusiness.ai.assistant.SkillFileDto
import com.rizkybusiness.ai.assistant.SkillUploadDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Base64

class SkillStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store() = SkillStore(tempFolder.root.toPath())

    private fun encode(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun manifest(name: String = "my-skill", description: String = "Does a thing") =
        """
        ---
        name: $name
        description: $description
        ---
        Body.
        """.trimIndent()

    private fun upload(
        name: String = "my-skill",
        files: List<SkillFileDto>,
        overwrite: Boolean = false,
    ) = SkillUploadDto(name = name, files = files, overwrite = overwrite)

    @Test
    fun `round-trip write creates the skill folder with nested files`() {
        val files = listOf(
            SkillFileDto("SKILL.md", encode(manifest())),
            SkillFileDto("references/x.md", encode("reference content")),
        )
        val result = store().write(upload(files = files))
        assertTrue(result is SkillStoreResult.Ok)
        assertEquals("my-skill", (result as SkillStoreResult.Ok).name)

        val root = tempFolder.root.toPath().resolve("my-skill")
        assertTrue(Files.isRegularFile(root.resolve("SKILL.md")))
        assertTrue(Files.isRegularFile(root.resolve("references/x.md")))
        assertEquals("reference content", Files.readString(root.resolve("references/x.md")))
    }

    @Test
    fun `missing manifest file is rejected`() {
        val files = listOf(SkillFileDto("references/x.md", encode("content")))
        val result = store().write(upload(files = files))
        assertTrue(result is SkillStoreResult.Invalid)
        assertEquals("missing.manifest", (result as SkillStoreResult.Invalid).code)
    }

    @Test
    fun `path traversal is rejected`() {
        val files = listOf(
            SkillFileDto("SKILL.md", encode(manifest())),
            SkillFileDto("../evil.md", encode("evil")),
        )
        val result = store().write(upload(files = files))
        assertTrue(result is SkillStoreResult.Invalid)
        assertEquals("bad.path", (result as SkillStoreResult.Invalid).code)
    }

    @Test
    fun `absolute path is rejected`() {
        val absolute = tempFolder.newFolder().toPath().resolve("x.md").toString()
        val files = listOf(
            SkillFileDto("SKILL.md", encode(manifest())),
            SkillFileDto(absolute, encode("evil")),
        )
        val result = store().write(upload(files = files))
        assertTrue(result is SkillStoreResult.Invalid)
        assertEquals("bad.path", (result as SkillStoreResult.Invalid).code)
    }

    @Test
    fun `oversize file is rejected`() {
        val big = "x".repeat((SkillStore.MAX_FILE_BYTES + 1).toInt())
        val files = listOf(
            SkillFileDto("SKILL.md", encode(manifest())),
            SkillFileDto("references/big.md", encode(big)),
        )
        val result = store().write(upload(files = files))
        assertTrue(result is SkillStoreResult.TooLarge)
    }

    @Test
    fun `second write without overwrite reports exists`() {
        val files = listOf(SkillFileDto("SKILL.md", encode(manifest())))
        val s = store()
        assertTrue(s.write(upload(files = files)) is SkillStoreResult.Ok)
        val result = s.write(upload(files = files))
        assertTrue(result is SkillStoreResult.Exists)
    }

    @Test
    fun `overwrite replaces the folder and drops old extra files`() {
        val s = store()
        val firstFiles = listOf(
            SkillFileDto("SKILL.md", encode(manifest())),
            SkillFileDto("references/old.md", encode("old content")),
        )
        assertTrue(s.write(upload(files = firstFiles)) is SkillStoreResult.Ok)

        val secondFiles = listOf(SkillFileDto("SKILL.md", encode(manifest())))
        val result = s.write(upload(files = secondFiles, overwrite = true))
        assertTrue(result is SkillStoreResult.Ok)

        val root = tempFolder.root.toPath().resolve("my-skill")
        assertTrue(Files.isRegularFile(root.resolve("SKILL.md")))
        assertFalse(Files.exists(root.resolve("references/old.md")))
    }

    @Test
    fun `folder is named after the manifest name, not the upload name`() {
        val files = listOf(SkillFileDto("SKILL.md", encode(manifest(name = "actual-name"))))
        val result = store().write(upload(name = "upload-name", files = files))
        assertTrue(result is SkillStoreResult.Ok)
        assertEquals("actual-name", (result as SkillStoreResult.Ok).name)
        assertTrue(Files.isDirectory(tempFolder.root.toPath().resolve("actual-name")))
        assertFalse(Files.isDirectory(tempFolder.root.toPath().resolve("upload-name")))
    }

    @Test
    fun `deleting an unknown skill reports not found`() {
        val result = store().delete("does-not-exist")
        assertTrue(result is SkillStoreResult.NotFound)
    }

    @Test
    fun `deleting with a path traversal name is rejected`() {
        val result = store().delete("../x")
        assertTrue(result is SkillStoreResult.Invalid)
        assertEquals("bad.path", (result as SkillStoreResult.Invalid).code)
    }

    @Test
    fun `deleting an existing skill removes its folder`() {
        val s = store()
        val files = listOf(SkillFileDto("SKILL.md", encode(manifest())))
        assertTrue(s.write(upload(files = files)) is SkillStoreResult.Ok)

        val result = s.delete("my-skill")
        assertTrue(result is SkillStoreResult.Ok)
        assertFalse(Files.exists(tempFolder.root.toPath().resolve("my-skill")))
    }
}
