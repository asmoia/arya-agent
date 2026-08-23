# Arya architecture (redesign/v1)

This document describes the **actual** tree after the redesign. It replaces the old reconstruction notes.

```
┌───────────────────────── MAIN PROCESS (io.agents.arya) ─────────────────────────┐
│  UI (Compose)                                                                    │
│    ComposeChatActivity ── ChatScreen (stateless)                                 │
│    SettingsActivity / LlmConfigActivity / ThemeActivity                          │
│                                                                                  │
│  Runtime                                                                         │
│    ChatRuntime + ChatHistoryStore + ChatRuntimeRegistry                          │
│    TaskSessionStore   — THE task state machine (MMKV snapshot)                   │
│    TaskOrchestrator   — route → execute → observe → finish                       │
│    PipelineRouter     — Tier1 compiler/matchers → skills → agent loop            │
│    PermissionTruth + PermissionRouter                                            │
│    DefaultAgentService — single agent loop over LlmClient                        │
│                                                                                  │
│  Tools                                                                           │
│    ToolRegistry + Accessibility / Notification / SensitiveActionGate             │
│                                                                                  │
│  LLM                                                                             │
│    LlmClient ── LocalLlmClient (EngineClient AIDL)                               │
│              ── CloudLlmClient (OkHttp + SSE, OpenAI/Anthropic)                  │
│    StreamAssembler ── tool-call / stop-string / think-tag parser                 │
└───────────────┬─────────────────────────────────────────────────────────────────┘
                │ AIDL IEngine / IEngineCallback (oneway deltas)
┌───────────────▼──────────── ENGINE PROCESS (:engine) ───────────────────────────┐
│  EngineService (FGS specialUse)                                                  │
│    EngineCore — one model, one generation, MemoryBudget refuse                   │
│    DeviceProfileManager — first-run bench                                        │
│    PrefixCache — llama.cpp state save/load                                       │
│  libarya-engine.so  llama.cpp b10566, CPU, arm64-v8a + x86_64                    │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## Process contract

- Main process never calls `System.loadLibrary`. Only `EngineNative` (engine process) does.
- Engine crash → `DeathRecipient` → `EngineState.Crashed`. Task state survives in `TaskSessionStore`.
- 3 native crashes / 10 min for one model → quarantine until process restart.

## Task state

```
IDLE → ROUTING → EXECUTING ⇄ CONFIRM_PENDING → FINISHED
any non-terminal → STOPPING → CANCELLED
any non-terminal → FAILED
process death restore → FAILED("restored")
```

Only `TaskOrchestrator` calls `transition()`. Anyone may `requestStop()`.

## Deleted / archived

LiteRT-LM, LangChain4j, WeChat/Discord runtimes, ConfigServer, Hermes agent/cron/MCP/voice (see `archive/`).

## Future (not in v1)

Vulkan backend, speculative decoding, whisper.cpp offline STT (AIDL sketched in overflow).
