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
| JDK | ⚠️ only 25 on PATH — BOOTSTRAP calls for 17/21 (see P0) |

## Phase P0 — pre-flight (before any plugin code)

Branch: `chore/p0-preflight` unless noted. Small PRs, one concern each.

1. **JDK 21.** Install Temurin 21 alongside 25 (don't remove 25). Point
   Gradle at it via `org.gradle.java.home` in the *user* `gradle.properties`
   or rely on toolchains once the template is in. Exit: `./gradlew -version`
   (post-import) reports a supported JVM.
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
4. **Verify CLAUDE.md's template assumptions and bank them in the codemap**
   (this is the code-recon skill's first real outing): actual `rpc` plugin
   version + Kotlin version, the real name/location of the demo responder
   ("likely `BackendChatRepositoryModel`" is a hypothesis), how RPC impls
   are registered, where the tool window is declared. Any doc drift → fix
   CLAUDE.md in the same PR.

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

## Session recipe (every coding session)

1. `git checkout main && git pull`, branch.
2. Check `.claude/codemap.md` before exploring; bank new findings after.
3. Implement. `/boundary-check` after touching frontend/ or shared/.
4. Gate with `/milestone-done`. 5. PR via `gh pr create`, verification
   listed. 6. User reviews and merges; next session starts from merged main.

## Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| JDK 25 breaks Gradle/instrumentation | Blocks M0 | P0.1: JDK 21 preinstalled before first build |
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

## Open decisions (user input wanted)

1. **Template README handling** in the import PR: drop it or keep as
   `TEMPLATE_README.md`.

## Tracker

- [x] Repo + docs + Claude tooling
- [x] PR-only workflow (PR #1)
- [ ] P0.1 JDK 21 · [ ] P0.2 .gitattributes · [ ] P0.3 template import ·
  [ ] P0.4 assumption verification
- [ ] M0 · [ ] M1 · [ ] M2 · [ ] M3 · [ ] M4 · [ ] M5 · [ ] M6
