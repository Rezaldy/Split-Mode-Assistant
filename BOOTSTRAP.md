# BOOTSTRAP — from zero to M0/M1 at home (no proxy)

## 1. Prerequisites

```bash
# JDK 17 or 21 on PATH, IntelliJ IDEA 2025.2+ installed
ollama pull qwen2.5-coder:7b
ollama serve &            # if not already running as a service
curl -s http://localhost:11434/api/tags   # sanity: model list appears
```

## 2. Create the project

```bash
git clone https://github.com/JetBrains/intellij-platform-modular-plugin-template.git assistant
cd assistant
rm -rf .git && git init && git add -A && git commit -m "template import"
./gradlew build           # everything resolves directly — no mirror gymnastics at home
```

Drop `CLAUDE.md` (from this folder) into the repo root and commit it.

## 3. Rename identity (M0)

- `gradle.properties`: group / name / version
- `src/main/resources/META-INF/plugin.xml`: `<id>`, `<name>`, `<vendor>`
- Ensure `<depends>com.intellij.modules.platform</depends>` and nothing language-specific

Then verify both run modes with the untouched demo chat:

```bash
./gradlew runIde                          # monolithic
# IntelliJ: run configuration "Run IDE (Split Mode)"
```

M0 is done when the demo chat echoes in **both** modes.

## 4. First Claude Code session (M1)

Suggested opening prompt once CLAUDE.md is in the repo:

> Read CLAUDE.md. Implement milestone M1: create backend/OllamaClient.kt and a
> minimal ProjectContextCollector as specified, then wire them into the
> template's backend response generation (find the class that owns the mutable
> message list and demo response logic, likely BackendChatRepositoryModel, and
> replace the demo response with a streamed Ollama chat call). Keep the RPC
> surface and frontend untouched for this milestone. Show me the diff of the
> backend responder before finalizing.

Then per milestone, one session each: M2 (ModelsApi + dropdown), M3 (settings),
M4 (@ mentions), M5 (verifyPlugin for IDEA/PyCharm/WebStorm), M6 (polish).
Keep sessions scoped to one milestone; each milestone lands as its own PR
(branch `m<N>-<slug>` → `gh pr create` → user reviews and merges).

## 5. Verification loop (run every milestone)

```bash
./gradlew build
./gradlew runIde                # quick manual check
# split-mode run config         # the check that actually counts
./gradlew verifyPlugin          # from M5 onward: IDEA + PyCharm + WebStorm
./gradlew buildPlugin           # shippable zip in build/distributions/
```

## 6. Later: bringing it back to the corporate/cluster environment

The artifact is environment-agnostic — only the *build* differs. When a build
must happen behind the proxy again, the three known adaptations are:
`useInstaller = false` on every platform dependency, `instrumentCode = false`,
and mirror upstreams for `packages.jetbrains.team` + `www.jetbrains.com/intellij-repository`.
Config for the model source is env-based (`OLLAMA_BASE_URL`), so pointing the
backend at a cluster service like `http://ollama.it:11434` is a pod-spec
change, not a code change.
