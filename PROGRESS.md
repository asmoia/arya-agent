# Arya redesign — progress log

Branch: `redesign/v1`  
Source: `asmoia/arya-agent` main (v0.4.0-era snapshot, ~28.5k Kotlin lines)  
Constraint: source-only. No Gradle Android/NDK assemble, no APK compile/decompile.

## Pins recorded

- llama.cpp FetchContent tag: **`v0.2.0`** (stable semantic release, 2026-08-21; nightly sibling `b10566`)
- Repository: `https://github.com/ggml-org/llama.cpp`
- JSON protocol: `org.json` (already on Android). Deviation from S1 “kotlinx-serialization”: avoided a new plugin/dep; same APK, same JSON contract.

## Time

Timer started in `/tmp/arya_t0`. See `WORKLOG.md`.

---

## Phase A1 — `:engine` process + AIDL + EngineService

**Accept (source-ready; device items → USER ACTION REQUIRED):**

- [x] `IEngine.aidl` / `IEngineCallback.aidl` match S1
- [x] `EngineService` isolated `android:process=":engine"`, FGS specialUse
- [x] `EngineCore` one-model / one-generation invariant + MemoryBudget refuse
- [x] `EngineClient` is the only AIDL consumer (DeathRecipient, crash-loop breaker)
- [x] JNI: cancel, state save/load, countTokens, UTF-8 guard, deadlines, delta mode
- [x] CMake target `arya-engine`; ABIs `arm64-v8a` + `x86_64`; llama.cpp `v0.2.0`
- [x] `bitnet_jni.cpp` removed (bridge lives in `engine_jni.cpp`)
- [x] `LocalLlmClient` talks only to `EngineClient`

**USER ACTION REQUIRED (device):**

```bash
# After assembleDebug on a phone:
adb shell ps -A | grep arya          # expect io.agents.arya and io.agents.arya:engine
adb shell kill -9 <engine-pid>       # UI must survive; next generate rebinds
# Concurrent generate → ERR_BUSY (code 1)
```

---

## Phase A2 — Delete LiteRT-LM + LangChain4j

- [ ] No litert/mediapipe/langchain4j artifacts
- [ ] `LlmClientFactory` returns Local (AIDL) or Cloud (OkHttp+SSE)
- [ ] DefaultAgentService ported off LangChain4j

## Phase A3 — MemoryBudget + DeviceProfile

- [ ] Table-driven unit tests (3/4/6/8/12 GB)
- [ ] First-run bench once

## Phase B1 — TaskSessionStore

- [ ] Single source of truth; illegal-transition policy
- [ ] Restore-to-FAILED

## Phase B2 — PermissionTruth

- [ ] Settings + pre-task gating share one store

## Phase B3 — Chat runtime extraction

- [ ] Activity ≤ 300 lines; no user-facing literals in ChatScreen

## Phase C1 — Prefix/state cache

- [ ] Warm first-token target ≤ 40% of cold (measure on device)

## Phase C2 — StreamAssembler

- [ ] Chunk-boundary property tests

## Phase C3 — Tier1 expansion

- [ ] ≥10 new compiler patterns + bench hit-rate

## Phase D1 — Catalog

- [ ] Qwen3 0.6B / 1.7B / 4B Q4_K_M + custom URL; RAM gating

## Phase D2 — Colab LoRA notebook

- [ ] `training/arya_lora.ipynb` dry-run cells

## Phase E1–E4 — Cleanup, docs, CI, QA

- [ ] Archive frozen modules; docs; CI prune; S10 gates

## Voice + UI polish (time contract)

- [ ] SpeechRecognizer fa-IR state machine + listening sheet + TTS toggle
- [ ] Assistant overlay sheet over other apps
- [ ] i18n fa/en, themes, RTL

## Completion gates

- [ ] TIME GATE PASS
- [ ] Phases A1–E4 tagged
- [ ] Voice E2E
- [ ] Overlay sheet
- [ ] S10 gates
- [ ] WORKLOG consistent
- [ ] Final APK: **USER ACTION REQUIRED** (no assemble in this environment)
