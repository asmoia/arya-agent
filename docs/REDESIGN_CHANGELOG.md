# Redesign v1 changelog

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
