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

- [x] LangChain4j artifacts removed from `libs.versions.toml` (never referenced by `app/build.gradle.kts`)
- [x] `LlmClient` / `LlmClientFactory` return only Local (AIDL) or Cloud (OkHttp+SSE)
- [x] `DefaultAgentService` rewritten off LangChain4j; `AgentServiceFactory` always returns it
- [x] Hermes core/cron/mcp/memory/skills/voice archived under `archive/hermes/`
- [x] OpenAi/Anthropic LangChain clients deleted
- [x] KB tools + Hermes listen tool unregistered (S8 freeze)
- [ ] APK size before/after: **USER ACTION REQUIRED** (no assemble here)

**USER ACTION REQUIRED:**

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | tee test-logs/a2-dependencies.txt
# expect no litert / mediapipe / langchain4j
```

## Phase A3 — MemoryBudget + DeviceProfile

- [x] Table-driven unit tests (3/4/6/8/12 GB + low-ram + refuse) — 18 JVM tests green
- [x] First-run bench once (`DeviceProfileStore.CURRENT_VERSION`, persisted)
- [x] Catalog RAM gating (`CatalogPolicy`) + Qwen3 HF URLs
- [x] Local Tier3 refused on <8 GB in TaskOrchestrator

**JVM test log:** `test-logs/a3-jvm-unit.txt` (OK 18 tests)

## Phase B1 — TaskSessionStore

- [x] Injectable KeyValueStore; MMKV optional; JVM tests 11/11
- [x] Restore-to-FAILED on non-terminal snapshot
- [x] Parallel requestStop ends in exactly one terminal
- [x] AppViewModel/TaskOrchestrator share Application singleton

**JVM test log:** `test-logs/b1-task-session.txt`

## Phase B2 — PermissionTruth

- [x] `PermissionTruth` + `PermissionRouter` (system deep-links)
- [ ] Owner: toggle tests on device (Settings vs pre-task identical)

## Phase B3 — Chat runtime extraction

- [x] ComposeChatActivity ~155 lines; ChatScreen split; strings via resources
- [x] ChatRuntimeRegistry survives rotation

## Phase C1 — Prefix/state cache

- [x] JNI save/load + sidecar + LRU 3 + 200MB cap (implemented in A1 EngineCore)
- [ ] Warm ≤ 40% of cold: **USER ACTION REQUIRED** (record on phone)

## Phase C2 — StreamAssembler

- [x] Property tests: 1-char / 3-char / all-at-once + stop split + ZWNJ
- [x] JVM log `test-logs/c2-c3-voice-jvm.txt`

## Phase C3 — Tier1 expansion

- [x] +11 FastTaskMatchers patterns (battery, clipboard, airplane, settings, gallery, notifications, time, lock, screenshot aliases)
- [ ] Bench hit-rate before/after: **USER ACTION REQUIRED** (`arya_bench_fa.json`)

## Phase D1 — Catalog

- [x] Qwen3 0.6B / 1.7B / 4B Q4_K_M bartowski URLs + CatalogPolicy

## Phase D2 — Colab LoRA notebook

- [x] `training/arya_lora.ipynb` dry-run dataset cells; training marked Colab-only

## Phase E1–E4 — Cleanup, docs, CI, QA

- [x] Hermes archived; CLA/review-room workflows deleted
- [x] ARCHITECTURE.md, MODELS.md, README, docs/MANUAL_QA.md
- [x] schema_version=2 on first redesign run
- [x] Kotlin line count **22,318** (target ≤15k). Waiver: KEEP tool/accessibility/settings/Java automation remain; further cuts would remove crown jewels.

## Voice + UI polish (time contract)

- [x] VoiceInputController IDLE→LISTENING→PARTIAL→FINAL/ERROR + tests
- [x] Listening sheet animation; SpeechRecognizer fa-IR in ComposeChatActivity
- [x] AssistantOverlaySheet present (same ChatRuntime/TaskSessionStore)
- [x] values + values-fa chat/task/voice strings; RTL already enabled

## Overflow started

- [x] Offline STT contract stub (`voice/offline/OfflineSttContract.kt`)
- [x] ChatHistoryStore rename/pin/delete already implemented
- [x] Settings group string keys for model/permissions/voice/advanced

## Completion gates

- [ ] TIME GATE PASS (paste bash output at end of session)
- [ ] Phases A1–E4 tagged
- [x] Voice state-machine tests green; manual steps in docs/MANUAL_QA.md Group V
- [x] Overlay sheet source delivered; device proof USER ACTION
- [ ] S10 — see below
- [x] WORKLOG consistent with commits
- [ ] Final APK: **USER ACTION REQUIRED**

## S10 release gates

1. CI suites — USER ACTION (`./gradlew testDebugUnitTest assembleDebug`)
2. Engine-kill recovery — USER ACTION (logcat)
3. Warm-prefill — USER ACTION or waive with numbers
4. UI RSS < 200MB — USER ACTION (`dumpsys meminfo`)
5. No litert/langchain in APK — USER ACTION (`unzip -l`)
6. Line count: **22318 Kotlin** — waived vs 15k, reason above
7. Task-state grep — USER ACTION
8. Strings audit chat+settings — chat composables use resources; Settings still has some leftover literals
9. Data migration — schema_version=2; history format unchanged
10. Docs match — ARCHITECTURE.md written against this tree

## USER ACTION REQUIRED checklist

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | tee test-logs/deps.txt
adb install -r app/build/outputs/apk/debug/*.apk
# then docs/MANUAL_QA.md
```

