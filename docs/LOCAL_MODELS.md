# Local (on-device) models — GGUF via llama.cpp

Open Chat can run small language models **entirely on the phone**: download
once, chat offline afterwards. The engine is [llama.cpp](https://github.com/ggml-org/llama.cpp),
pinned at tag **b10941**, compiled per-ABI into `libllamajni.so`
(arm64-v8a / armeabi-v7a / x86_64, CPU + mmap, 4096-token context).

## Where to find it

`Settings → Local models (on-device)`

- **Download** any catalog model (verified HuggingFace GGUF files, ≤6B params).
  Downloads are resumable: **Pause / Resume / Cancel**, and a paused or
  interrupted download survives closing the app (`.part` + `.meta` sidecar).
- **Import .gguf** — pick any `.gguf` file from phone storage (SAF). You can
  also download a model *manually* on a PC, copy it to the phone, and import it.
- **Export** — save a downloaded model file back to phone storage (or share it).
- **Delete** — frees storage (and unloads the model if it was resident).

After download/import, open the chat model selector and pick the model under
**"Local (on-device)"**. Local models bypass the provider registry entirely —
no API key, no account, no network.

## How chat uses it (architecture)

- Routing: conversation `modelId` starting with `local:` resolves to a
  `LocalModelSpec` and is executed by `LocalInferenceEngine` — the same
  streaming message pipeline as cloud models (partial content kept on cancel).
- Background: generation runs on a dedicated single-thread dispatcher; the UI
  thread is never blocked. While generating, the existing
  `RuntimeForegroundService` (dataSync) protects the process from being killed
  (§14/§25); navigation inside the app never stops a run.
- Lifecycle: `Application.onTrimMemory(RUNNING_LOW+)` unloads weights when the
  system is under memory pressure (only when not generating). Only ONE model is
  resident at a time; switching models frees the previous one.
- Prompting: each model's own chat template (GGUF metadata) is applied via
  `llama_chat_apply_template`; history is trimmed to ≈9k chars (≈2.2–3k tokens)
  so it always fits the 4096 context together with up to 1024 generated tokens.

## Honest limits (§32)

- **CPU-only** inference. Speed depends on the SoC: 1–2B models are usable on
  most phones (≈5–15 tok/s on mid-range), 3B+ is noticeably slower.
- **RAM**: keep ≈ model file size + 350 MB free. Loading fails with an explicit
  message otherwise. 32-bit (`armeabi-v7a`) cannot address models ≳1.5 GB.
- Context is 4096 tokens; very long chats are trimmed (oldest first, newest
  message always kept).
- If an APK was built without the pinned llama.cpp source, the UI says so and
  download/import/export still work — inference is simply unavailable (no fake
  success).

## Catalog (verified at authoring time)

| Model | Params | Quant | ≈Size | Repo |
|---|---|---|---|---|
| Qwen2.5 Coder 1.5B Instruct | 1.5B | Q4_K_M | 1065 MB | Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF |
| Qwen2.5 Coder 3B Instruct | 3B | Q4_K_M | 2007 MB | Qwen/Qwen2.5-Coder-3B-Instruct-GGUF |
| Llama 3.2 1B Instruct | 1B | Q4_K_M | 770 MB | bartowski/Llama-3.2-1B-Instruct-GGUF |
| Llama 3.2 3B Instruct | 3B | Q4_K_M | 1925 MB | bartowski/Llama-3.2-3B-Instruct-GGUF |
| Gemma 2 2B IT | 2.6B | Q4_K_M | 1629 MB | bartowski/gemma-2-2b-it-GGUF |
| Qwen2.5 1.5B Instruct | 1.5B | Q4_K_M | 1065 MB | Qwen/Qwen2.5-1.5B-Instruct-GGUF |
| SmolLM2 1.7B Instruct | 1.7B | Q4_K_M | 1006 MB | bartowski/SmolLM2-1.7B-Instruct-GGUF |
| DeepSeek R1 Distill Qwen 1.5B | 1.5B | Q4_K_M | 1065 MB | bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF |
| Phi-3.5 Mini Instruct | 3.8B | Q4_K_M | 2282 MB | bartowski/Phi-3.5-mini-instruct-GGUF |

All URLs are `https://huggingface.co/<repo>/resolve/main/<file>` and were
verified live (HTTP 200 + byte size) before being added. A JVM test
(`LocalModelsTest`) guards id uniqueness, URL shape and size sanity.

## Building the engine

CI fetches the pinned source automatically:

```yaml
- uses: actions/checkout@v4
  with: { repository: ggml-org/llama.cpp, ref: b10941,
          path: app/src/main/cpp/llama.cpp, fetch-depth: 1 }
```

For a local Gradle build, run the same fetch first — without the directory the
build still succeeds and ships an honest stub that reports "unavailable".

To update the pin: change `ref:` in both workflows, check the llama.h API
signatures used by `app/src/main/cpp/llama_jni.cpp`, and rebuild.
