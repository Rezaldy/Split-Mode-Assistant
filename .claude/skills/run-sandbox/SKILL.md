---
name: run-sandbox
description: Launch and verify the plugin in a sandbox IDE — monolithic runIde or split mode — without rediscovering the traps. Use whenever a change needs to be seen running, when asked to "run it" / "test it in the IDE", when a runIde/prepareSandbox build fails, or before handing the user a verification checklist. Encodes the jar-lock trap, model env setup, and where logs land per mode.
---

# Running the sandbox (monolithic + split mode)

The commands are trivial; the traps are not. Check the traps FIRST.

## Trap 0: the jar lock (hit twice already — check before every launch)

`prepareSandbox FAILED ... user-mapped section open` means a **still-running
sandbox IDE** holds the previous plugin jar. Windows will not let Gradle
overwrite it. Diagnose, don't retry:

```powershell
Get-CimInstance Win32_Process -Filter "name='java.exe'" | ForEach-Object { "{0} | {1}" -f $_.ProcessId, $_.CreationDate }
```

A long-lived `java.exe` from an earlier session is the sandbox. The user
closes it (red Stop in IDEA's Run tool window, or close the sandbox
window) — do not kill processes yourself; you cannot reliably tell a
sandbox from the user's tooling. THEN relaunch.

## Model setup (before launching anything that will chat)

- No hardcoded models: the backend takes `OLLAMA_MODEL` env, else the
  first model from `/api/tags` — which is the **slow 27b** on this
  machine. For dev loops always set `OLLAMA_MODEL=qwen3.5:4b`.
- The env must reach the **backend** process:
  - CLI (Git Bash): `OLLAMA_MODEL=qwen3.5:4b ./gradlew runIde`
  - PowerShell: `$env:OLLAMA_MODEL="qwen3.5:4b"; ./gradlew runIde`
  - Split mode from IDEA: set it in the **"Run IDE (Backend)"** run
    config's Environment variables — the frontend config does nothing.
- Sanity-check the source first when chat matters: `/ollama-smoke`.

## Launching

- **Monolithic:** `./gradlew runIde` — run it in the background (it blocks
  until the sandbox closes; exit 0 = user closed it normally). First
  launch after a dependency change takes minutes.
- **Split mode:** only from IDEA — the "Run IDE (Split Mode)" compound
  config launches `:runIdeBackend` + `:runIdeFrontend`. The backend is up
  when its console prints `Join link: tcp://127.0.0.1:...`. It builds
  whatever branch is CHECKED OUT — say which branch that is when handing
  the user a checklist.
- Both modes need Ollama reachable from the backend process.

## Reading the logs (know which mode you're in)

- Monolithic sandbox: `.intellijPlatform/sandbox/code-assistant/IU-*/log*/idea.log`
- Split backend (where ALL plugin backend logs land):
  `.intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log`
- Split frontend: `...log_runIdeFrontend/frontend/*/idea.log`
- Grep for our code first: `transtrend|OllamaException|Chat generation`.

## Known benign noise (do not chase these)

- `InstanceNotOverridableException` warnings — stock 2026.1 sandbox chatter.
- `StationSocketConnectionLoop ... jb.station.*.sock refused` — Toolbox
  discovery; absent on this machine.
- ONE early `TLS Transport ... FAULTED` right after backend start — the
  client knocking before the listener is ready; only repeated failures
  with no client window are a real problem.
- netty `sun.misc.Unsafe` / SLF4J provider warnings.

## Handing off verification

The user does the clicking. Give them: which window/mode, which branch it
was built from, the exact steps (open project → Code Assistant tool
window → action), and what PASS looks like. Milestone gates come from
`/milestone-done`; remind them to STOP sandbox sessions afterward so the
next build doesn't hit Trap 0.
