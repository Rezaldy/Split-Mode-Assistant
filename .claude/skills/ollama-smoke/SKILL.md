---
name: ollama-smoke
description: Verify the Ollama model source is healthy before touching plugin code. Use FIRST whenever chat doesn't respond, streaming stalls, the model dropdown is empty, "connection refused" appears anywhere, or any model-related behavior seems broken — a large share of apparent plugin bugs are just the model source being down, the model missing, or a cold model loading slowly. Also use to sanity-check the environment before starting a runIde session that will exercise chat.
---

# Ollama smoke test

The plugin talks to one endpoint speaking the Ollama REST API (default
`http://localhost:11434`, overridable via `OLLAMA_BASE_URL`). When chat
misbehaves, prove the endpoint first — it takes 30 seconds and regularly
saves an hour of debugging the wrong layer.

## 1. Resolve the effective base URL

Precedence: `OLLAMA_BASE_URL` env var (of the process running the
**backend**) > stored setting > default `http://localhost:11434`. On this
machine check with:

```powershell
$env:OLLAMA_BASE_URL
```

In split mode the connection is made from the **host** — the endpoint must
be reachable from wherever the backend process runs, not from the client
machine.

## 2. Is the server up, and which models exist?

```powershell
curl.exe -s http://localhost:11434/api/tags
```

- **Connection refused** → server not running: `ollama serve` (or the
  Windows service/tray app isn't started).
- **JSON with empty `models`** → server fine, no models pulled:
  `ollama pull <model>` (any coder-capable model; check what the user wants).
- **Model list present but missing the selected model** → the plugin's
  persisted selection is stale; expect "model not found" errors until the
  user re-selects or pulls it.

## 3. Does chat actually stream?

```powershell
curl.exe -s -N http://localhost:11434/api/chat -d '{\"model\": \"<a model name from step 2>\", \"messages\": [{\"role\": \"user\", \"content\": \"Say hi in three words.\"}]}'
```

Expect NDJSON: one JSON object per line, arriving incrementally, ending with
`"done":true`. Interpretation:

- **Long pause before the first line** → model cold start. This is normal
  and can take tens of seconds — it is exactly why `OllamaClient` must have
  no request timeout. Not a bug.
- **HTTP 404 with a "model not found" body** → pull the model.
- **Lines arrive but the plugin UI shows nothing** → the endpoint is fine;
  now it IS a plugin bug — look at the backend's NDJSON→`Flow` parsing and
  the RPC stream path, and check the **host's** `idea.log` (split mode logs
  land there, not on the client).

## 4. Verdict

State clearly which layer is broken: **environment** (server down, model
missing — give the user the exact command to fix it) or **plugin** (endpoint
proven healthy — name the component to investigate next). Never start
editing plugin code while step 2 or 3 still fails.
