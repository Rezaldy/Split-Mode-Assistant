# Split Mode Assistant

Split Mode Assistant is a JetBrains IDE plugin that adds a local/self-hosted AI chat tool window backed by a model source you configure.

All project access and all model traffic stay on the host machine, so it works in Remote Development (split mode) out of the box. Your code goes only to the model source you point it at — nothing else.

## Requirements

- A JetBrains IDE, version 2026.1 or later (IntelliJ IDEA, PyCharm, WebStorm, and most IntelliJ-based IDEs).
- An Ollama-compatible API endpoint reachable **from the backend/host machine** — default `http://localhost:11434` — with at least one chat model pulled.
- Optional: an embedding model, if you want to enable project indexing.

## Install

1. Download the plugin zip from the GitHub Releases page.
2. In the IDE, go to **Settings | Plugins**, click the **⚙** gear icon, and choose **Install Plugin from Disk**.
3. In Remote Development, install the plugin on **both** the host and the client, using the **same version**.

Mismatched versions between host and client silently break the connection between the UI and the backend — no error is shown, so it's worth double-checking after upgrades.

## Quick start

1. Open the **Code Assistant** tool window.
2. Pick a model from the dropdown in the tool window header.
3. Ask a question.

Type `@` in the input to attach a project file. Select code in the editor to have that selection included in the context automatically.

## Features

- Streaming chat, with reasoning ("thinking") display and Markdown/code rendering
- Multiple chat tabs, each with its own independent conversation
- `@` file mentions for pulling specific files into context
- Automatic context from open files and the current editor selection
- Opt-in local project indexing (RAG) via embeddings
- Per-reply token usage display
- Commit-message generation button in the commit toolbar
- Customizable system prompts

## Learn more

- [DOCUMENTATION.md](DOCUMENTATION.md) — how the plugin works, end to end.
- [BOOTSTRAP.md](BOOTSTRAP.md) — building from source and dev setup.
- [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) and [CLAUDE.md](CLAUDE.md) — for contributors.
