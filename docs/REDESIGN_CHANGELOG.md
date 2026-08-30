# Redesign v1 changelog

## 1.2.15

Add **FunctionGemma 270M** (bartowski Q4_K_M, 253 MB) as a real catalog model
with Gemma turn template + `<start_function_call>` parser.

Google: not a general chat model. On Huawei it is the *small enough to live*
tool-calling brain (~250 MB vs 1.5 GB that EMUI SIGKILLs). Qwen3 0.6B remains
the small *chat* option.

## 1.2.14

1.2.13 put the engine in the UI process — the whole app crashed on load.
ADY-LX9 still cannot keep 1.7B alive through generate (EMUI SIGKILL).

- Restore isolated `:engine` (UI must not die)
- Keep file-backed mmap + prefetch
- Huawei/Honor default to **Qwen3 0.6B** (~484 MB), skip 1.7B prewarm
- FunctionGemma 270M is not a chat model (Google: "not intended for dialogue")

## 1.2.13

Huawei ADY-LX9 / Kirin 9000S lab (1.2.12 signed): load **succeeded**
(`rss_mb=1475`, warmup_done) then `:engine` was **SIGKILL'd 16s into generate**.
No `native-crash.txt` — LMK, not SIGILL. 12 GB RAM, 4 GB free; isolated
anonymous 1.5 GB is what EMUI kills.

- Drop `android:process=":engine"` — infer in the foreground app process
- `LLAMA_LOAD_MODE_MMAP` + sequential prefetch of the ext4 copy (file-backed)
- Disable flash-attn; smaller n_batch/n_ubatch for first decode
- Huawei wakelock tag `LocationManagerService` (dontkillmyapp EMUI workaround)

## 1.2.12

Stop lying about local-model ready. 1.2.6–1.2.11 oscillated mmap/process/fallback
and never made Qwen3 1.7B actually resident before generate.

- `LLAMA_LOAD_MODE_NONE` again — read weights into RAM during load (1.2.5)
- Restore 1-token warmup `llama_decode` so SIGILL/OOM happens before "Writing…"
- Reject ready unless process RSS is at least 200 MB for a ≥200 MB GGUF
- Remove the 1.2.7 CMake patch that forced lazy `init_mappings(false)`
- Keep isolated `:engine` + FGS; 0.6B remains last-resort after an honest failure
- Engine FGS notification id 21001 (was 1001, colliding with ForegroundService)
- Qwen3 hard switch: prefill `<think>\\n\\n</think>\\n\\n` (official tokenizer template); drop raw `/no_think`
- Watchdog stall uses request `tokenDeadlineMs` (12s), not a hardcoded 6s

## 1.2.6

Official rebuild from the signed v1.2.5 source tag with the v1.2.6 version metadata (versionCode 110, versionName 1.2.6). This hotfix carries the recovered model-loading, engine-lifecycle, cancellation, voice, external-routing, security and localization fixes from the review work.

- Validate local files as non-trivial GGUF files before activation or native load; search external, internal and mmap-safe `models/fast` roots.
- Repair stale local model paths and prevent cloud fallback from masking a usable local model; reject model switching/unload races during generation.
- Keep request-scoped cancellation callbacks until a terminal event; harden voice model resolution and external automation payload/callback validation.
- Close global cleartext traffic and keep debug task receivers in the debug manifest only.
- Use `LLAMA_LOAD_MODE_MMAP` after the ext4 fast copy instead of the anonymous-allocation path; configure an app-specific native crash breadcrumb so a process death during weight loading is diagnosable.

## 1.2.5

Huawei 1.7B on 1.2.4 copied the GGUF off FUSE, then said ready in 3s.
`:engine` died ~8s into generate (no `LAB_FIRST_TOKEN`); the UI hung 120s.

- llama.cpp `LLAMA_LOAD_MODE_NONE` — actually read weights into RAM
- Warmup `llama_decode` during load so a crash happens *before* "Writing…"
- n_ctx cap 2048, n_batch 128 / n_ubatch 32, chunked prefill
- Do not reload the same GGUF just because the path is FUSE vs `filesDir/fast`
- Single-flight `ensureLoaded`; fail generate immediately if `:engine` dies
- Native SIGSEGV/SIGILL breadcrumb in `engine_logs/native-crash.txt`

## 1.2.2 (F1–F8)

- llama.cpp **b10603** (Qwen3-capable; do not revert to b3800)
- MemoryBudget uses `ActivityManager.MemoryInfo`, never JVM heap
- `oneway requestLoad` + `onLoadResult`; 120s timeout; % UI + FGS text
- Bind via `onServiceConnected`; startForegroundService before bind
- `RECORD_AUDIO` runtime request; Persian settings deep-link
- Hold-to-talk: `detectTapGestures` + `tryAwaitRelease` (cancel < 300ms)
- Engine callbacks keyed by `requestId`
- LocalModelManager: three bartowski Qwen3 Q4_K_M entries (exact sizes)

## Engine
- Isolated `:engine` process, AIDL `IEngine` / `IEngineCallback`
- llama.cpp **b10603**, `libarya-engine.so`, arm64 + x86_64
- Cancel, prefix state save/load, UTF-8 guard, token/request deadlines
- MemoryBudget + DeviceProfile first-run bench
- Crash-loop quarantine (3 / 10 min)

## Agent
- Single `DefaultAgentService` over `LlmClient` (no LangChain4j / LiteRT)
- `TaskSessionStore` is the only task lifecycle owner
- CatalogPolicy refuses local Tier3 under 8 GB
- FastTaskMatchers + compiler expanded (~20 new deterministic patterns)

## UI
- ChatScreen split; strings in `values` + `values-fa`
- Voice listening sheet + `VoiceInputController`
- OverlayHostActivity from floating circle
- OnboardingPermissions bound to PermissionTruth

## Removed
- LiteRT-LM, LangChain4j, Hermes loop/cron/MCP/voice (see `archive/`)
- CLA + review-room workflows
