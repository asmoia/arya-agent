# Redesign v1 changelog

## 1.2.6

Official rebuild from the signed v1.2.5 source tag with the v1.2.6 version metadata (versionCode 109, versionName 1.2.6). This release also carries the recovered model-loading, engine-lifecycle, cancellation, voice, external-routing, security and localization fixes from the review work.

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
