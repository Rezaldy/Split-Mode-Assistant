package com.rizkybusiness.ai.assistant.commit

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.rizkybusiness.ai.assistant.models.BackendModelsService
import com.rizkybusiness.ai.assistant.ollama.OllamaChatMessage
import com.rizkybusiness.ai.assistant.ollama.OllamaClientService
import com.rizkybusiness.ai.assistant.settings.AssistantSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringWriter
import java.nio.file.Paths

/**
 * Generates a commit message from a set of changes and streams it into the commit UI's
 * message field. Everything runs host-side: VCS state, diff building, and the model call.
 */
@Service(Service.Level.PROJECT)
class CommitMessageGeneratorService(
    private val project: Project,
    private val serviceScope: CoroutineScope,
) {
    companion object {
        fun getInstance(project: Project): CommitMessageGeneratorService =
            project.getService(CommitMessageGeneratorService::class.java)

        /** Diff budget; commit diffs beyond this get truncated (the head carries the intent). */
        private const val MAX_DIFF_CHARS = 24_000
        private const val FIELD_FLUSH_INTERVAL_MS = 100L
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    private var generationJob: Job? = null

    /** Starts (or restarts) generation; a click while running cancels the previous run. */
    fun generate(changes: List<Change>, commitMessage: CommitMessageI) {
        generationJob?.cancel()
        generationJob = serviceScope.launch(Dispatchers.IO) {
            isRunning = true
            try {
                val diff = buildDiff(changes)
                if (diff.isBlank()) {
                    notify(ModularPluginBackendBundle.message("commit.generate.no.changes"), NotificationType.WARNING)
                    return@launch
                }
                streamMessage(diff, commitMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Commit message generation failed", e)
                notify(
                    ModularPluginBackendBundle.message(
                        "commit.generate.failed", e.message ?: e.javaClass.simpleName
                    ),
                    NotificationType.ERROR,
                )
            } finally {
                isRunning = false
            }
        }
    }

    /** Real unified diff of exactly the given changes — the model sees what will be committed. */
    private fun buildDiff(changes: List<Change>): String {
        if (changes.isEmpty()) return ""
        val basePath = project.basePath ?: return ""
        val patches = IdeaTextPatchBuilder.buildPatch(
            project, changes, Paths.get(basePath), /* reversePatch = */ false, /* honorExcludedFromCommit = */ true,
        )
        val writer = StringWriter()
        UnifiedDiffWriter.write(project, patches, writer, "\n", null)
        val diff = writer.toString()
        return if (diff.length <= MAX_DIFF_CHARS) diff else diff.take(MAX_DIFF_CHARS) + "\n[diff truncated]"
    }

    private suspend fun streamMessage(diff: String, commitMessage: CommitMessageI) {
        val model = BackendModelsService.getInstance().resolveChatModel()
        val request = listOf(
            OllamaChatMessage("system", AssistantSettings.getInstance().effectiveCommitSystemPrompt),
            OllamaChatMessage("user", diff),
        )
        val text = StringBuilder()
        var lastFlush = 0L
        // requestThinking stays off: reasoning tokens must never land in a commit message,
        // and the subject-line task doesn't need them.
        OllamaClientService.getInstance().client()
            .chatStream(model, request, contextTokens = AssistantSettings.getInstance().contextTokens)
            .collect { token ->
                if (token.isThinking) return@collect
                text.append(token.text)
                val now = System.currentTimeMillis()
                if (now - lastFlush >= FIELD_FLUSH_INTERVAL_MS) {
                    lastFlush = now
                    setField(commitMessage, text.toString())
                }
            }
        setField(commitMessage, text.toString().trim())
    }

    private suspend fun setField(commitMessage: CommitMessageI, text: String) {
        withContext(Dispatchers.EDT) {
            commitMessage.setCommitMessage(text)
        }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Split Mode Assistant")
            .createNotification(content, type)
            .notify(project)
    }
}
