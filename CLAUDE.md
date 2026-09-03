# CLAUDE.md

## What this project is

A JetBrains IDE plugin — **"Split Mode Assistant"** (plugin id `code-assistant`) — that provides an AI chat tool window backed by a **user-configured model source** (Ollama-compatible HTTP API). It is **natively built for split mode** (JetBrains Remote Development): all project-aware logic and all LLM traffic live on the **backend/host**, and only UI runs on the **frontend/client**. It must also run identically in monolithic (local, non-split) mode.

Core capabilities:

1. **Custom model source**: a single configurable endpoint (default `http://localhost:11434`) speaking the Ollama REST API. No multi-provider abstraction — one provider, done well.
2. **Model discovery & selection**: the plugin lists models from the source (`GET /api/tags`) and lets the user pick one from a dropdown in the chat UI. The selection persists.
3. **Full project context on the host**: context is collected via PSI/VFS/indexes on the backend, where the project files physically live. Nothing project-related executes on the frontend.
4. **`@` file references**: typing `@` in the chat input opens a completion popup of project files (fuzzy-matched). Selected references are resolved to file contents **on the backend** at prompt-build time.
5. **Multi-IDE**: must install and work in IntelliJ IDEA, PyCharm, and WebStorm (and by extension most IntelliJ-based IDEs).

## Architecture (do not deviate without discussion)

Based on JetBrains' modular plugin template (`JetBrains/intellij-platform-modular-plugin-template`), which is itself a split-mode chat plugin — we extend it rather than reinvent it.

```
root/                 assembles the plugin, owns splitMode config
├── shared/           RPC interfaces (@Rpc), DTOs, serializers. No logic.
├── frontend/         Tool window, chat UI, model dropdown, @-completion popup.
│                     Loads in JetBrains Client. NEVER touches PSI/VFS/network-to-Ollama.
└── backend/          Everything else: Ollama HTTP client, context collector,
                      file search, settings, RPC implementations.
                      Loads on the host. Owns ALL project access and ALL LLM traffic.
```

**The boundary rule (most important rule in this file):** if code needs the project model, the filesystem, indexes, or the model endpoint → it goes in `backend/`. If code renders or handles input → `frontend/`. Data crosses only via the RPC interfaces in `shared/`. When in doubt, put logic in the backend; the frontend should be as thin as possible.

Module loadability is declared via content-module dependencies (verified against template @ 624df076):
- backend **XML descriptor** depends on `intellij.platform.backend`, `intellij.platform.kernel.backend` (+ the shared module). `intellij.platform.rpc.backend` is a **Gradle `bundledModule` only** — do not add it to the XML.
- frontend descriptor depends on `intellij.platform.frontend` (+ the shared module)
- shared uses the `rpc` Gradle plugin (JetBrains-internal Kotlin compiler plugin, resolved from `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/`) to generate RPC stubs from `@Rpc` interfaces.

### RPC surface (shared/)

Keep the surface small. Three interfaces:

- `ChatApi` — `sendMessage(text, attachments)`, plus a `Flow` of message/stream updates (the template already implements this pattern as `ChatRepositoryRpcApi` in shared/; extend it, don't replace it).
- `ModelsApi` — `listModels(): List<ModelInfo>`, `getSelectedModel()`, `selectModel(name)`.
- `FileSearchApi` — `search(query: String, limit: Int): List<FileRef>` where `FileRef = (path, presentablePath, fileName)`. Powers the `@` popup.

DTOs are `@Serializable` data classes. Never pass PSI, VFS, or any platform object across RPC — paths and plain data only, and no custom serializers (encode times as epoch millis, not `LocalDateTime`).

**Wire-contract rule (learned in real Remote Development):** any change to an `@Rpc` interface or DTO is a wire-contract change — **bump the plugin version in the same PR**. The JetBrains Client keeps its own copy of the plugin keyed by version; a host and client both claiming the same version with different contracts fails *silently* (changed methods hang or deliver nothing, identical ones keep working — no error in any log).

### Key backend components

- `OllamaClient` — JDK `java.net.http.HttpClient` + `kotlinx.serialization`. **No Ktor, no OkHttp, no new HTTP deps.** Streaming chat via `/api/chat` (NDJSON lines → `Flow<String>`); model list via `/api/tags`. No connect timeout shorter than 10s; no request timeout (streams + model cold-start can be slow).
- `ProjectContextCollector` (`@Service(Service.Level.PROJECT)`) — assembles the context block under a hard character budget (default 24k chars, configurable later): project file tree (truncated), open files, and the contents of `@`-referenced files (these get priority in the budget). All reads inside `runReadAction`.
- `FileSearchService` — backs `FileSearchApi`. Use `ProjectFileIndex`/`FilenameIndex` with simple fuzzy matching on file names first; full-path matching second. Must respect project scope (no library/external files by default) and be fast enough for keystroke-frequency calls (cache the file list, invalidate on VFS changes).
- `AssistantSettings` — `PersistentStateComponent` storing base URL and selected model. Registered on the **backend** (in split mode it appears under "Settings on Host", which is correct: the connection to the model source is made from the host). Env overrides `OLLAMA_BASE_URL` / `OLLAMA_MODEL` win over stored state (for containerized backends).

### `@` mention flow (end to end)

1. Frontend chat input detects `@` and subsequent typing → debounced RPC `FileSearchApi.search(query)`.
2. Backend fuzzy-matches project files, returns ≤ 20 `FileRef`s.
3. Frontend shows a completion popup; on selection, inserts a mention token rendered as `@fileName` but carrying the full path in the message model.
4. On send, mentions travel as structured attachments (list of paths) alongside the text — do not parse them back out of the string.
5. Backend prompt assembly: referenced files are read (read action, size-capped per file, binary files rejected with a note) and placed in the context block above the general project context.

### Multi-IDE compatibility rules

- `plugin.xml` declares `<depends>com.intellij.modules.platform</depends>` and **nothing language-specific**. Never depend on `com.intellij.modules.java` or any language plugin.
- Backend code may use only platform-level APIs: VFS, `ProjectFileIndex`, `FilenameIndex`, editors, documents. **No `com.intellij.psi.PsiJavaFile` or other language-specific PSI** — if language-aware context is wanted later, it must be optional-dependency based, not hard-required.
- Verify continuously: `pluginVerification { ides { ... } }` lists IntelliJ IDEA (IU or IC), PyCharm (PY/PC), and WebStorm (WS). Run `./gradlew verifyPlugin` before considering any milestone done.

## Build & run

```bash
./gradlew build                 # compile + test everything
./gradlew runIde                # monolithic sandbox (fast iteration)
# "Run IDE (Split Mode)" run configuration (.run/ folder) → frontend + backend as two processes
./gradlew verifyPlugin          # multi-IDE compatibility check (IDEA, PyCharm, WebStorm)
./gradlew buildPlugin           # → build/distributions/*.zip (contains both module sets)
```

Test in split mode before declaring any feature done — monolithic mode hides boundary violations (frontend code accidentally touching project APIs works locally and breaks in split mode).

Sandbox testing needs Ollama reachable from the machine running the *backend* process: `ollama serve` locally is fine for both run modes at home.

## Workflow: pull requests only

**Nothing lands on `main` directly — every change goes through a GitHub pull request.** This applies to all changes: code, docs, `.claude/` config, build scripts.

- Start every unit of work on a branch cut from up-to-date `main`. Naming: `m<N>-<slug>` for milestone work (e.g. `m1-ollama-client`), `fix/<slug>`, `docs/<slug>`.
- One coherent change per PR — a milestone, a fix, a doc update. Don't bundle unrelated changes.
- Open PRs with `gh pr create`. The description states what changed and why, which verification steps ran (build, run modes, `verifyPlugin` where applicable), and the written justification for any new external dependency.
- PRs are reviewed and merged by the user — never self-merge without explicit instruction.
- The milestone rule follows: a milestone is "done" when its PR is merged, and milestone N+1 starts from the merged state of N.

## Model tiering (who does what work)

- The **main session** (Fable/Opus-class) plans, designs RPC surfaces, reviews all delegated work, and personally handles the subtle parts: split-mode wiring, coroutine/streaming logic, Gradle/build changes, debugging, anything where monolithic-vs-split behavior could differ.
- **Sonnet is the default implementer for routine work** — delegate via the `plugin-engineer` agent when the design is decided and an existing pattern shows the way: bundle strings, DTO fields with defaults, UI components copying an existing pattern, mechanical refactors/renames, docs/tracker updates, test boilerplate. It escalates back instead of improvising on anything subtle.
- **Haiku navigates** (`codebase-navigator`); review/diagnosis agents (`boundary-guard`, `multi-ide-auditor`, `gradle-doctor`) run on Sonnet.
- Delegated changes never go straight into a PR: the main session reviews the diff (`/boundary-check` + `/code-quality`) first. Delegation saves cost, not scrutiny.

## Conventions

- **`DOCUMENTATION.md` is the definition of the application** — what it does, how context assembly and indexing work, where user data (index, settings) lives, and every user-visible feature. Any PR that changes that definition — a feature added or removed, a default changed, a storage location moved, context/priority rules adjusted — updates `DOCUMENTATION.md` in the same PR. Checking it is part of every milestone's and feature PR's definition of done; keeping it accurate is a docs task (delegable), verifying it happened is the reviewer's job.
- **`README.md` is the front door, kept deliberately thin**: what the plugin is, requirements, install, quick start, and a pointer to `DOCUMENTATION.md` for everything else. It must be updated in the same PR only when setup steps, requirements, or the headline feature list change — detail changes belong in `DOCUMENTATION.md`, never duplicated into the README.
- Kotlin, official code style. Coroutines + `Flow` for anything async; no raw threads.
- Dependencies: platform-provided only (`kotlinx.serialization`, coroutines ship with the platform). Adding any external library requires a written justification in the PR description.
- All user-visible strings in a message bundle from day one (cheap now, painful later).
- Errors from the model source must surface in the chat UI as a distinct error bubble (connection refused, model not found, stream aborted) — never swallowed into logs only.
- Backend logging via `thisLogger()`; remember: in split mode, backend logs land in the **host's** `idea.log`.

## Known traps (learned the hard way — do not rediscover)

1. `intellijPlatform { }` exists twice: as an **extension block** (splitMode, instrumentCode, pluginVerification) and as a **dependency helper block** inside `dependencies { }` (`intellijIdea(...)`, `pluginModule(...)`). Functions from one do not resolve in the other.
2. The `rpc` Gradle plugin (bare id, version coupled to the Kotlin version, e.g. `2.3.20-RC2-0.1`) exists **only** on `packages.jetbrains.team/maven/p/ij/intellij-dependencies/` — it is not on the Gradle Plugin Portal and is unrelated to `org.jetbrains.kotlinx.rpc`. The template's `settings.gradle.kts` pluginManagement block must keep that repo.
3. Synthetic coordinates (`idea:idea:*`, `localIde:*`, `bundled*`) are resolved from plugin-generated **local Ivy repositories**, never from remote Maven. If they show up in network requests, repository configuration is broken (usually a settings-level `repositoriesMode` clobbering project repos — at home, just don't enforce `PREFER_SETTINGS`).
4. Each subproject declares its own platform dependency; platform-dependency changes (version bumps etc.) must be applied to **root + shared + frontend + backend**, ideally via one shared property.
5. `~` in Gradle script paths is not expanded — use `System.getProperty("user.home")`.
6. A `Flow` collected from the RPC layer must be consumed on the backend's coroutine scopes provided by the template — don't create ad-hoc `GlobalScope` work.

## Milestones (in order; each one shippable)

1. **M0 — template runs**: clone, rename plugin id/name, both run modes show the demo chat.
2. **M1 — streamed Ollama chat**: `OllamaClient` + model discovered from the endpoint (no hardcoded model names anywhere — backend uses the first model from `/api/tags`, `OLLAMA_MODEL` env override wins) + minimal context (open files only) wired into the backend responder. Tokens stream into the UI in split mode.
3. **M2 — model selection**: `ModelsApi`, dropdown in the tool window header, persisted selection, error state when the source is unreachable.
4. **M3 — settings**: base URL + defaults in a Settings panel (backend-registered), env overrides.
5. **M4 — @ mentions**: `FileSearchApi`, completion popup, structured attachments, prioritized context budget.
6. **M5 — multi-IDE hardening**: `verifyPlugin` clean for IDEA/PyCharm/WebStorm; manual smoke test in at least PyCharm.
7. **M6 — polish**: cancel button for in-flight generations, chat history within session, context-size indicator.
8. **M7 — opt-in project indexing (local RAG)**: settings-gated; backend chunks + embeds project files via the endpoint's `/api/embed` (embedding model auto-picked from `/api/tags`, `OLLAMA_EMBED_MODEL` override), vectors stored under the IDE system path, question-time top-k retrieval merged into the context budget (mentions > open files > retrieved). Index errors must never block chat. Full design in DEVELOPMENT_PLAN.md §M7.

Do not start milestone N+1 while N has known regressions.
