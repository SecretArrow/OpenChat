# Local (on-device) models — GGUF via llama.cpp

Open Chat can run small language models **entirely on the phone**: download
once, chat offline afterwards. The engine is [llama.cpp](https://github.com/ggml-org/llama.cpp),
pinned at tag **b10941**, compiled per-ABI into `libllamajni.so`
(arm64-v8a / armeabi-v7a / x86_64, CPU + Vulkan GPU offload + mmap,
4096-token context).

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
- Reasoning output: thinking models (DeepSeek R1 distills, Qwen3, SmolLM3)
  emit a `<think>…</think>` phase — `ThinkFilter` hides it and streams only
  the final answer. Non-thinking output passes through unchanged; if the
  token budget runs out mid-thinking, the buffered thinking text is shown so
  the answer is never silently empty.

## GPU acceleration (Vulkan)

The arm64-v8a and x86_64 builds ship the llama.cpp **Vulkan backend**
(`libggml-vulkan.so`). On devices with a Vulkan driver, all model layers are
offloaded to the GPU by default (`n_gpu_layers = 999`, llama.cpp clamps to the
real layer count), which typically gives a 2–5× speedup over CPU. Devices
without a Vulkan driver fall back to CPU transparently at load time — the
setting is an honest request, never a fake claim (§32). `armeabi-v7a` stays
CPU-only (legacy 32-bit GPUs are not worth the APK size).

## Honest limits (§32)

- **Speed** depends on the SoC and on GPU offload availability: with Vulkan,
  1–4B models are comfortable on most phones; CPU-only fallback gives
  ≈5–15 tok/s for 1–2B on mid-range, 3B+ is noticeably slower.
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
| Qwen2.5 Coder 0.5B Instruct | 0.5B | Q4_K_M | 468 MB | Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF |
| Qwen2.5 Coder 1.5B Instruct | 1.5B | Q4_K_M | 1065 MB | Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF |
| Qwen2.5 Coder 3B Instruct | 3B | Q4_K_M | 2007 MB | Qwen/Qwen2.5-Coder-3B-Instruct-GGUF |
| Qwen3 4B Instruct 2507 | 4B | Q4_K_M | 2381 MB | unsloth/Qwen3-4B-Instruct-2507-GGUF |
| Gemma 3 4B IT | 4B | Q4_K_M | 2374 MB | unsloth/gemma-3-4b-it-GGUF |
| Phi-4 Mini Instruct | 3.8B | Q4_K_M | 2376 MB | unsloth/Phi-4-mini-instruct-GGUF |
| Phi-3.5 Mini Instruct | 3.8B | Q4_K_M | 2282 MB | bartowski/Phi-3.5-mini-instruct-GGUF |
| Qwen2.5 3B Instruct | 3B | Q4_K_M | 2007 MB | Qwen/Qwen2.5-3B-Instruct-GGUF |
| SmolLM3 3B *(thinks)* | 3B | Q4_K_M | 1826 MB | unsloth/SmolLM3-3B-GGUF |
| Llama 3.2 3B Instruct | 3B | Q4_K_M | 1925 MB | bartowski/Llama-3.2-3B-Instruct-GGUF |
| Gemma 2 2B IT | 2.6B | Q4_K_M | 1629 MB | bartowski/gemma-2-2b-it-GGUF |
| Qwen3 1.7B *(thinks)* | 1.7B | Q4_K_M | 1056 MB | unsloth/Qwen3-1.7B-GGUF |
| SmolLM2 1.7B Instruct | 1.7B | Q4_K_M | 1006 MB | bartowski/SmolLM2-1.7B-Instruct-GGUF |
| Qwen2.5 1.5B Instruct | 1.5B | Q4_K_M | 1065 MB | Qwen/Qwen2.5-1.5B-Instruct-GGUF |
| Llama 3.2 1B Instruct | 1B | Q4_K_M | 770 MB | bartowski/Llama-3.2-1B-Instruct-GGUF |
| Gemma 3 1B IT | 1B | Q4_K_M | 768 MB | unsloth/gemma-3-1b-it-GGUF |
| Qwen3 0.6B *(thinks)* | 0.6B | Q4_K_M | 378 MB | unsloth/Qwen3-0.6B-GGUF |
| SmolLM2 360M Instruct | 0.36B | Q8_0 | 368 MB | HuggingFaceTB/SmolLM2-360M-Instruct-GGUF |
| Qwen3 4B Thinking 2507 *(thinks)* | 4B | Q4_K_M | 2381 MB | unsloth/Qwen3-4B-Thinking-2507-GGUF |
| DeepSeek R1 Distill Qwen 1.5B *(thinks)* | 1.5B | Q4_K_M | 1065 MB | bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF |

All URLs are `https://huggingface.co/<repo>/resolve/main/<file>` and were
verified live (HTTP 200/206 + exact byte size) before being added. A JVM test
(`LocalModelsTest`) guards id uniqueness, URL shape, size sanity, the presence
of the current catalog wave, and the reasoning flags. `unsloth/*` repos are
Unsloth's well-known quantizations; the rest are the model authors' official
GGUFs or bartowski's community quants.

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
