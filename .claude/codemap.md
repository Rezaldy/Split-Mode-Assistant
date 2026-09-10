# Code map — verified facts, cite before re-exploring

Entry format: **Topic** — fact. `path/File.kt` `symbolName` (verified YYYY-MM-DD)
Rules: anchor to paths + symbols (never bare line numbers), date-stamp entries,
delete wrong entries on sight, keep the whole file under ~120 lines.
Maintained by the code-recon skill.

## Architecture anchors

- **RPC contract (M6 — per-conversation)** — `ChatRepositoryRpcApi` is
  keyed by `(projectId, chatId)`: `getMessagesFlow`, `sendMessage(...,
  attachments, skills)`, `abortGeneration`, `closeChat`, plus
  project-scoped `getContextFilesFlow(projectId)`; `chatId` is an opaque
  id minted by the frontend, one per tab.
  `shared/.../ChatRepositoryRpcApi.kt` (verified 2026-09-10)
- **Token footer (M6 context-size indicator)** — per-reply usage in
  `frontend/.../chatApp/ui/MessageItem.kt` timestamp (`in · out · % of
  num_ctx`, PR #42) IS the M6 indicator by decision (2026-09-10).
  `ContextFilesBar` lists file names + source tags, no char count.
- **Backend RPC registration** — `BackendRpcApiProvider : RemoteApiProvider`
  via EP `platform.rpc.backend.remoteApiProvider` in
  `backend/src/main/resources/code-assistant.backend.xml`. New RPC
  *methods* need nothing extra; new *interfaces* need a `remoteApi{}` line. (verified 2026-08-01)
- **Models + settings (M2)** — `ModelsApi` (app-scoped) in shared; backend
  `BackendModelsService` (@Service APP) owns discovery/selection,
  `AssistantSettings` (@Service APP, PersistentStateComponent) persists;
  precedence env > stored > first tag in `resolveChatModel()`. Frontend:
  `FrontendModelsModel` → `ChatViewModel.modelsStateFlow` → combo in
  `ChatHeader.updateModels`. (verified 2026-08-17)
- **Project index / RAG core (M7 complete)** — retrieval is live.
  `context/ProjectContextCollector.kt` `collect(question, mentionPaths,
  budget)` → `retrieve()` via `index/RetrievalSelector.select` (top-12,
  score floor 0.30, dedupes vs open+mentioned); budget order mentions →
  editor selection (`SelectionSnapshot`, ≤8k chars) → remainder 60% open
  files / 40% retrieved (`RETRIEVED_BUDGET_SHARE`), unused share spills.
  `shared/.../IndexApi.kt` (`getStatusFlow`, `rebuild`) →
  `backend/.../index/BackendIndexApi.kt`; `ProjectIndexService` now
  incremental via `BulkFileListener` + 3s debounce
  (`INCREMENTAL_DEBOUNCE_MS`). Settings: `IndexingConfigurable.kt`
  (enable, embed model combo, custom embed URL, rebuild, live status).
  Frontend: `ChatHeader.updateIndexStatus(IndexStatusDto)` sync dot.
  (verified 2026-09-10)
- **@ mentions (M4)** — `FileSearchApi` in shared; backend
  `search/FileSearchService` (@Service PROJECT, cache nuked by any VFS
  change, name-beats-path scoring). Attachments travel as
  `sendMessage(..., attachments: List<String>)` — full paths, never
  re-parsed from text. Mention popup lives in `PromptInput`
  (`currentMentionQuery`/`insertMention`); debounce (250ms) is in
  `ChatViewModel.onMentionQuery`; collector gives mentions budget
  priority. (verified 2026-08-17)
- **Agent Skills backend (M8 PR2)** — `backend/.../skills/`:
  `SkillManifestParser` (pure SKILL.md frontmatter parser, no YAML lib),
  `SkillStore` (atomic write/delete under `SkillLocations.uploadRoot()`),
  `SkillDiscoveryService` (@Service PROJECT; scans
  `.code-assistant|.agents|.claude/skills` under project + host home,
  project > user precedence, VFS-debounced, gated on
  `TrustedProjects.isProjectTrusted`), `BackendSkillsApi`.
  `shared/.../SkillsApi.kt` + DTOs in `dtos.kt` (files cross RPC as
  base64, not `ByteArray`). (verified 2026-09-07)
- **Slash-command flow (M8 PR3)** — `PromptInput.currentSlashQuery`/
  `leadingSkillToken` → `ChatViewModel.onSlashQuery`/`resolveSkillToken`
  (local filter, no RPC per keystroke) → `onSendMessage(skills)` →
  `BackendChatRepositoryModel.Conversation.activatedSkills` +
  `buildSkillBlocks` (system-prompt injection, sticky per tab). (verified 2026-09-07)
- **`skills/SkillImporter` (M8 PR3)** — frontend-only, client-local
  `JFileChooser` → base64-encoded `SkillFileDto`s → `SkillsApi.uploadSkill`;
  the one sanctioned filesystem read in `frontend/` (client machine, not the project). (verified 2026-09-07)
- **Settings sub-pages (M8 PR1)** — root id
  `com.rizkybusiness.ai.assistant.settings` on
  `AssistantGeneralConfigurable`; `PromptsConfigurable`,
  `IndexingConfigurable`, `SkillsConfigurable` are children via `parentId`. (verified 2026-09-07)
- **Frontend remote-API acquisition** — NOT in the tool window: per-tab
  calls live in `ChatTabRepository`, project-wide plumbing in
  `FrontendChatRepositoryModel` (@Service PROJECT) — both wrap RPC in
  `fleet.rpc.client.durable { }`. (verified 2026-09-10)
- **Tool window** — `ModularPluginToolWindowFactory` (frontend), declared
  in `code-assistant.frontend.xml` with id "Code Assistant". (verified 2026-08-01)

## Flows

- **Send message (end to end, M6 per-tab)** — frontend `PromptInput` →
  `ChatViewModel` → `ChatRepositoryApi` (impl `ChatTabRepository`, mints
  `chatId`, owns abort) → RPC `ChatRepositoryRpcApi.sendMessage(projectId,
  chatId, text, attachments, skills)` → `BackendChatRepositoryRpcApi` →
  `BackendChatRepositoryModel.Conversation` (`MAX_HISTORY_MESSAGES = 20`;
  generation runs on a backend-owned scope so Stop survives the RPC call
  being cancelled, PRs #45/#52). Replies arrive as new `getMessagesFlow`
  emissions; Stop is `PromptInput` `SendButtonStyle.Stop`. (verified 2026-09-10)

## Gotchas

- **RD client caches the plugin BY VERSION** — in real Remote Development
  the JetBrains Client holds its own plugin copy; ship a changed RPC
  contract under an unchanged version and client/host skew can fail
  silently. Rule in CLAUDE.md: version bump in the same PR as any
  @Rpc/DTO change; uninstall from BOTH host and client lists when in doubt. (verified 2026-08-31)
- **Bubbles must wrap to the viewport width** — MessageContent had a
  FIXED wrap width; in a narrow tool window, GridBag laid bubbles outside
  the visible area → chat looked empty while header/input worked. Fix:
  `ChatList.applyAvailableWidth` feeds viewport width to
  `MessageBubble.updateAvailableWidth`. Symptom: "no bubbles but everything else fine" = check window width FIRST. (verified 2026-08-31)
- **ChatList renders by message id** — bubbles are created for new ids and
  removed for vanished ids; content changes to an EXISTING id must go
  through `MessageBubble.updateFrom`. Symptom when broken: streamed reply
  freezes at its first few tokens. `frontend/.../chatApp/ui/ChatList.kt` `addNewMessages` (verified 2026-08-02)
- **Platform toolchain requires JDK 21** — Gradle 9.4 daemon runs on JDK 25
  but compilation wants languageVersion=21; Temurin 21.0.12 auto-detected. (verified 2026-08-01)
- **`intellij.platform.rpc.backend`** is a Gradle `bundledModule` in
  `backend/build.gradle.kts` only — must NOT appear in the backend XML descriptor. (verified 2026-08-01)
- **Multi-IDE dependency check** — backend descriptor depends on
  `intellij.platform.vcs.impl` (+ Gradle `bundledModule`) for
  `commit/CommitMessageGeneratorService.kt`; core platform in
  IDEA/PyCharm/WebStorm so allowed, but any further module must be
  checked as bundled in all three first. `pluginVerification` in root
  `build.gradle.kts` targets IntelliJ IDEA Ultimate, PyCharm Professional,
  WebStorm at `intellijPlatformVersion` (M5). Tests: 7 backend classes
  (Chunker, IndexStore, RetrievalSelector, VectorMath,
  SkillManifestParser, SkillStore, SelectionSnapshot), 1 frontend
  (MarkdownBlocks). (verified 2026-09-10)
