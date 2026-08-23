# Redesign v1 changelog

## Engine
- Isolated `:engine` process, AIDL `IEngine` / `IEngineCallback`
- llama.cpp **v0.2.0**, `libarya-engine.so`, arm64 + x86_64
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
