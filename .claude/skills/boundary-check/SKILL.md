---
name: boundary-check
description: Fast split-mode boundary audit for this plugin. Run it before every commit, after touching anything in frontend/ or shared/, when moving code between modules, and as part of any milestone's definition of done. Also use when the plugin misbehaves in split mode but works in monolithic runIde — that symptom is almost always a boundary violation. Catches frontend code touching PSI/VFS/indexes/network and platform objects leaking through RPC, without needing a split-mode run.
---

# Split-mode boundary check

Monolithic `runIde` runs frontend and backend in one process, so frontend
code that illegally touches project APIs *works* — until it runs in split
mode and breaks. This check finds those violations statically, in seconds.

## Quick pass (inline, always do this much)

Run these greps and read every hit in context:

| Where | Pattern | Why it's a violation |
|---|---|---|
| `frontend/src` | `com\.intellij\.psi` | PSI exists only on the host |
| `frontend/src` | `openapi\.vfs\|VirtualFile\|VfsUtil` | filesystem is host-side |
| `frontend/src` | `ProjectFileIndex\|FilenameIndex\|FileBasedIndex` | indexes are host-side |
| `frontend/src` | `java\.net\.http\|HttpClient\|URLConnection` | all LLM traffic goes through the backend |
| `frontend/src` | `runReadAction\|ReadAction\|WriteAction` | implies project-model access |
| `shared/src` | `com\.intellij\.` | shared is DTOs + `@Rpc` interfaces only |
| `backend/src` | `GlobalScope\|Thread\(` | use the template's coroutine scopes |
| `backend/src` | `io\.ktor\|okhttp` | JDK HttpClient only, no new HTTP deps |

Then eyeball the RPC surface in `shared/`: every DTO must be a
`@Serializable` data class of plain data (strings, paths, numbers, lists).
Any field typed as a platform object — PSI, `VirtualFile`, `Document`,
`Project` — is a violation even if it compiles.

A grep hit is not automatically a violation — frontend using platform *UI*
classes is fine; frontend reaching into the project model is not. Read
before flagging.

## Deep pass (when it matters)

Before declaring a milestone done, or when the change was large, delegate a
full review to the `boundary-guard` agent instead of relying on the quick
pass — it reads hits in context across all three modules and checks
build-script dependencies too.

## Fixing a violation

The fix is always the same shape: move the logic to `backend/`, expose it as
a method on one of the three RPC interfaces (`ChatApi`, `ModelsApi`,
`FileSearchApi`) or extend one — don't invent a fourth interface without
discussion — and pass plain data across. When in doubt, put logic in the
backend; the frontend should be as thin as possible.

## Remember

A clean static check still doesn't replace an actual split-mode run
("Run IDE (Split Mode)" run configuration) before a feature is declared
done — this check catches API-level violations, not behavioral ones.
