# Shell tools (Termux bridge)

MLCChat can let a model run shell commands on the device through
[Termux](https://github.com/termux/termux-app), a full Linux environment
(`bash`, `python`, `pip`, `git`, `gcc`, …). Commands run inside Termux's own
sandbox — never inside the MLCChat process.

## One-time device setup

1. **Install Termux** from F-Droid or the GitHub releases. Do *not* use the
   old Google Play build (it is stale and its `RUN_COMMAND` API differs).
2. Open Termux once and install anything you want the model to use, e.g.:
   ```sh
   pkg update && pkg install python git
   ```
3. Allow external apps to drive Termux. In Termux run:
   ```sh
   mkdir -p ~/.termux
   echo 'allow-external-apps = true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```
   (Without this, MLCChat cannot start commands and you'll get a bridge error
   in the chat.)
4. First time MLCChat sends a command, Android may prompt to grant the
   `com.termux.permission.RUN_COMMAND` permission — allow it.

## Using it

- In a chat, tap the **terminal icon** in the top bar to turn shell tools on
  (it brightens when enabled). This injects a system prompt teaching the model
  the exec-block convention.
- When the model wants to run something it emits a fenced block:
  ````
  ```sh exec
  python3 -c "print(1+1)"
  ```
  ````
- MLCChat shows the command and asks you to **Run** or **Decline**. On Run, the
  command executes in Termux and its stdout/stderr/exit code are fed back to
  the model, which continues. The loop is capped at
  `ShellToolProtocol.MAX_STEPS` (8) commands per turn.
- Every command requires explicit approval. Tapping outside the dialog counts
  as Decline — nothing runs by accident.

## Implementation

- `tools/TermuxShell.kt` — the `com.termux.RUN_COMMAND` intent bridge; sends a
  foreground-service intent and receives the result via a one-shot broadcast
  `PendingIntent`.
- `tools/ShellToolProtocol.kt` — system prompt, exec-block parser, and output
  formatting fed back to the model.
- `AppViewModel.ChatState` — the tool loop (`streamAssistant` →
  `onAssistantComplete` → `approvePendingCommand`/`declinePendingCommand`).
- `ChatView.kt` — the top-bar toggle and the confirmation dialog.
- `AndroidManifest.xml` — the `RUN_COMMAND` permission and the `<queries>`
  entry making the Termux package visible (Android 11+).

## Notes / limits

- Commands run with a 120s timeout (`TermuxShell.run`). Long-running or
  interactive commands (that wait for stdin) will time out.
- Output fed back to the model is truncated (~4000 chars) so a chatty command
  can't blow the 4096-token context window.
- Tool-result turns are persisted as user messages, so they survive session
  switches and reloads.
