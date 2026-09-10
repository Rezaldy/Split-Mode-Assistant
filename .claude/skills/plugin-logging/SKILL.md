---
name: plugin-logging
description: How to log in this plugin and how to read the logs back, so one chat generation can be reconstructed end to end. Use whenever asked to "add logging", "log this", "why is nothing in the log", "debug the stream", "the reply was cut off", "find out what happened", "enable debug logging", "trace this", or anything mentioning idea.log or the host log — and before diagnosing any streaming/RPC/split-mode problem. Also use whenever a Kotlin change adds or edits a thisLogger() call.
---

# Logging in this plugin

There is no SLF4J, no JSON logs, no MDC here — this is IntelliJ Platform, not
Spring. The analogues are `com.intellij.openapi.diagnostic.Logger`, one
category per class, and a correlation id you carry by hand in the message
text. This skill is how to write a line worth reading later and how to find
it again.

## The API

- `thisLogger()` — an extension on any instance, used inline in a method
  body. This is what the codebase uses everywhere; reach for it first.
- `logger<T>()` — the equivalent for a top-level function or a companion
  object, where there's no `this` to extend.
- Category is the fully qualified class name. Every plugin logger therefore
  lands under `com.rizkybusiness.ai.assistant`, with sub-packages `ollama`,
  `context`, `index`, `models`, `skills`, `settings`, `search`, `repository`,
  `commit`, `chatApp`, `toolWindow` giving you a natural filter — you rarely
  need to enable the whole plugin when you know which piece is misbehaving.
- Levels: `trace`, `debug`, `info`, `warn`, `error`. `info` and above are
  always written to disk; `debug`/`trace` are compiled in but silent until a
  category is switched on (see below) — cheap to leave in code, free until
  needed.
- Lazy forms avoid building a string that's about to be thrown away:
  `LOG.debug { "expensive ${toRender()}" }` and `LOG.trace { "..." }` only
  evaluate the lambda if the level is enabled. Guard genuinely expensive work
  (not just string concatenation) with `LOG.isDebugEnabled` /
  `isTraceEnabled` instead of relying on the lazy form alone.

## Picking a level

- `warn(message, throwable)` — anything the *user's environment* caused:
  connection refused, model not found, stream stalled or cut off, an index
  read that failed. Always pass the throwable, never just `e.message` —
  the stack trace is often the only way to tell "gateway timeout" from
  "server crashed" after the fact.
- `error(...)` is not "more severe warn" — in IntelliJ it triggers a fatal
  error report: a red exclamation mark in the status bar, a "Report to
  JetBrains" dialog the user sees, and in tests it fails the test outright.
  Reserve it for actual plugin bugs — invariants this code assumes and
  violates — never for something Ollama or the network did. If you're
  tempted to reach for `error` because the failure feels important, that
  urge means `warn` plus a UI-visible error, not a fatal report.
- `info` — state transitions worth knowing happened even without debug
  logging on: a generation finished (and why), a skill got shadowed, model
  discovery failed. One line per event, not one per token.
- `debug`/`trace` — the play-by-play you only want while actively chasing a
  bug: time to first token, per-chunk counters, skill injection detail.

Whatever the level, a log line is never the only place an error-worthy event
shows up. CLAUDE.md's rule stands regardless of level: connection refused,
model not found, and stream-abort all need a distinct error bubble in the
chat UI. A `warn` with no UI counterpart is a code-quality finding, not a
logging nuance — see `code-quality`'s Errors section.

## Line shape (so grep keeps working)

`<Subject> <past-tense verb or state>: key=value key=value`, with a small,
stable set of subject words: `Chat stream`, `Chat generation`, `Index
build`, `Model discovery`, `Context assembled`. Keep using the words that
are already there rather than inventing a new one per call site — that
vocabulary is what makes `references/log-recipes.md`'s greps work at all.

Log sizes and counts, never payloads. Never put in a line: prompt text, file
contents, the assembled context block, the model's reply, a full absolute
user path when a project-relative one will do, or a base URL with
credentials in it. `context=18432 chars files=3 open=2 retrieved=4` tells you
what you need; the actual 18432 characters do not belong in `idea.log`.

## The examples already in the code

Read these before adding a new line nearby — match their shape, don't
invent a new one:

- `backend/.../ollama/OllamaClient.kt` — `info` on every stream end:
  `Chat stream done: gen=<id> model='<m>' reason=<r> prompt=<n> reply=<n>
  num_ctx=<n>`. This is the line that answers "did the model stop on its
  own or hit a limit" after the fact — `reason=stop` is the model finishing
  normally, `reason=length` is `num_ctx`/`num_predict` cutting it off. Same
  file, `warn` on a stall (`no data for <s>s after <n> chunks`) and `warn`
  on EOF without `done:true` (connection cut mid-stream, not the model's
  choice).
- `backend/.../BackendChatRepositoryModel.kt` — `info`
  `Chat generation cancelled (user abort, tab closed, or backend
  shutdown)`; `warn Chat generation failed` with the throwable; `debug
  Injecting <n> skill(s) (<chars> chars): <names>`.
- `backend/.../context/ProjectContextCollector.kt` — `warn Retrieval failed;
  continuing without indexed context` — an index problem must never block
  chat, so it's a warning the retrieval path swallows on purpose, not a
  crash.
- `backend/.../models/BackendModelsService.kt` — `info Model discovery
  failed: <message>`.
- Frontend logging is deliberately rare: only
  `frontend/.../skills/SkillImporter.kt` warns on a read/upload failure. The
  frontend is thin by design (CLAUDE.md) — it logs RPC failures it can't
  surface any other way, and nothing at token frequency.

## The generation id

Every chat generation carries a short id — the first 8 chars of the `chatId`
plus a per-conversation counter, e.g. `gen=3f9a2c1e/7` — as the first key of
its started, done, stalled, ended-without-done, cancelled, and failed lines.
It is minted in `BackendChatRepositoryModel.Conversation.sendMessage` and
reaches `OllamaClient.chatStream` through its `logTag` parameter; commit-message
generations pass `gen=commit/<n>` instead. With two chat tabs open, or a host
log and a client log side by side, the id is what tells the lines apart — so
any new line on the generation path takes the id as its first key too, and
any new `chatStream` caller passes a tag rather than leaving it empty.

## Instrumenting a generation

When a streaming bug needs more than the lines above, add instrumentation at
these points and no others — anything per token turns the log into the
payload you were told to keep out:

- Start, `info`: `Chat generation started: gen=<id> model='<m>' messages=<n>
  attachments=<n> context=<chars>` — the one line that says what was asked
  without saying what was asked.
- First token, `debug`: time since start. Distinguishes "model loading"
  from "model slow" from "nothing arriving".
- Done, stalled, incomplete, cancelled, failed — already logged, each with
  the `gen=` id as its first key.
- Progress, `trace` at most: a chunk counter every N chunks, never every
  chunk.

## Turning on debug/trace to actually see something

Help | Diagnostic Tools | Debug Log Settings, one category per line, `#`
prefix — `#com.rizkybusiness.ai.assistant` for the whole plugin, or scope it
down, e.g. `#com.rizkybusiness.ai.assistant.ollama`. Append `:trace` for
trace level. Takes effect immediately in the running IDE and persists for
that IDE instance, no restart. In split mode the categories for backend
logic are set on the **host** IDE — the backend process, not the client the
user is clicking in. Frontend categories are set on the client.

Pre-setting this for the Gradle sandbox (`idea.log.debug.categories` JVM
property on the run tasks) is a build-file change — ask the main session
rather than editing `build.gradle.kts` yourself.

## Reading logs back

`run-sandbox` owns the log file locations per run mode and the list of
benign noise to ignore — don't duplicate that here, use
`references/log-recipes.md` for the actual commands (grep patterns, tailing
live, merging host+client by timestamp, stripping noise, what to paste into
a PR or issue).

## Tests

`LOG.error` fails a test outright — it is not a safe way to assert "this
code logged something bad happened". Assert on the surfaced error (thrown
exception, UI state, RPC error payload) instead of scraping log output.

## Definition of done for a logging change

- Level matches the cause: `warn` with the throwable for environment
  failures, `error` reserved for actual plugin bugs.
- No payloads in the line — prompt text, file contents, replies, full paths,
  credentials all stay out; sizes and counts go in instead.
- Anything the user needs to act on is also visible in the chat UI, not
  logged only.
- The line uses an existing subject word, or a new one that's genuinely a
  new kind of event — and carries the `gen=` id when it is on the generation
  path (see "The generation id").
- The PR description says which line to look for and in which file, per run
  mode that matters for the change.
