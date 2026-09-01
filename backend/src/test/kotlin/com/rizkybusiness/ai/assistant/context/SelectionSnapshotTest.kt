package com.rizkybusiness.ai.assistant.context

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionSnapshotTest {

    @Test
    fun `multi-line selection shows a line range`() {
        val snapshot = ProjectContextCollector.SelectionSnapshot(
            path = "/project/src/Foo.kt",
            fileName = "Foo.kt",
            startLine = 12,
            endLine = 40,
            text = "fun foo() {}",
        )
        assertEquals("Foo.kt:12-40", snapshot.presentableName)
    }

    @Test
    fun `single-line selection shows one line number`() {
        val snapshot = ProjectContextCollector.SelectionSnapshot(
            path = "/project/src/Foo.kt",
            fileName = "Foo.kt",
            startLine = 7,
            endLine = 7,
            text = "val x = 1",
        )
        assertEquals("Foo.kt:7", snapshot.presentableName)
    }
}
