# Log recipes

Git Bash on this machine (`grep`, `tail`, `awk`, `sort`, forward slashes).
Sandbox log paths (see `run-sandbox` for the full explanation of each mode):

- Monolithic: `.intellijPlatform/sandbox/code-assistant/IU-*/log*/idea.log`
- Split backend: `.intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log`
- Split frontend: `.intellijPlatform/sandbox/*/*/log_runIdeFrontend/frontend/*/idea.log`

## Only our plugin

```bash
grep -E "rizkybusiness|OllamaException|Chat (stream|generation)|Model discovery|Index build" .intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log
```

## Last generation

```bash
grep -E "Chat (stream|generation)" .intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log | tail -n 20
```

Read the `Chat stream done` line's `reason=` field: `stop` means the model
finished on its own; `length` means `num_ctx`/`num_predict` cut it off — a
config problem, not a bug. No `Chat stream done` line at all, paired with a
`stalled` or `ended without done:true` warning just above it, means
something between the plugin and the model died mid-stream — start with
`ollama-smoke`, not the plugin code.

## Follow live during a sandbox session

```bash
tail -f .intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log
```

Swap the glob for the monolithic or frontend path depending on which mode
is running (only one will exist at a time for a given sandbox session).

## Both sides of split mode, merged by time

```bash
grep -h -E "^[0-9]{4}-[0-9]{2}-[0-9]{2}" .intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log .intellijPlatform/sandbox/*/*/log_runIdeFrontend/frontend/*/idea.log | sort
```

IntelliJ log lines start `yyyy-MM-dd HH:mm:ss,SSS`, so a plain `sort`
interleaves host and client correctly. In the sandbox both files come from
one clock on one machine; in real Remote Development they're two machines'
clocks — treat small offsets as expected, not as evidence of a race.

## Enable debug logging, then reproduce

1. In the sandbox IDE (host window in split mode): Help | Diagnostic Tools
   | Debug Log Settings, add `#com.rizkybusiness.ai.assistant.ollama` (or
   the relevant sub-package), `:trace` suffix for trace level.
2. Reproduce the issue — no restart needed, it applies immediately.
3. Re-run the "last generation" grep above; debug/trace lines now appear
   interleaved with the always-on `info`/`warn` ones.

## Strip the benign noise

```bash
grep -v -E "InstanceNotOverridableException|StationSocketConnectionLoop|sun.misc.Unsafe|SLF4J" .intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log
```

See `run-sandbox` for what each of these is and why it's safe to ignore.

## Attach to a PR or issue

Paste only the lines for the one generation that misbehaved — the `gen=`
id if the code you're looking at has it, otherwise a tight time window
bounded by the surrounding `Chat generation`/`Chat stream` lines — plus the
run mode (monolithic/split), the branch, and which log file (host or
client) they came from. Redact anything that shouldn't have been logged in
the first place: if a pasted line contains prompt text, file contents, or a
base URL with credentials, that's the log line itself being wrong (see
SKILL.md's line-shape rules) — fix the log call before shipping the PR, in
addition to redacting the paste.
