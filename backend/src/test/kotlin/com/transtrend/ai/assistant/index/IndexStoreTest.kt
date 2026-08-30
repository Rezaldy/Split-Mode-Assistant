package com.transtrend.ai.assistant.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class IndexStoreTest {

    private fun newStore() = IndexStore(Files.createTempDirectory("code-assistant-index-test"))

    private fun meta(model: String = "test-embed", dims: Int = 4) = IndexMeta(
        schemaVersion = IndexStore.SCHEMA_VERSION,
        embeddingModel = model,
        dims = dims,
        files = listOf(
            FileEntry("a/File1.kt", "hash1", listOf(ChunkMeta(1, 10), ChunkMeta(8, 20))),
            FileEntry("b/File2.kt", "hash2", listOf(ChunkMeta(1, 5))),
        ),
    )

    private fun vectors() = listOf(
        floatArrayOf(1f, 0f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f),
    )

    @Test
    fun `save then load round-trips meta and vectors`() {
        val store = newStore()
        store.save(meta(), vectors())
        val loaded = store.load()
        assertNotNull(loaded)
        loaded!!
        assertEquals(2, loaded.meta.files.size)
        assertEquals(3, loaded.vectors.size)
        assertEquals(1f, loaded.vectors[0][0], 0f)
        assertEquals(1f, loaded.vectors[1][1], 0f)
        assertEquals("hash1", loaded.meta.files[0].contentHash)
        assertEquals(20, loaded.meta.files[0].chunks[1].endLine)
    }

    @Test
    fun `load returns null when model differs`() {
        val store = newStore()
        store.save(meta(model = "nomic-embed-text"), vectors())
        assertNull(store.load(expectedModel = "mxbai-embed-large"))
        assertNotNull(store.load(expectedModel = "nomic-embed-text"))
    }

    @Test
    fun `load returns null for missing or deleted store`() {
        val store = newStore()
        assertNull(store.load())
        store.save(meta(), vectors())
        store.delete()
        assertNull(store.load())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `save rejects mismatched vector count`() {
        newStore().save(meta(), vectors().dropLast(1))
    }
}
