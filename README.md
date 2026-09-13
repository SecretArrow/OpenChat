# Open Chat — AI Coding Environment for Android

Open Chat brings a real Linux desktop/server AI coding experience to Android 10+:
a genuine **Ubuntu userspace** (not Debian, not a VM), a **real PTY terminal**,
the **OpenCode CLI**, multi-provider AI chat, Ollama, a file manager, workspaces
and background processes — all inside one app.

## Feature map
- **AI Chat** — streaming responses, Markdown + code blocks with copy, tool
  execution output, cancel/retry/regenerate, conversation history, per-
  conversation backend (direct API or OpenCode CLI).
- **Ubuntu userspace** — one-time install flow (download → verify SHA256 →
  extract → configure → apt packages → Node.js/npm/pnpm → OpenCode CLI),
  with Install / Repair / Update / Reset actions and persisted status.
- **Terminal** — real pseudo-terminal (JNI forkpty) running bash inside the
  Ubuntu userspace; copy/paste, resize, extra keys row, reconnect.
- **OpenCode CLI** — installed and managed inside the userspace; provider/model
  config synced from Open Chat (official `opencode.json` schema).
- **Providers & Models** — OpenAI, Anthropic, Google Gemini, OpenRouter,
  Ollama, any custom OpenAI-compatible endpoint; full model CRUD, default
  model, enable/disable, connection tests.
- **Ollama** — remote-first (e.g. `http://192.168.1.10:11434`): test, list,
  pull (with progress), delete, run models.
- **Secrets** — Android Keystore (AES-256-GCM); keys are never stored in
  plaintext, never displayed after saving (`••••••••••••`).
- **Background processes** — `npm run dev &`, `node server.js &` survive
  navigation and app backgrounding via a foreground service + Process Manager.
- **Workspaces** — per-project provider/model/env config, persisted.
- **File manager** — four isolated domains (App data / Ubuntu rootfs /
  Workspaces / Shared storage via SAF), full CRUD + import/export.

## Releases (per-ABI split APKs)
| File | Devices |
|---|---|
| `OpenChat-arm64-v8a.apk` | Modern 64-bit ARM (most phones) |
| `OpenChat-armeabi-v7a.apk` | Older 32-bit ARM |
| `OpenChat-x86_64.apk` | Emulators / x86_64 tablets |

Grab them from [Releases](https://github.com/SecretArrow/OpenChat/releases)
with `checksums.txt` (SHA256). Release builds are signed with the persistent
release keystore via GitHub Actions secrets — see
[docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## CI/CD (builds happen ONLY in GitHub Actions)
- **CI** (`.github/workflows/ci.yml`) — every push/PR: per-ABI debug APKs + unit tests, Gradle cache, concurrency-canceled.
- **Release** (`.github/workflows/release.yml`) — on tag `v*` or manual dispatch: signed split APKs + `apksigner` verification + SHA256 + GitHub Release.
- **Auto Fix** (`.github/workflows/autofix.yml`) — when CI fails on `main`: deterministic log analysis (`scripts/autofix.py`), optional AI-assisted patch (`scripts/autofix_ai.py`), transient-failure rerun, fixes auto-pushed to `main`.

## Build locally (optional)
```bash
./build.sh release   # Clean → validate → build splits → verify → SHA256
```

## Architecture
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [INTEGRATION.md](INTEGRATION.md)
for the service graph (UbuntuRuntime / TerminalManager / ProviderManager / …)
and the UI ↔ service separation rules.

## Notes
- `targetSdk` is intentionally 28 so the SELinux policy permits executing the
  Ubuntu rootfs binaries inside the app sandbox (same approach as Termux).
- Ollama over plain HTTP LAN addresses is allowed on purpose (`usesCleartextTraffic`).
