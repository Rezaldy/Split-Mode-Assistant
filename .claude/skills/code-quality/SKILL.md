---
name: code-quality
description: Kotlin + IntelliJ Platform code-quality review for this plugin — coroutine/Flow discipline, EDT vs background threading, read actions, Disposable lifecycles, bundle strings, error surfacing. Use when the user says "review", "refactor", "clean up", before any milestone PR, and whenever new platform-API-touching code lands. Complements /boundary-check (architecture) — this skill is about the quality of code that is already in the right module.
---

# Code quality review — Kotlin + IntelliJ Platform

Systematic quality pass for this plugin's code. `/boundary-check` answers
"is this code in the right module?"; this skill answers "is it good code
for a JetBrains plugin?". Run both before a milestone PR.

## Review strategy

1. **Quick scan** — understand intent and scope of the change.
2. **Checklist pass** — apply the categories below to changed code.
3. **Summary** — findings by severity: Critical → Important → Smell → Good.

## Coroutines & Flow

The plugin is coroutine-first (CLAUDE.md: no raw threads). Flag:

- `GlobalScope`, `Thread(...)`, ad-hoc `CoroutineScope(...)` — everything
  runs on injected/service scopes (services take a `CoroutineScope`
  constructor param; UI uses `CoroutineScopeHolder.createScope`).
- **Swallowed `CancellationException`** — any `catch (e: Exception)` that
  doesn't rethrow `CancellationException` first breaks cancellation
  (cancel button, stream abort). This bit the template already; the
  `sendMessage` pattern in `BackendChatRepositoryModel` is the reference.
- `runBlocking` anywhere in production code.
- Flows collected without a clear owner/scope, or `StateFlow` exposed as
  mutable (`MutableStateFlow` should be private behind `asStateFlow()`).
- RPC `Flow` methods returning flows built on ad-hoc scopes (known trap
  #6 — streams die silently in split mode when scoped wrong).

## Threading: EDT vs background

- Swing/UI mutation must happen on EDT — `withContext(Dispatchers.EDT)`
  around UI updates from collectors (see `ChatAppSample`). Flag UI touches
  from plain coroutine context.
- No blocking work on EDT: network calls, document loads, embedding calls
  belong on `Dispatchers.IO` / background coroutines.
- Listeners (message bus, VFS) often fire on EDT — handlers must stay
  cheap; enqueue and process elsewhere.

## Read actions & platform access

- Every PSI/VFS/document/index read from a background thread is inside
  `runReadAction` — and read actions are **short**: never wrap a loop over
  many files or a network call in one read action; take many small ones.
- Prefer `FileDocumentManager` document text over raw VFS bytes when
  unsaved edits should count (context collection does this deliberately).
- No write actions unless truly modifying the workspace (this plugin
  mostly shouldn't).

## Lifecycles: Disposable & listeners

- `messageBus.connect(...)` always takes a parent `Disposable` (service
  implementing `Disposable`, or `toolWindow.disposable`) — a bare
  `connect()` is a leak.
- UI components registered on the tool window's disposable; view models
  cancel their scope in `dispose()` (see `ChatViewModel`).
- `HttpClient` and caches are per-service singletons, not per-call.

## Errors & user-visible behavior

- Project convention (CLAUDE.md): model-source errors surface in the chat
  UI — connection refused, model not found, stream aborted each get a
  distinct, bundle-sourced message. **Log-only error handling is a
  Critical finding.**
- Exceptions carry cause: `throw X(..., e)`, never `e.message` alone.
- Backend logging via `thisLogger()`; remember backend logs land in the
  host's `idea.log` in split mode.

## Strings & serialization

- Every user-visible string comes from a message bundle
  (`ModularPlugin*Bundle.properties`) — hardcoded UI strings are flagged
  from day one (cheap now, painful later).
- DTOs: `@Serializable` data classes, plain data only, default values for
  new fields so the wire format stays compatible (`ContextFileDto.source`
  pattern).

## Kotlin idioms

- No `!!` where a safe alternative reads clearly; no `lateinit` where a
  nullable + check is honest about lifecycle.
- Prefer `data class` + `copy` for evolving immutable state (streaming
  message updates), sealed classes for UI states (`MessageInputState`).
- Constants named in `companion object`, not magic numbers (budget sizes,
  flush intervals, caps).
- Match the surrounding code's style and comment density; comments state
  constraints code can't show, not narration.

## Output format

```markdown
## Code review: <scope>

### Critical
- **Swallowed cancellation** (Foo.kt:42) — catch(Exception) without
  rethrowing CancellationException; cancel button will stop working.

### Important
- **Long read action** (Bar.kt:88) — wraps the whole file loop; take one
  read action per file.

### Smells
- **Hardcoded string** (Baz.kt:12) — "Loading…" belongs in the bundle.

### Good
- ✅ Streaming updates throttled before hitting the RPC flow.
```

## Quick red-flag table

| Category | Red flags |
|---|---|
| Coroutines | `GlobalScope`, `runBlocking`, swallowed `CancellationException`, exposed `MutableStateFlow` |
| Threading | UI off-EDT, blocking on EDT, heavy listener handlers |
| Read actions | long RAs, network inside RA, PSI/VFS reads without RA |
| Lifecycle | `connect()` without disposable, per-call HttpClient, un-cancelled scopes |
| Errors | log-only failures, lost causes, non-bundle error text |
| Wire | non-`@Serializable` DTOs, new DTO fields without defaults |

Severity: **Critical** = breaks cancellation/split mode/UX conventions →
fix before merge. **Important** = correctness/performance risk → should
fix. **Smell** = style/maintainability. **Good** = reinforce it.
