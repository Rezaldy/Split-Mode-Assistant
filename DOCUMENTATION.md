# Split Mode Assistant — How It Works

## Overview

Split Mode Assistant is a JetBrains IDE plugin that provides an AI chat tool window backed by a user-configured model source.

It speaks a single, configured Ollama-compatible REST API. There is no multi-provider abstraction, and no other model source is supported — one provider, done well.

Everything project-aware (file access, indexing, prompt assembly) and all LLM traffic run on the host/backend. The client renders UI only.

In a local (monolithic) IDE, both halves run in one process. Behavior is identical to split mode — there is no separate "local mode" feature set.

## Architecture (brief, for curious users)

The plugin is made of three modules:

- `shared/` — RPC interfaces and DTOs. Plain data only; no logic.
- `frontend/` — the tool window UI. Runs in the JetBrains Client.
- `backend/` — the Ollama HTTP client, context collection, file search, settings, and the project index. Runs on the host.

Data crosses the frontend/backend boundary only via RPC, as plain data. No project objects, file handles, or platform objects are ever passed across it.

Consequences for users:

- In Remote Development, plugin settings appear under **Settings on Host**, not in the client's settings.
- Logs land in the **host's** `idea.log`, including for problems that appear to originate in the UI.
- The host and client must run the **same plugin version**, or the connection between UI and backend silently breaks — mismatched methods hang or deliver nothing, while unchanged ones keep working, with no error in any log.

## Chat

### Streaming and rendering

- Replies stream in as they are generated, token by token.
- Reasoning models' "thinking" output streams into a collapsible section. That section auto-collapses once the answer itself starts.
- Markdown rendering supports GFM: tables, task lists, and strikethrough.
- Fenced code blocks render as read-only IDE editors with syntax highlighting, when the IDE recognizes the language.
- All chat text is selectable and copyable.

### Chat tabs

- Use the "+" action in the tool window tab strip to open a new tab.
- Each tab is an independent conversation with its own history, and therefore its own prompt context — keeping each conversation scoped to one problem.
- Closing a tab frees its history on the host.
- Closing the last tab opens a fresh one; the tool window is never left with zero tabs.

### History and stopping

- The last 20 messages of a tab's conversation are sent with each request.
- While a reply is streaming, the send button becomes a Stop button.
- Stopping keeps whatever has streamed in so far as the reply; nothing is discarded.

### Token usage

Each reply shows a footer line, for example:

```
9,812 in · 2,411 out · 74% of 16,384
```

- **in** — prompt tokens evaluated for this request.
- **out** — tokens generated in this reply.
- **% of N** — context-window (`num_ctx`) utilization, as reported by the model source.

Ollama excludes cached prompt prefixes from these counts, so the figure is a lower bound, not an exact total.

### Guardrail notes

If the model stopped because it hit a token limit, or the reported counts show the context window at 98% or more full, the reply includes a note saying so — instead of the reply silently cutting off with no explanation.

### Error handling

- Connection failures, aborted streams, and a source that stops sending data mid-stream all surface as distinct error bubbles in the chat.
- Any partial reply that had already streamed in is kept, not discarded, when an error occurs.
- A stalled stream is detected as no data for 120 seconds between chunks.
- Up to 10 minutes is allowed before the first chunk arrives, to accommodate cold model loads.
- A generation survives client-host connection blips: it keeps running on the host, and the UI simply re-attaches to it when the connection recovers.

### Search

A toolbar search field with previous/next navigation lets you search across the current conversation's messages.

## Model selection

The dropdown in the tool window header lists models returned by `GET /api/tags` on the configured source. The selection persists across sessions. A refresh button re-queries the source for its current model list.

Precedence for which model is actually used, highest first:

1. The `OLLAMA_MODEL` environment variable, read on the host.
2. The stored selection.
3. The first model returned by `/api/tags`.

## Context: what the model sees

Each request carries a system prompt (customizable — see Settings) plus a project-context block assembled under a hard budget of 24,000 characters. The block is filled in this priority order:

1. **`@`-mentioned files** — full content, first claim on the budget. Binary files are rejected with a note instead of their content.
2. **Your current editor selection** — up to 8,000 characters, labeled with file path and line numbers.
3. **The remainder** splits 60% open files / 40% index-retrieved snippets. Whatever open files leave unused spills over to retrieval. With project indexing off, or no retrieval hits, open files take the entire remainder.

Every block in the context is labeled with its source, so the model knows why it is seeing each piece of content.

The context files bar, shown between the chat history and the input field, lists exactly which files are currently included in context. Hover over an entry to see its full path.

## @ file mentions

Typing `@` in the chat input opens a fuzzy file search over project files only — library and external files are excluded.

Matching is tried in this order, and results are ranked accordingly:

1. Name prefix
2. Substring
3. Subsequence
4. Path match

Results are capped at 20. The search is backed by a cached file list that is invalidated on any VFS change, so it stays fast at keystroke frequency.

Mentions travel with the message as structured attachments, not as text parsed back out of the message body. The referenced files' contents are read on the host at request time.

## Project indexing (opt-in, local RAG)

This section is the summary. For a code-level, step-by-step walkthrough of the whole pipeline — chunking, the `/api/embed` calls, the on-disk format, retrieval scoring, and how snippets are fitted into the budget — see [EMBEDDINGS.md](EMBEDDINGS.md).

### Why an embedding model?

Keyword search finds files whose text matches your words. An embedding model instead maps text to vectors, so search can find code by meaning — asking "where do we retry failed requests?" can surface `OllamaClient` even if no file in the project contains the word "retry".

At question time, the plugin:

1. Embeds your question.
2. Compares it against pre-computed vectors of project code chunks.
3. Adds the best-matching snippets to the context.

The result is relevant code you never opened, surfaced without sending your whole project to the model. Embeddings are computed by the same (or a dedicated) Ollama-compatible source, so code never leaves your infrastructure.

### What gets indexed

- Project files only, using the same scope as the IDE's project view.
- Files must be non-binary and between 1 byte and 512 KB.
- The index is capped at 4,000 files and 25,000 chunks.
- Files are split into chunks of at most 2,000 characters, with an 8-line overlap between adjacent chunks.

### When it updates

- File create, delete, modify, and move events are debounced by 3 seconds and applied as incremental updates.
- A startup reconciliation pass hash-checks files to catch changes made while the IDE was closed.
- A manual Rebuild button is available in Settings and via the sync indicator.

### Where the index is stored

On the **host** machine, under the IDE's system directory:

```
<IDE system path>/code-assistant-index/<project-hash>/
```

containing `meta.json` and `vectors.bin`. The index never leaves the host machine. Deleting the folder is safe — it rebuilds automatically the next time it's needed.

### Embedding model choice

Resolution order, highest first:

1. The `OLLAMA_EMBED_MODEL` environment variable.
2. The Settings value.
3. Auto-pick — the first model from `/api/tags` whose name contains "embed", "bge", "minilm", or "arctic".

Embeddings can target a different base URL than chat, via `OLLAMA_EMBED_BASE_URL` or the corresponding setting.

### Retrieval at question time

- The top 12 chunks by cosine similarity are retrieved, subject to a similarity floor of 0.30.
- Adjacent chunks from the same file are merged into one snippet.
- Each resulting snippet is capped at 4,000 characters.
- Files that are already open or `@`-mentioned are never retrieved, since they are already included in full elsewhere in the context.
- Retrieved snippets are labeled `[retrieved]` in the context and are shown in the context files bar.

### Failure policy

Index errors never block chat. If indexing or retrieval fails, the plugin degrades to open-files-only context and logs the error to the host's `idea.log`.

The tool window header shows a color-coded sync indicator with four states:

- In sync
- Building
- Stale
- Error

Clicking the indicator triggers a rebuild.

## Commit message generation

A lightning-bolt button in the commit toolbar, next to Amend, generates a commit message (action `CodeAssistant.GenerateCommitMessage`).

What it does:

1. Builds a real unified diff of exactly the changes included in the commit — checkbox selections and partial changes are honored, falling back to the active changelist if nothing is explicitly checked.
2. Sends that diff, capped at 24,000 characters, to the selected model together with the commit system prompt.
3. Streams the resulting message directly into the commit message field.

Reasoning tokens never enter the commit message field — only the final message text does. Clicking the button again regenerates the message. Failures appear as notifications, not as silent no-ops.

While a message is being generated, a background progress entry — "Split Mode Assistant: generating commit message…" — appears in the status bar (bottom right). Cancelling it from there stops the generation and keeps whatever text had already streamed into the field.

## Settings

Location: **Settings | Tools | Split Mode Assistant** (on the host, in Remote Development).

| Setting | Notes |
|---|---|
| Base URL | Default `http://localhost:11434`. The `OLLAMA_BASE_URL` environment variable, if set, overrides and locks this field. |
| Use IDE proxy | Default off — model sources are usually local or on the LAN. |
| Context window (`num_ctx`) | Default 16,384, minimum 2,048. Raise it for long conversations or large contexts. |
| System prompts | Separate fields for chat and commit-message generation. Each field shows the effective prompt; clearing a field and applying restores the built-in default. |
| Project indexing | Enable toggle, Rebuild button, embedding model selection, and an optional custom embedding URL. |

### Environment overrides

Read on the host; useful for containerized backends:

- `OLLAMA_BASE_URL`
- `OLLAMA_MODEL`
- `OLLAMA_EMBED_MODEL`
- `OLLAMA_EMBED_BASE_URL`

## Privacy & data flow

Prompts, including file contents placed in context, go only to the configured model source. The project index lives on the host's disk. Nothing is sent anywhere else.

Chat history is held in memory per IDE session, per tab, and is not persisted — it does not survive an IDE restart.

## Troubleshooting

**Empty model dropdown / connection errors**
Confirm the model source is reachable from the host, e.g. run `curl <base-url>/api/tags` on the host machine, and check the Ollama server logs.

**Replies stopping early**
Read the note under the reply. If it reports the context window full, raise the context window size in Settings.

**Split mode oddities (chat silent, features missing)**
Confirm the host and client are running the same plugin version. Backend logs are in the host's `idea.log`.
