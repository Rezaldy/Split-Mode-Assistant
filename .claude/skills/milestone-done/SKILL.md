---
name: milestone-done
description: Definition-of-done gate for milestones M0–M6. Run whenever a milestone's implementation feels complete, before committing "milestone finished", before starting the next milestone, or when the user asks "is Mx done?" / "can we move on?". Executes the full verification loop (build, both run modes, verifyPlugin from M5 on, buildPlugin) plus the milestone's specific acceptance checks, and refuses to green-light with known regressions.
---

# Milestone definition-of-done

Milestones ship in order and the rule is absolute: do not start milestone
N+1 while N has known regressions. This gate makes "done" mean something.

## Universal verification loop (every milestone)

Run in order; stop and report on first failure:

```bash
./gradlew build                 # compile + tests
./gradlew runIde                # monolithic — quick manual check
# "Run IDE (Split Mode)" run configuration — the check that actually counts
./gradlew verifyPlugin          # from M5 onward
./gradlew buildPlugin           # shippable zip in build/distributions/
```

`runIde` and the split-mode config are interactive — launch them, then tell
the user exactly what to click and what they should see (see the per-
milestone checks below). Never declare a feature done from monolithic mode
alone: it hides boundary violations by design.

Also run the boundary-check skill as part of every gate.

## Per-milestone acceptance checks

**M0 — template runs.** Plugin id/name renamed everywhere
(`gradle.properties`, `plugin.xml`). Demo chat echoes in BOTH run modes.

**M1 — streamed Ollama chat.** With `ollama serve` running: a prompt streams
tokens into the UI incrementally (not one blob at the end), in split mode.
Context includes open files. Kill ollama mid-stream → a distinct error
bubble appears in the chat, not a silent stall.

**M2 — model selection.** Dropdown lists models from `/api/tags`. Selection
persists across IDE restart. Unreachable source → visible error state, and
the UI stays usable.

**M3 — settings.** Base URL editable in a Settings panel; in split mode it
appears under "Settings on Host" (that placement is correct — verify it,
don't "fix" it). `OLLAMA_BASE_URL` / `OLLAMA_MODEL` env vars override stored
state.

**M4 — @ mentions.** Typing `@` opens the file popup; fuzzy match works;
popup stays responsive while typing (backend search is cached + debounced).
Mentions travel as structured attachments — verify by checking the send path,
not by parsing the message string. Referenced file contents reach the prompt
with priority in the context budget; oversized/binary files degrade to a
note, not a crash.

**M5 — multi-IDE.** `verifyPlugin` clean for IDEA, PyCharm, WebStorm. Manual
smoke test in at least PyCharm (install the zip from `buildPlugin`).
Delegate a static sweep to the `multi-ide-auditor` agent as well.

**M6 — polish.** Cancel button actually aborts the in-flight generation
(check the backend stops consuming the stream, not just UI hiding). Chat
history survives within the session. Context-size indicator updates.

## Regression sweep

A milestone gate re-checks the previous milestones' headline behaviors — at
minimum: chat still streams, model dropdown still works, settings still
persist. New features that break old ones fail the gate.

## Report format

End with an explicit verdict: **Mx: DONE** or **Mx: NOT DONE** plus the
blocking list. Anything that needed a manual step the user hasn't confirmed
yet is reported as UNVERIFIED, not assumed passing.
