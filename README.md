<div align="center">

# MLCChat + Shell Tools — a fully offline, on-device coding assistant for Android

**Run powerful LLMs 100% locally on a phone — and let them run real shell commands.**

[![Based on MLC LLM](https://img.shields.io/badge/based_on-mlc--ai%2Fmlc--llm-blue?logo=github)](https://github.com/mlc-ai/mlc-llm)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![Offline](https://img.shields.io/badge/network-100%25_offline-success)](#)

</div>

> This is a personal fork of [**mlc-ai/mlc-llm**](https://github.com/mlc-ai/mlc-llm) focused on
> turning the **MLCChat** Android app into a practical, fully offline assistant that can
> **test the code it writes** — directly on the device. The upstream project's README is kept
> at [README.upstream.md](README.upstream.md).

---

## What this fork adds

Everything here runs **on the phone, with the network off**. No cloud, no API keys, no data
leaving the device.

| Feature | What it does |
|---|---|
| 🧠 **Bundled offline LLMs** | Three models shipped and run entirely on-device (pushed via `adb`, no in-app download): a 7B coding model, a 3B general-chat model, and a locally converted security-focused coding model. Tuned to fit the Samsung Galaxy S23 Ultra's GPU budget. |
| 💬 **Multi-session chat history** | Each model keeps independent, persistent conversations (Room DB). Start new chats, switch between topics, delete them — history survives restarts. |
| 🖥️ **Shell tools (the headline feature)** | The model can run **real shell commands** on the device through [Termux](https://github.com/termux/termux-app) — a full Linux environment (`bash`, `python`, `pip`, `git`, `gcc`, …). It writes code, runs it, reads the output, and keeps going. |

---

## 🖥️ Shell tools in action

With shell tools enabled, the model follows a simple convention: it proposes a command, you
approve it, it runs in Termux, and the captured output is fed straight back into the
conversation — closing the loop so the assistant can actually **test and iterate on the code
it writes**.

<div align="center">

| Chat with a local model | Command approval gate | Shell settings |
|:---:|:---:|:---:|
| ![Chat](docs/img/02_chat.png) | ![Approval dialog](docs/img/03_shell_confirm.png) | ![Shell settings](docs/img/04_shell_settings.png) |

</div>

**Key design points:**

- **Safety first** — every command shows a **Run / Decline** confirmation by default. Nothing
  runs by accident; tapping outside the dialog counts as Decline.
- **Optional auto-run** — once you trust what the model is doing, flip on *Auto-run* to skip
  the confirmation. Off by default.
- **Choose the working directory** — point commands at your project folder inside Termux.
- **Sandboxed** — commands execute inside Termux's own environment, never in the app process.
- **Context-aware** — stdout/stderr/exit code are fed back (and truncated to fit the model's
  context window), so the assistant reacts to real results.

Full usage and setup notes: [`android/MLCChat/SHELL_TOOLS.md`](android/MLCChat/SHELL_TOOLS.md).

---

## Getting started

### Requirements
- A capable Android device (developed and tested on a **Samsung Galaxy S23 Ultra**, 12 GB RAM).
- [**Termux**](https://github.com/termux/termux-app/releases) — the **GitHub/F-Droid build**,
  *not* the Google Play one (the Play build lacks the `RUN_COMMAND` API the shell bridge needs).

### Enable shell tools on the device
In Termux, once:
```bash
pkg update && pkg install python git
mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings
```
Then in MLCChat: open a chat, tap the **terminal icon** in the top bar to enable shell tools,
and (optionally) the **settings icon** to set auto-run / working directory.

### Build the app
It's a standard Gradle build of `android/MLCChat` (JDK 17). The LLM weights are pushed to the
device separately rather than bundled in the APK. See
[`android/MLCChat/SHELL_TOOLS.md`](android/MLCChat/SHELL_TOOLS.md) for the shell-tools
specifics and the project's build notes for the model-packaging workflow.

---

## Credits

- Built on top of [**MLC LLM**](https://github.com/mlc-ai/mlc-llm) by the MLC AI community
  (Apache-2.0).
- The **shell-tools feature** (Termux bridge, tool-calling loop, approval gate, auto-run and
  working-directory settings) was designed and implemented end-to-end by
  [**Claude Code**](https://claude.com/claude-code), Anthropic's agentic coding tool — from
  surveying the 2,000+ forks for prior art, through writing the Kotlin, to building, installing
  and verifying it live on the device over `adb`. 🤖

---

<div align="center">
<sub>A personal, experimental fork. Not affiliated with or endorsed by MLC AI or Anthropic.</sub>
</div>
