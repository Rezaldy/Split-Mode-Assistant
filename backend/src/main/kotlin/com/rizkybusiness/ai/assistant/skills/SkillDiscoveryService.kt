package com.rizkybusiness.ai.assistant.skills

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.rizkybusiness.ai.assistant.SkillDto
import com.rizkybusiness.ai.assistant.SkillsStateDto
import com.rizkybusiness.ai.assistant.settings.AssistantSettings
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Finds Agent Skills on the host and keeps a catalog of them.
 *
 * Backend-only by design: the skill folders (project and the host user's home) exist where
 * the project files are; in split mode the client only ever sees [SkillDto]s. Precedence is
 * project > user, first root wins within a scope, and shadowed copies are reported as a
 * warning on the winner rather than silently ignored. Project roots are skipped until the
 * project is trusted — a freshly cloned repository must not inject instructions on its own.
 *
 * Bodies are re-read from disk at activation time ([readBody]) so edits to a SKILL.md take
 * effect on the next message without a rescan.
 */
@OptIn(FlowPreview::class)
@Service(Service.Level.PROJECT)
class SkillDiscoveryService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    companion object {
        fun getInstance(project: Project): SkillDiscoveryService =
            project.getService(SkillDiscoveryService::class.java)

        const val SCOPE_PROJECT = "project"
        const val SCOPE_USER = "user"
        const val MANIFEST_FILE = "SKILL.md"
        const val MAX_MANIFEST_BYTES = 64L * 1024
        /** The spec recommends < 5k tokens per skill; this is the hard cap per injected body. */
        const val MAX_SKILL_BODY_CHARS = 8_000
        const val VFS_DEBOUNCE_MS = 500L
        private val IGNORED_DIRS = setOf("node_modules")
    }

    /** A skill known to the catalog. [location] is its SKILL.md; the body is not kept in memory. */
    data class DiscoveredSkill(
        val manifest: SkillManifest,
        val scope: String,
        val location: Path,
        val uploaded: Boolean,
        val warnings: List<SkillWarning>,
    ) {
        val name: String get() = manifest.name
        val description: String get() = manifest.description
    }

    /** Body of an activated skill, capped at [MAX_SKILL_BODY_CHARS]. */
    data class SkillBody(val name: String, val text: String, val truncated: Boolean)

    private val _skills = MutableStateFlow<List<DiscoveredSkill>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)

    /** Bumped when enable/disable settings change so [stateDtoFlow] re-emits without a rescan. */
    private val settingsTick = MutableStateFlow(0)
    private val rescanTickle = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val scanMutex = Mutex()

    @Volatile
    private var scannedOnce = false

    private val projectRootPaths: List<String> by lazy {
        SkillLocations.projectRoots(project).map { FileUtil.toSystemIndependentName(it.toString()) }
    }

    init {
        scope.launch {
            rescanTickle.debounce(VFS_DEBOUNCE_MS).collect {
                runCatching { scanNow() }.onFailure { e ->
                    if (e is CancellationException) throw e
                    thisLogger().warn("Skill rescan failed", e)
                }
            }
        }
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { event -> isUnderProjectSkillRoot(event.path) }) rescanTickle.tryEmit(Unit)
            }
        })
        connection.subscribe(TrustedProjectsListener.TOPIC, object : TrustedProjectsListener {
            override fun onProjectTrusted(project: Project) {
                if (project == this@SkillDiscoveryService.project) rescanTickle.tryEmit(Unit)
            }
        })
    }

    /** Catalog as plain DTOs for the settings page and the client. Triggers the first scan lazily. */
    fun stateDtoFlow(): Flow<SkillsStateDto> =
        combine(_skills, _error, settingsTick) { skills, error, _ ->
            val settings = AssistantSettings.getInstance()
            SkillsStateDto(skills = skills.map { it.toDto(settings) }, error = error)
        }.onStart { ensureScanned() }

    /**
     * Last scan result without suspending, for Swing code that polls (settings page). Empty
     * until the first scan; pair with [refreshAsync] to trigger one.
     */
    fun snapshot(): SkillsStateDto {
        val settings = AssistantSettings.getInstance()
        return SkillsStateDto(skills = _skills.value.map { it.toDto(settings) }, error = _error.value)
    }

    /** Current catalog snapshot (scanning first if needed); enabled skills only. */
    suspend fun enabledSkills(): List<DiscoveredSkill> {
        ensureScanned()
        val settings = AssistantSettings.getInstance()
        return _skills.value.filter { settings.isSkillEnabled(it.name) }
    }

    /** Rescans every root now; suspends until the catalog is updated. */
    suspend fun refreshNow() = scanNow()

    /** Rescans in the background (settings page Refresh, VFS-independent triggers). */
    fun refreshAsync() {
        rescanTickle.tryEmit(Unit)
    }

    /** Call after enable/disable settings were applied. */
    fun notifySettingsChanged() {
        settingsTick.value++
    }

    /**
     * The instructions of an enabled skill, read fresh from disk, or null when the name is
     * unknown, disabled, or its SKILL.md became unreadable/invalid since discovery.
     */
    suspend fun readBody(name: String): SkillBody? {
        ensureScanned()
        val skill = _skills.value.firstOrNull { it.name == name } ?: return null
        if (!AssistantSettings.getInstance().isSkillEnabled(name)) return null
        return withContext(Dispatchers.IO) {
            val text = try {
                Files.readString(skill.location)
            } catch (e: Exception) {
                thisLogger().warn("Cannot read ${skill.location}", e)
                return@withContext null
            }
            when (val parsed = SkillManifestParser.parse(text, skill.location.parent?.name)) {
                is SkillParseResult.Ok -> {
                    val body = parsed.manifest.body
                    val truncated = body.length > MAX_SKILL_BODY_CHARS
                    SkillBody(name, if (truncated) body.take(MAX_SKILL_BODY_CHARS) else body, truncated)
                }
                is SkillParseResult.Skipped -> {
                    thisLogger().warn("Skill $name at ${skill.location} is no longer valid: ${parsed.reason}")
                    null
                }
            }
        }
    }

    private suspend fun ensureScanned() {
        if (!scannedOnce) scanNow()
    }

    private suspend fun scanNow() = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val trusted = TrustedProjects.isProjectTrusted(project)
            val roots = buildList {
                if (trusted) SkillLocations.projectRoots(project).forEach { add(it to SCOPE_PROJECT) }
                SkillLocations.userRoots().forEach { add(it to SCOPE_USER) }
            }
            val uploadRoot = SkillLocations.uploadRoot().toAbsolutePath().normalize()
            val found = LinkedHashMap<String, DiscoveredSkill>()
            for ((root, scopeName) in roots) {
                for (dir in listSkillDirs(root)) {
                    val uploaded = dir.toAbsolutePath().normalize().parent == uploadRoot
                    val skill = load(dir, scopeName, uploaded) ?: continue
                    val winner = found[skill.name]
                    if (winner == null) {
                        found[skill.name] = skill
                    } else {
                        found[skill.name] = winner.copy(
                            warnings = winner.warnings + SkillWarning(SkillWarning.SHADOWED, skill.location.toString())
                        )
                        thisLogger().info("Skill '${skill.name}' at ${skill.location} is shadowed by ${winner.location}")
                    }
                }
            }
            _skills.value = found.values.sortedBy { it.name }
            _error.value = if (trusted) null else ModularPluginBackendBundle.message("skills.error.untrusted")
            scannedOnce = true
        }
    }

    private fun listSkillDirs(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return try {
            Files.list(root).use { stream ->
                stream.filter { dir ->
                    Files.isDirectory(dir) &&
                        !dir.name.startsWith(".") &&
                        dir.name !in IGNORED_DIRS &&
                        Files.isRegularFile(dir.resolve(MANIFEST_FILE))
                }.sorted().toList()
            }
        } catch (e: Exception) {
            thisLogger().warn("Cannot list skills under $root", e)
            emptyList()
        }
    }

    private fun load(dir: Path, scopeName: String, uploaded: Boolean): DiscoveredSkill? {
        val manifestPath = dir.resolve(MANIFEST_FILE)
        return try {
            if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
                thisLogger().info("Skipping $manifestPath: larger than $MAX_MANIFEST_BYTES bytes")
                return null
            }
            when (val parsed = SkillManifestParser.parse(Files.readString(manifestPath), dir.name)) {
                is SkillParseResult.Ok -> DiscoveredSkill(
                    manifest = parsed.manifest,
                    scope = scopeName,
                    location = manifestPath,
                    uploaded = uploaded,
                    warnings = parsed.manifest.warnings,
                )
                is SkillParseResult.Skipped -> {
                    thisLogger().info("Skipping $manifestPath: ${parsed.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Cannot read $manifestPath", e)
            null
        }
    }

    private fun isUnderProjectSkillRoot(path: String): Boolean {
        val normalized = FileUtil.toSystemIndependentName(path)
        return projectRootPaths.any { root -> normalized.startsWith(root) }
    }

    private fun DiscoveredSkill.toDto(settings: AssistantSettings) = SkillDto(
        name = name,
        description = description,
        scope = scope,
        location = location.toString(),
        enabled = settings.isSkillEnabled(name),
        uploaded = uploaded,
        warnings = warnings.map { ModularPluginBackendBundle.message("skills.warning.${it.code}", it.arg) },
    )

    override fun dispose() = Unit
}
