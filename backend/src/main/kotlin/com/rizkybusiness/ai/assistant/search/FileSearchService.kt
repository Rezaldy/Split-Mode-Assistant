package com.rizkybusiness.ai.assistant.search

import com.rizkybusiness.ai.assistant.FileRefDto
import com.rizkybusiness.ai.assistant.FileSearchApi
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull

/**
 * Backs the `@` popup at keystroke frequency: the project file list is cached in memory
 * and invalidated wholesale on any VFS change (rebuilding lazily costs one short read
 * action; keeping it simple beats tracking individual events).
 */
@Service(Service.Level.PROJECT)
class FileSearchService(private val project: Project) : Disposable {

    companion object {
        const val MAX_RESULTS = 20

        fun getInstance(project: Project): FileSearchService =
            project.getService(FileSearchService::class.java)
    }

    @Volatile
    private var cachedFiles: List<FileRefDto>? = null

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    cachedFiles = null
                }
            },
        )
    }

    fun search(query: String, limit: Int): List<FileRefDto> {
        if (query.isBlank()) return emptyList()
        val files = cachedFiles ?: buildFileList().also { cachedFiles = it }
        val q = query.lowercase()
        return files.asSequence()
            .mapNotNull { ref -> score(ref, q)?.let { score -> score to ref } }
            .sortedByDescending { it.first }
            .take(limit.coerceIn(1, MAX_RESULTS))
            .map { it.second }
            .toList()
    }

    /** File-name matches beat path matches; shorter names beat longer at equal match kind. */
    private fun score(ref: FileRefDto, q: String): Int? {
        val name = ref.fileName.lowercase()
        return when {
            name.startsWith(q) -> 3_000 - name.length
            q in name -> 2_000 - name.length
            isSubsequence(q, name) -> 1_000 - name.length
            q in ref.presentablePath.lowercase() -> 500 - ref.presentablePath.length
            else -> null
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var i = 0
        for (c in haystack) {
            if (i < needle.length && needle[i] == c) i++
        }
        return i == needle.length
    }

    private fun buildFileList(): List<FileRefDto> = runReadAction {
        val result = mutableListOf<FileRefDto>()
        val basePath = project.basePath
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && !file.fileType.isBinary) {
                val presentable = basePath
                    ?.let { base -> file.path.removePrefix(base).trimStart('/') }
                    ?: file.path
                result += FileRefDto(path = file.path, presentablePath = presentable, fileName = file.name)
            }
            true
        }
        result
    }

    override fun dispose() {}
}

class BackendFileSearchApi : FileSearchApi {
    override suspend fun search(projectId: ProjectId, query: String, limit: Int): List<FileRefDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return FileSearchService.getInstance(backendProject).search(query, limit)
    }
}
