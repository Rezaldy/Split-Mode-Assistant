---
name: new-rpc
description: Scaffold or extend an RPC method end-to-end across shared/frontend/backend, following this project's template patterns. Use whenever the frontend needs data or behavior that lives on the host — a new query, a new action, a new stream — including "add a method to ModelsApi", "the popup needs file results", or any feature where UI must reach project files, settings, or the model source. Prevents the two classic mistakes: platform objects in DTOs and ad-hoc coroutine scopes for Flows.
---

# Adding or extending an RPC method

Any time the frontend needs something from the host — project data, settings,
model traffic — it crosses via an `@Rpc` interface in `shared/`. This skill
walks the full path so nothing is half-wired.

## Step 0: Don't create a fourth interface

The RPC surface is deliberately small: `ChatApi`, `ModelsApi`,
`FileSearchApi`. New capability almost always belongs as a method on one of
these. A genuinely new interface is an architecture change — stop and discuss
with the user first.

## Step 1: shared/ — contract only

1. Add the method to the `@Rpc` interface. Suspend functions for
   request/response; return `Flow<T>` for streams (tokens, progress).
2. Define DTOs as `@Serializable` data classes of plain data only — strings,
   paths, numbers, booleans, lists, nested DTOs. Never PSI, `VirtualFile`,
   `Document`, `Project`, or any `com.intellij.*` type: those objects only
   exist on one side of the wire. Represent files as paths (plus
   presentable name where the UI needs one, like `FileRef`).
3. No logic in shared/. If you're writing an `if` statement here, it belongs
   in the backend.

Before writing anything, read how an existing interface (start with
`ChatApi`) is declared and registered — the template already implements the
whole pattern; extend it, don't reinvent it.

## Step 2: backend/ — implementation

1. Implement the method in the existing backend implementation of that
   interface (find it by the existing registration of `ChatApi`'s impl —
   follow the same registration mechanism exactly).
2. Project access rules:
   - All PSI/VFS/index reads inside `runReadAction`.
   - Project-scoped state lives in `@Service(Service.Level.PROJECT)`
     services.
   - Keystroke-frequency methods (like `FileSearchApi.search`) must be fast:
     cache, invalidate on VFS changes, cap result counts.
3. `Flow`-returning methods must run on the coroutine scopes the template
   provides for the RPC layer — never `GlobalScope` or a hand-rolled scope.
   Streams die silently in split mode when scoped wrong.
4. Errors that the user must see (connection refused, model not found,
   stream aborted) travel across RPC as data the frontend renders as an
   error bubble — never swallowed into logs only.

## Step 3: frontend/ — consumption

1. Obtain the remote proxy the same way the existing UI gets `ChatApi`.
2. Debounce keystroke-driven calls (the `@` popup pattern) before they hit
   RPC.
3. The frontend renders what it receives. If you feel the urge to
   post-process, filter, or enrich results on the frontend — that logic
   belongs on the backend; go back to Step 2.

## Step 4: verify

1. `./gradlew build` — the rpc compiler plugin regenerates stubs; stale
   stub errors usually mean shared/ changed but wasn't rebuilt.
2. Exercise the feature in monolithic `runIde`.
3. Exercise it again with the "Run IDE (Split Mode)" run configuration —
   this is the check that actually counts. Monolithic mode cannot prove an
   RPC boundary is correct.
4. Run the boundary-check skill (or the `boundary-guard` agent for large
   changes) before committing.
