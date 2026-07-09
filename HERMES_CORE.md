# Arya Embedded Hermes Core

## Goal

Ship a **self-contained** Hermes-style agent brain inside the Arya APK.

- **No Termux**
- **No external `hermes gateway` process**
- Full phone tools stay on-device (Accessibility, notifications, EMUI, Shamsi, …)
- Learning loop: memory + skills improve across sessions

Upstream reference: [NousResearch/hermes-agent](https://github.com/nousresearch/hermes-agent)  
We port **architecture and contracts**, not the full Python monorepo.

## Why not embed CPython / Termux?

| Approach | Pros | Cons |
|---|---|---|
| Termux + Hermes CLI | Full upstream parity | Extra app, fragile lifecycle, user friction |
| CPython-for-Android | Reuse Python code | Huge APK, NDK hell, OEM kill risk |
| **Kotlin-native Hermes core** | Single APK, battery/lifecycle control, same tools | Port subset of features; evolve iteratively |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Arya APK                            │
│  UI / Accessibility / Notifications / ToolRegistry          │
│                           │                                 │
│                           ▼                                 │
│                 HermesAgentService (AgentService)           │
│                           │                                 │
│         ┌─────────────────┼─────────────────┐               │
│         ▼                 ▼                 ▼               │
│   HermesLoop        HermesMemory       HermesSkills         │
│   (observe→think    (profile +         (procedural MD       │
│    →act→learn)       episodic FTS)      library + improve)  │
│         │                 │                 │               │
│         └────────────┬────┴─────────────────┘               │
│                      ▼                                      │
│              HermesSessionStore (SQLite)                    │
│                      │                                      │
│                      ▼                                      │
│         LlmClient (Local LiteRT / OpenAI / Anthropic)       │
└─────────────────────────────────────────────────────────────┘
```

## Modules (`io.agents.arya.agent.hermes`)

| Package | Role | Hermes analogue |
|---|---|---|
| `core/HermesAgentService` | `AgentService` impl, main loop | `AIAgent` + `conversation_loop` |
| `core/HermesLoopState` | iteration budget, stuck, cancel | `IterationBudget`, turn state |
| `core/HermesContextCompressor` | history compression | `context_compressor` |
| `core/HermesPromptBuilder` | system prompt + memory + skills | prompt assembly |
| `memory/HermesMemoryStore` | `MEMORY.md` + episodes | `MemoryManager` + vault |
| `skills/HermesSkillStore` | skill MD files + match/improve | skills hub / skill_manage |
| `session/HermesSessionStore` | session + messages SQLite | `SessionDB` |
| `tools/*` | hermes meta-tools (memory/skill) | `memory`, `skill_*` tools |
| `HermesBridgeService` | **deprecated** external HTTP bridge | old Termux path |

## Feature map (v0.2 target)

### Must ship now
- [x] In-process agent loop with phone tools
- [x] Persistent user memory (profile + episodes)
- [x] Skill library (load / match / write / improve)
- [x] Session store (resume last session)
- [x] Context compression when history grows
- [x] Post-turn learning nudge (memory + skill draft)
- [x] Factory wiring: `hermesEnabled → HermesAgentService`
- [x] Persian «آریا» identity in Hermes system prompt
- [ ] Settings UI toggle (next commit if needed via KV)

### Later
- [ ] Cron / scheduled automations
- [ ] Subagent delegation
- [ ] MCP client (connect external tool servers)
- [ ] Full FTS5 parity + Honcho-style user model
- [ ] OpenAI-compatible local API server inside app (for desktop clients)

## Config

`AgentConfig`:
- `hermesEnabled: Boolean` — use embedded Hermes core
- `hermesUrl` / `hermesApiKey` — **legacy only** (external bridge); ignored by embedded core

KV keys (via `KVUtils` or dedicated prefs):
- `hermes.embedded.enabled`
- `hermes.memory.enabled`
- `hermes.skills.enabled`
- `hermes.learning.enabled`

## Data layout (app files dir)

```
files/hermes/
  MEMORY.md                 # curated long-term profile
  episodes/YYYY-MM-DD.md    # daily episodic notes
  skills/*.md               # procedural skills
  sessions.db               # SQLite sessions + messages
```

## Safety

- Path traversal blocked for skill/memory writes
- Learning loop never auto-executes shell / dangerous tools
- Phone tools still go through existing guards (`DirectDeviceDataGuard`, …)
- Learning writes are append-only with size caps

## Activation

```kotlin
// AgentServiceFactory
fun create(config: AgentConfig): AgentService =
    if (config.hermesEnabled) HermesAgentService() else DefaultAgentService()
```

Default for Arya builds: prefer enabling Hermes when building the Persian product path; keep `DefaultAgentService` as fallback.

## Non-goals for this milestone

- Full port of `run_agent.py` (6k lines) and gateway platforms
- Browser/computer-use desktop tools
- Kanban multi-agent swarm
