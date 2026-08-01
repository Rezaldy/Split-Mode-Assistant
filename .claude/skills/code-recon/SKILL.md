---
name: code-recon
description: Token-efficient codebase traversal with a persistent code map. Use whenever exploring code to answer "where is / how does / what calls" questions, before implementing a feature in an unfamiliar area, when tracing a flow across modules, or any time you're about to read a file to find something out. ALWAYS consult and update the code map (.claude/codemap.md) — it is the memory that stops every session from re-paying the exploration cost for facts a previous session already established.
---

# Code recon: traverse cheap, remember what you learned

Context is a budget. Every whole-file read of something you only needed one
function from, and every re-discovery of a fact a previous session already
established, is spent budget that buys nothing. This skill has two halves:
traverse cheaply, and bank what you learn.

## Before exploring: check the map

Read `.claude/codemap.md` first (cheap — it's kept small). If it already
answers the question, **verify the anchor still exists** (one targeted grep
for the symbol) and move on without re-exploring. If the map is missing or
silent on the topic, explore — then pay it forward (see below).

## Traversal discipline

1. **Locate before you read.** Glob for file candidates, Grep for symbols.
   Never open a file to "look around" — arrive with a line number.
2. **Read ranges, not files.** Use offset/limit around the grep hit.
   A whole file is justified only when it's small or you're about to edit
   it substantially.
3. **Trace by symbol, not by scrolling.** To follow a flow, grep the next
   symbol (callee, interface, message key) rather than reading forward from
   where you are. `Grep -n` with 2–3 lines of context often answers the
   question without any Read at all.
4. **Fan-out goes to a subagent.** "Find every usage of X across modules" or
   "which class owns Y, could be anywhere" → delegate to the Explore agent
   and take back only the conclusion. Its intermediate reads never touch
   your context. Keep inline only searches with an obvious first place to
   look.
5. **Never re-read what's in context.** If you read it this session, trust
   it (the harness tells you if a file changed).
6. **This project has a shape — use it.** UI questions → `frontend/`,
   anything touching project files / Ollama / settings → `backend/`,
   contracts → `shared/`. Grep the RPC interface first: it's the table of
   contents for any cross-module flow.

## The code map: `.claude/codemap.md`

A single project-local file of *hard-won, stable* facts. Structure:

```markdown
# Code map — verified facts, cite before re-exploring
Entry format: **Topic** — fact. `path/File.kt` `symbolName` (verified YYYY-MM-DD)

## Architecture anchors
- **Backend chat responder** — demo response logic lives in
  `backend/.../BackendChatRepositoryModel.kt` `generateResponse()`; owns the
  mutable message list. (verified 2026-08-01)

## Flows
- **Send message** — frontend `ChatPanel.onSend` → RPC `ChatApi.sendMessage`
  → backend responder → Flow of tokens back. (verified 2026-08-01)

## Gotchas
- **RPC impl registration** — done via <mechanism>, in <file>; new methods
  need no extra registration, new interfaces do. (verified 2026-08-01)
```

**Anchor rule:** anchor entries to file paths + symbol names, never bare
line numbers — symbols survive edits, line numbers don't. Date-stamp every
entry so staleness is visible.

**What earns an entry:** anything that took more than ~2 tool calls to
establish and will plausibly be needed again — where a responsibility lives,
how a flow crosses the RPC boundary, how registration/DI works, a
non-obvious gotcha. One or two lines each.

**What never goes in:** anything derivable from one grep, anything already
in CLAUDE.md, code snippets, speculation, session-specific state ("currently
broken"). The map's value is inverse to its size — a bloated map costs more
to read than it saves.

**Maintenance is part of using it:** when a verified anchor turns out to be
wrong (file moved, symbol renamed), fix or delete the entry *in the same
session* — a wrong map is worse than no map. After a big refactor, sweep the
affected section. Keep the whole file under ~120 lines; when it grows past
that, compress the oldest sections.

## End-of-exploration checklist

Before moving on from any exploration that took real effort:
1. Did I learn something the next session would otherwise re-derive? → add
   an entry (or update the stale one I just corrected).
2. Am I about to paste large code into my summary? → cite `path` + symbol
   instead; the map and the code are the source of truth, not the prose.
