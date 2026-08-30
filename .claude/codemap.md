# Code map — verified facts, cite before re-exploring

Entry format: **Topic** — fact. `path/File.kt` `symbolName` (verified YYYY-MM-DD)
Rules: anchor to paths + symbols (never bare line numbers), date-stamp entries,
delete wrong entries on sight, keep the whole file under ~120 lines.
Maintained by the code-recon skill.

## Architecture anchors

- **Demo/backend responder** — `@Service(PROJECT)` holding
  `MutableStateFlow<List<ChatMessage>>`; `simulateAIResponse()` posts a
  thinking message then a canned reply from
  `repository/AIResponseGenerator.kt`. This is what M1 replaces.
  `backend/src/main/kotlin/com/transtrend/ai/assistant/BackendChatRepositoryModel.kt`
  (verified 2026-08-01)
- **RPC contract** — single `@Rpc` interface, `getMessagesFlow(projectId):
  Flow<List<ChatMessageDto>>` + `sendMessage(projectId, text)`; resolves
  itself via `companion.getInstance()` →
  `RemoteApiProviderService.resolve(remoteApiDescriptor<...>())`.
  `shared/src/main/kotlin/com/transtrend/ai/assistant/ChatRepositoryRpcApi.kt`
  (verified 2026-08-01)
- **Backend RPC registration** — `BackendRpcApiProvider : RemoteApiProvider`
  registered via EP `platform.rpc.backend.remoteApiProvider` in
  `backend/src/main/resources/code-assistant.backend.xml`. New RPC
  *methods* need nothing extra; new *interfaces* need a `remoteApi{}` line
  here + descriptor stays. (verified 2026-08-01)
- **Models + settings (M2)** — `ModelsApi` (app-scoped, no projectId) in
  shared; backend `BackendModelsService` (@Service APP + scope) owns
  discovery/selection state, `AssistantSettings` (@Service APP,
  PersistentStateComponent, `splitModeAssistant.xml`) persists; selection
  precedence env > stored > first tag lives in `resolveChatModel()`.
  Clients come from `OllamaClientService` (URL-keyed cache). Frontend:
  `FrontendModelsModel` (APP) → `ChatViewModel.modelsStateFlow` → combo in
  `ChatHeader.updateModels`. (verified 2026-08-17)
- **Project index / RAG core (M7 PR1)** — `backend/.../index/`: `Chunker`
  (pure, line-based, tested), `IndexStore` (meta.json + vectors.bin under
  `PathManager.getSystemPath()/code-assistant-index/<locationHash>`,
  model/dims mismatch → null → rebuild), `ProjectIndexService`
  (@Service PROJECT + scope; caps 4k files/25k chunks visible in status;
  hash-skip reuse; sequential embed batches of 16; flush per 500 chunks).
  Feature dark until PR2 (settings/IndexApi) + PR3 (retrieval).
  `OllamaClient.embed()` via `/api/embed`; embedding model resolution in
  `BackendModelsService.resolveEmbeddingModel` (env OLLAMA_EMBED_MODEL >
  name heuristic). (verified 2026-08-17)
- **@ mentions (M4)** — `FileSearchApi` in shared; backend
  `search/FileSearchService` (@Service PROJECT, whole-list cache nuked by
  any VFS change, name-beats-path scoring). Attachments travel as
  `sendMessage(projectId, text, attachments: List<String>)` — full paths,
  never re-parsed from text. Mention tokens+popup live in
  `PromptInput` (`currentMentionQuery`/`insertMention`/`currentMentionPaths`);
  debounce (250ms) is in `ChatViewModel.onMentionQuery`. Collector gives
  mentions budget priority (`collect(mentionPaths, budget)`).
  (verified 2026-08-17)
- **Frontend remote-API acquisition** — NOT in the tool window:
  `FrontendChatRepositoryModel` (`@Service(PROJECT)`) wraps calls in
  `fleet.rpc.client.durable { }` and exposes a `StateFlow` via `stateIn`.
  `frontend/src/main/kotlin/com/transtrend/ai/assistant/chatApp/viewmodel/FrontendChatRepositoryModel.kt`
  (verified 2026-08-01)
- **Tool window** — `ModularPluginToolWindowFactory` (frontend), declared in
  `code-assistant.frontend.xml` with id "Code Assistant"; builds
  `ChatViewModel(CoroutineScopeHolder.scope, FrontendChatRepositoryModel)`.
  (verified 2026-08-01)
- **Content module naming** — module names derive from
  `rootProject.name` (`code-assistant`) + subproject: descriptor files
  `code-assistant.{shared,frontend,backend}.xml` must match plugin.xml
  `<content>` entries. Rename all together or loading breaks. (verified 2026-08-01)

## Flows

- **Send message (end to end)** — frontend `PromptInput` → `ChatViewModel`
  → `ChatRepositoryApi` (local iface) → `FrontendChatRepositoryModel`
  → RPC `ChatRepositoryRpcApi.sendMessage(projectId, text)` →
  `BackendChatRepositoryRpcApi` (resolves `projectId.findProjectOrNull()`)
  → `BackendChatRepositoryModel`; replies travel back solely as new
  emissions of `getMessagesFlow`. UI is Swing (template deliberately
  removed Compose). (verified 2026-08-01)

## Gotchas

- **ChatList renders by message id** — bubbles are created for new ids and
  removed for vanished ids; content changes to an EXISTING id must go
  through `MessageBubble.updateFrom` (added for M1 streaming — the template
  never updated messages in place). Symptom when broken: streamed reply
  freezes at its first few tokens.
  `frontend/.../chatApp/ui/ChatList.kt` `addNewMessages` (verified 2026-08-02)

- **Platform toolchain requires JDK 21** — Gradle 9.4 daemon runs on JDK 25
  but compilation wants languageVersion=21 and no toolchain download repo is
  configured; Temurin 21.0.12 installed 2026-08-01 (auto-detected — no
  config needed). (verified 2026-08-01)
- **`intellij.platform.rpc.backend`** is a Gradle `bundledModule` in
  `backend/build.gradle.kts` only — it must NOT appear in the backend XML
  descriptor. (verified 2026-08-01)
- **`shared/build.gradle.kts` is intentionally empty** — plugins come from
  the root `subprojects{}` block; serialization compileOnly artifacts are
  declared only in frontend. Config cache is ON. (verified 2026-08-01)
