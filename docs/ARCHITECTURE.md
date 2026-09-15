# Architecture

```
Open Chat
│
├── AI Chat (ui/chat)                 ← Compose UI
├── OpenCode Controller (ai/opencode)  ← install / run / config sync (§29)
├── Ubuntu Userspace (ubuntu/)
│   ├── RootfsCatalog      pinned Ubuntu Base URLs + SHA256
│   ├── UbuntuInstaller    download → verify → extract → configure → apt → node
│   ├── ProotRunner        proot argv/session builder (bionic bundle on x86_64,
│   │                      pinned static download on arm64/armhf)
│   ├── UbuntuRuntime      state machine, exec / execStream / sessions
│   ├── UbuntuFileSystem   path mapping + traversal protection
│   └── UbuntuProcessManager
├── Terminal / PTY (terminal/)
│   ├── Pty (JNI, C forkpty)   app/src/main/cpp/pty.c
│   ├── TerminalBuffer         real ANSI/VT parser (pure Kotlin, tested)
│   ├── TerminalSession        pid + fd lifecycle, reader thread
│   └── TerminalManager
├── Process Manager (§14)   foreground service keep-alive
├── Workspace Manager       per-project config + dirs in rootfs
├── File Manager            4 domains + SAF import/export
├── Provider Manager        OpenAI/Anthropic/Gemini/OpenRouter/Ollama/custom
├── Model Manager           CRUD + default + tests
├── Ollama Manager          remote-first servers
└── Secure Secrets Manager  Android Keystore AES-256-GCM
```

## Key decisions
1. **Ubuntu userspace, not a VM.** Rootfs (Ubuntu Base, jammy/noble) is
   downloaded once, SHA256-verified against pinned cdimage hashes, extracted
   with commons-compress (traversal-guarded), and entered via **proot**
   (static, per-ABI, hash-pinned). `proot -0 -R rootfs` fakes root inside the
   app sandbox — no root access, no VM.
2. **targetSdk 28.** Android 10+ blocks exec() of app-data files for apps
   targeting ≥ 29. Targeting 28 keeps the legacy SELinux policy so
   bash/apt/node actually run (Termux uses the same approach). minSdk stays 29
   per spec (Android 10+).
3. **Real PTY via JNI.** `forkpty()` in C; Kotlin-side reader thread parses
   the byte stream into a VT buffer; the Compose Canvas renderer observes a
   revision counter — no whole-buffer copies, no full-rootfs loads (§26).
4. **UI ↔ services only.** Screens call `AppGraph` singletons; all runtime
   logic lives in managers (§17). State is StateFlow; errors are structured
   `ErrorInfo` with repair actions (§24).
5. **Honest fallbacks (§32).** When signing secrets are missing, CI labels the
   build ephemeral; when a process dies with the app kill, the Process Manager
   marks it EXITED with an explanation; every failure maps to actionable
   causes + [Retry][Repair][Reset][View Logs].
6. **OpenCode config sync (§29).** `OpenCodeConfigSync` writes the official
   `~/.config/opencode/opencode.json` from the Open Chat model selection —
   real schema (`$schema`, `provider.<id>.npm/@ai-sdk/*`, `model`), no fake
   config OpenCode would ignore.
