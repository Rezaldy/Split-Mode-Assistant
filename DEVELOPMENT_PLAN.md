# Development plan — Assistant (TT-AI-Assistant)

Living document. Updated via PR whenever scope, order, or status changes.
Authoritative rules live in [CLAUDE.md](CLAUDE.md) (architecture, boundary
rule, conventions, PR-only workflow); this file plans the *execution*: what
happens in which order, in which PR, verified how, and what could go wrong.

## Status snapshot (2026-08-01)

**Done:** repo live at `Rezaldy/TT-AI-Assistant`; CLAUDE.md + BOOTSTRAP.md;
Claude tooling (agents: boundary-guard, multi-ide-auditor, gradle-doctor;
skills: boundary-check, new-rpc, milestone-done, ollama-smoke, code-recon;
seeded codemap); PR-only workflow documented (PR #1). **No plugin code yet.**

**Environment (verified):**

| Item | Status |
|---|---|
| IntelliJ IDEA | 2025.2.3 ✅ |
| git / gh | 2.45.1, gh authed as Rezaldy ✅ |
| Ollama | 0.32.5 serving; `qwen3.5:27b-q4_k_m`, `qwen3.5:4b` pulled ✅ |
| JDK | 25 (daemon) + Temurin 21.0.12 (platform toolchain; installed 2026-08-01 after toolchain rejected 25) ✅ |

## Phase P0 — pre-flight (before any plugin code)

Branch: `chore/p0-preflight` unless noted. Small PRs, one concern each.

1. **JDK check (revised 2026-08-01).** The template ships Gradle 9.4.0,
   which runs on JDK 25 — so try the installed JDK 25 at the first build
   (M0 checkpoint). Only if the IntelliJ Platform toolchain rejects it:
   `winget install EclipseAdoptium.Temurin.21.JDK` and pin via daemon
   toolchain config, never via the versioned `gradle.properties`.
2. **`.gitattributes` before the template lands.** `* text=auto` plus
   explicit `*.sh text eol=lf`, `gradlew text eol=lf`, `*.bat text eol=crlf`.
   The CRLF warnings we already see become real breakage when the Gradle
   wrapper script gets checked out with CRLF on a Linux host later.
3. **Template import (adapted BOOTSTRAP §2 — repo already exists).** On
   branch `m0-template-import`: clone
   `JetBrains/intellij-platform-modular-plugin-template` to a temp dir, copy
   everything except `.git` into repo root, resolve collisions (template
   README vs ours — keep both, template's becomes `TEMPLATE_README.md` or is
   dropped). One large import PR; reviewed for *completeness*, not
   line-by-line.
4. **Verify CLAUDE.md's template assumptions and bank them in the codemap.**
   *Largely done 2026-08-01 by remote exploration of the template (HEAD
   `624df076`)*: modules confirmed; Kotlin 2.3.20, `rpc` plugin
   2.3.20-RC2-0.1, IJPGP 2.16.0, platform 2026.1, Gradle 9.4.0; demo
   responder IS `BackendChatRepositoryModel`; single `@Rpc` interface
   `ChatRepositoryRpcApi` registered via `BackendRpcApiProvider`
   (`platform.rpc.backend.remoteApiProvider` EP). Known doc drift to fix in
   the M0 PR: backend XML descriptor does NOT list
   `intellij.platform.rpc.backend` (Gradle bundledModule only); root
   plugin.xml has no `<depends>` (we add the platform one at rename);
   verifier config is IU-only until M5. Codemap banking happens at import,
   from the real files.

## Milestones

Each milestone = one branch (`m<N>-<slug>`), one PR, one focused session.
Every PR runs the gate: `/milestone-done` (build → runIde → **split mode** →
`verifyPlugin` from M5 → buildPlugin) + `/boundary-check`, results listed in
the PR description. Regressions in earlier milestones block merge.

### M0 — template runs (`m0-template-import`, continues P0.3)
- Rename identity (decided 2026-08-01): plugin id **`code-assistant`**,
  group **`com.transtrend.ai`** — apply in `gradle.properties`
  (group/name/version) and `plugin.xml` (`<id>`, `<name>`, `<vendor>`);
  confirm only `<depends>com.intellij.modules.platform</depends>`.
- **Exit:** demo chat echoes in monolithic `runIde` AND the split-mode run
  config. Both verified by hand.
- Risk: first Gradle resolution of the `rpc` plugin (known trap #2) — if the
  build fights back, that's gradle-doctor's job, not trial-and-error.

### M1 — streamed Ollama chat (`m1-ollama-chat`)
- `OllamaClient` (JDK HttpClient, NDJSON → `Flow<String>`, ≥10s connect
  timeout, **no** request timeout), minimal `ProjectContextCollector` (open
  files only), wired into the template's backend responder in place of the
  demo reply. **No hardcoded model names anywhere** (decision 2026-08-01):
  the backend discovers models from `/api/tags` and uses the first
  available; `OLLAMA_MODEL` env override wins. For fast dev loops set
  `OLLAMA_MODEL=qwen3.5:4b` — the 27b model is for quality checks. M2 then
  adds the user-facing dropdown + persisted selection on top of the same
  discovery call.
- Frontend and RPC surface untouched this milestone.
- **Exit:** tokens stream incrementally in split mode; killing `ollama
  serve` mid-stream produces a distinct error bubble; `/ollama-smoke` is the
  first debugging step for any "chat is broken" report.

### M2 — model selection (`m2-model-selection`)
- `ModelsApi` in shared (follow `/new-rpc`), backend impl over `/api/tags`,
  dropdown in the tool window header, selection persisted, visible error
  state when the source is unreachable.
- **Exit:** selection survives IDE restart; unreachable source degrades
  gracefully; M1 streaming still works.

### M3 — settings (`m3-settings`)
- `AssistantSettings` (`PersistentStateComponent`, backend-registered), base
  URL in a Settings panel, `OLLAMA_BASE_URL` / `OLLAMA_MODEL` env overrides
  win over stored state.
- **Exit:** in split mode the panel appears under "Settings on Host" — this
  is *correct*, verify it rather than "fixing" it; env overrides proven.

### M4 — @ mentions (`m4-mentions`)
- `FileSearchApi` + `FileSearchService` (cached file list, VFS-change
  invalidation, project scope only, ≤20 results), debounced `@` popup,
  mentions as structured attachments, referenced files prioritized in the
  24k-char context budget, size caps + binary rejection.
- Most complex milestone: the only one adding a new RPC interface, frontend
  UI, and backend indexing at once. If it sprawls, split PRs: backend
  search + API first, popup UI second.
- **Exit:** popup responsive at keystroke frequency; attachments verified
  structurally (not re-parsed from the message string); oversized/binary
  degrade to a note.

### M5 — multi-IDE hardening (`m5-multi-ide`)
- `pluginVerification` lists IDEA (IU/IC), PyCharm (PY/PC), WebStorm (WS);
  fix findings; static sweep by multi-ide-auditor; manual smoke test of the
  `buildPlugin` zip in at least PyCharm.
- **Exit:** `verifyPlugin` clean ×3; PyCharm smoke test done by user.

### M6 — polish (`m6-polish`)
- Cancel button that aborts backend stream consumption (not just UI), chat
  history within session, context-size indicator.
- **Exit:** cancel verified backend-side; full regression pass of M1–M5.

### M7 — opt-in project indexing, local RAG (three PRs; planned 2026-08-17)

Goal: answer questions about ANY project file, not just open ones. Opt-in
setting → backend chunks project text files, embeds them via the local
Ollama endpoint (`POST /api/embed`), stores vectors on the host; at question
time the question is embedded and top-k chunks are retrieved into the
context budget. Frontend never sees vectors or file contents. Honest scope:
retrieval-quality-bound, not omniscience.

**User prerequisite:** an embedding model — `ollama pull nomic-embed-text`
(none pulled as of 2026-08-17; `OLLAMA_EMBED_MODEL` env override supported,
else auto-pick by name heuristic from `/api/tags`).

Key decisions (full rationale in the approved plan, 2026-08-17):
- Chunking: line-based, ≤2,000 chars, ~8-line overlap, path+line metadata;
  pure-Kotlin chunker (unit-testable, no PSI — multi-IDE rule).
- Storage: `PathManager.getSystemPath()/code-assistant-index/<locationHash>/`
  (never `.idea/`): `meta.json` (kotlinx.serialization; schema/model/dims +
  per-file content hash) + `vectors.bin` (raw float32 via JDK NIO).
  Normalized at write → cosine = dot product. Model/dims mismatch → rebuild.
- Build: `ProjectIndexService` (@Service PROJECT, injected scope);
  `ProjectFileIndex.iterateContent`; many short read actions; sequential
  embed batches of 16 (chat stays responsive); flush every ~500 chunks.
  Hard caps 4k files / 25k chunks / 512 KB per file — cap hits VISIBLE in
  status, never silent.
- Incremental: `BulkFileListener` → dirty set → 3s debounce → re-embed only
  hash-changed files; renames/deletes handled; per-file arrays, O(1) delete.
- Retrieval: `collect()` becomes `suspend collect(question, budget)`;
  brute-force top-12, score floor 0.30, dedupe vs open/mentioned files.
  Budget: mentions first (M4 rule) → remainder 60% open files / 40%
  retrieved, unused share spills over. Index off/no hits → today's behavior.
- Settings (extends M3): `indexingEnabled=false`, `embeddingModel` combo,
  Rebuild button, live status; `IndexApi` RPC for status only. Index errors
  never block chat — degrade to open-files context + notification.

PR sequence: **`m7-index-core`** (embed() + Chunker + IndexStore + build
pipeline, feature dark, unit tests) → **`m7-index-settings-incremental`**
(settings group, IndexApi, VFS incremental, notifications) →
**`m7-retrieval`** (budget split + retrieval + context-bar source tag).
- **Exit:** in split mode with all editors closed, a question about an
  un-opened file is answered from its actual content; disabling indexing
  reverts context to pre-M7 behavior; M4 mention priority regression-free.

## Session recipe (every coding session)

1. `git checkout main && git pull`, branch.
2. Check `.claude/codemap.md` before exploring; bank new findings after.
3. Implement. `/boundary-check` after touching frontend/ or shared/.
4. Gate with `/milestone-done`. 5. PR via `gh pr create`, verification
   listed. 6. User reviews and merges; next session starts from merged main.

## Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| JDK 25 rejected by platform toolchain | Blocks M0 | Gradle 9.4 itself is fine on 25; fallback = Temurin 21 via winget + daemon toolchain pin (P0.1) |
| Template drifted from CLAUDE.md assumptions | Wasted session flailing | P0.4 verification pass; docs fixed same PR |
| `rpc` plugin resolution fails | Blocks M0 | Known trap #2; gradle-doctor; keep jetbrains.team repo in settings |
| Split-mode run config broken on Windows | Can't verify the key property | Surface early in M0, not at M1; if broken, fix before any feature work |
| 27b model too slow for dev loops | Slow iteration, misdiagnosed "hangs" | `OLLAMA_MODEL=qwen3.5:4b` during dev; cold-start patience built into OllamaClient (no request timeout) |
| CRLF corrupts `gradlew` | Weird failures on Linux hosts later | P0.2 `.gitattributes` before import |
| M4 scope sprawl | Giant unreviewable PR | Pre-authorized split: backend-first, UI-second |

## Decisions log

- **2026-08-01 — Plugin identity:** id `code-assistant`, group
  `com.transtrend.ai`. (Vendor display name for `plugin.xml` assumed
  "Transtrend" — flag at M0 rename if wrong.)
- **2026-08-01 — No hardcoded models:** model options always come from the
  Ollama endpoint (`/api/tags`). M1 auto-picks the first available (env
  override wins); M2 adds user selection. No model name appears in code.
- **2026-08-01 — PR-only workflow** (PR #1).
- **2026-08-01 — Template README dropped at import** (approved in the
  P0→M1 execution plan): its RPC-flow walkthrough gets banked into
  `.claude/codemap.md`; a project README comes later.

## Open decisions (user input wanted)

*None currently.*

## Tracker

- [x] Repo + docs + Claude tooling
- [x] PR-only workflow (PR #1)
- [x] P0.1 JDK (Temurin 21 installed — toolchain rejected 25) ·
  [x] P0.2 .gitattributes · [x] P0.3 template import (@ 624df076) ·
  [x] P0.4 assumption verification + codemap banked
- [x] M0 (PR #5 — both run modes verified 2026-08-01) ·
  [x] M1 (PR #6 — streaming verified incl. split mode, 2026-08-02) ·
  [x] M2 (model dropdown + persisted selection + error bubble; split-mode
  manual check pending) ·
  [x] M3 (settings panel, backend-registered; split-mode manual check
  pending) ·
  [ ] M4 · [ ] M5 · [ ] M6 · [ ] M7 (indexing/RAG —
  planned, blocked on M3+M4)
