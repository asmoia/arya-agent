# Arya redesign — progress log

Branch: `redesign/v1`  
Source: `asmoia/arya-agent` main (v0.4.0-era snapshot, ~28.5k Kotlin lines)  
Constraint: source-only. No Gradle Android/NDK assemble, no APK compile/decompile.

## Pins recorded

- llama.cpp FetchContent tag: **`b10566`** (ggml-org/llama.cpp, 2026-08).  
  Deviation: earlier pin `v0.2.0` is an ancient 2023 tag; CI `assembleDebug` failed (`llama_model_load_from_file` / `llama_vocab` missing). JNI matches the modern C API.
- Repository: `https://github.com/ggml-org/llama.cpp`
- JSON protocol: `org.json` (already on Android). Deviation from S1 “kotlinx-serialization”: avoided a new plugin/dep; same APK, same JSON contract.

## Time

Timer started in `/tmp/arya_t0`. See `WORKLOG.md`. F1–F8 session T0=`1787513474`. Minimum 2 h before calling this work-order done.

---

## F1–F8 user-test failure fixes (2026-08-23)

Commits on `redesign/v1`: `[F1]` … `[F8]`. Version **1.2.2 / 105**. Source-only; signed APK = tag `v1.2.2` → Actions. See `USER_ACTION_REQUIRED.md`.

### Acceptance greps (pasted)

```
$ grep -n "GIT_TAG" app/src/main/cpp/CMakeLists.txt
13:    GIT_TAG        b10603

$ grep -rn "getMemoryInfo" app/src/main/java/io/agents/arya/engine/
app/src/main/java/io/agents/arya/engine/EngineCore.kt:306:        am.getMemoryInfo(info)
app/src/main/java/io/agents/arya/engine/budget/DeviceProfileManager.kt:100:        am.getMemoryInfo(info)
app/src/main/java/io/agents/arya/engine/budget/DeviceProfileStore.kt:56:        am.getMemoryInfo(info)

$ grep -n "RECORD_AUDIO" app/src/main/AndroidManifest.xml
6:    <uses-permission android:name="android.permission.RECORD_AUDIO" />

$ grep -n "tryAwaitRelease" app/src/main/java/io/agents/arya/ui/chat/ui/InputBar.kt
91:                                val released = tryAwaitRelease()

$ grep -n "Qwen3" app/src/main/java/io/agents/arya/agent/llm/LocalModelManager.kt
78:     * Built-in catalog: Qwen3 Q4_K_M GGUF only (bartowski, verified 2026-08-23).
79:     * Official Qwen/Qwen3-0.6B-GGUF Q4_K_M path 404s; bartowski filenames are the
80:     * ones already on device (do not rename Qwen_Qwen3-0.6B-Q4_K_M.gguf).
86:            displayName = "Qwen3 0.6B (very light)",
87:            url = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf",
88:            fileName = "Qwen_Qwen3-0.6B-Q4_K_M.gguf",
94:            displayName = "Qwen3 1.7B (default)",
95:            url = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
96:            fileName = "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
102:            displayName = "Qwen3 4B Instruct 2507",
103:            url = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
104:            fileName = "Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
118:        // GGUF (Qwen3) is the supported local format. Never retire a valid GGUF.

$ grep -rn "totalMemory()" app/src/main/java/io/agents/arya/engine/
(empty — PASS)
```

HEAD-verified GGUF sizes: 484220320 / 1282439584 / 2497280736.

---

## Live lab (GitHub Actions only) — 2026-08-24

T0 `/tmp/arya_lab_t0`. Workflows: `.github/workflows/live-lab.yml`, `.github/workflows/smoke.yml` (dispatch `mode=lab` for tmate+puppet). Branch `lab-control` drives puppet commands.

### Reproduction (API 31 x86_64 emulator, run 32704776544 / artifact `local-smoke`)

```
SMOKE_CHAT_SENT t=1787559558
SMOKE_FAIL no LAB_FIRST_TOKEN within 90s
adb shell dumpsys meminfo io.agents.arya:engine
  → No process found for: io.agents.arya:engine   (before AND after chat)
adb shell ps -A | grep arya
  → only io.agents.arya (main), RSS ~207 MB
```

Screenshot `lab-artifacts/smoke.png`: home UI **“Choose a model / Download Qwen3 to chat offline”**.

True cause of *this* run (not yet a native mmap failure):

1. Implicit `am broadcast -a io.agents.arya.DEBUG_TASK` did not activate local config (no DebugTaskReceiver lines in logcat).
2. Model file was not visible to `ModelSession.resolve` → `NeedsSetup` → `ChatRuntime.send` never called → `:engine` never spawned → RSS cannot grow.

Follow-up failures while tightening the harness:

- `run-as` cannot read `/sdcard` on API 31 (`cp: bad '/sdcard/…gguf': Permission denied`).
- `handleDebugProbe` was referenced before the function existed (compile break, fixed).

Fixes in tree: explicit `-n io.agents.arya/.debug.DebugTaskReceiver`, `adb push` into app external-files `models/`, `findAnyGguf()` heal, `EngineLog` rolling file + debug ZIP, `DEBUG_TASK --es chat`.

### Proof — smoke PASS (run [32708352655](https://github.com/asmoia/arya-agent/actions/runs/32708352655), HEAD `4a08473`)

```
SMOKE_PASS first_token_s=19

u0_a105  1701  ... S io.agents.arya
u0_a105  2246  ... S io.agents.arya:engine     # RSS 744468 KB after load

meminfo :engine BEFORE: No process found
meminfo :engine AFTER:  TOTAL PSS 662358 KB / RSS 746632 KB  (Other mmap 467083 KB ≈ GGUF)

08-24 09:01:25.016 I/DebugTaskReceiver(1701): debug chat LOCAL path=.../Qwen_Qwen3-0.6B-Q4_K_M.gguf
08-24 09:01:25.022 I/LocalLlmClient(1701): ensureLoaded .../Qwen_Qwen3-0.6B-Q4_K_M.gguf
08-24 09:01:25.039 I/ActivityManager(535): Start proc 2246:io.agents.arya:engine ... EngineService
08-24 09:01:25.378 I/AryaEngineLog(2246): EngineService onCreate pid=2246
08-24 09:01:25.405 I/AryaEngineLog(2246): EngineService requestLoad id=1 ... cb=true
08-24 09:01:25.542 I/AryaEngineJNI(2246): Loading model: .../Qwen_Qwen3-0.6B-Q4_K_M.gguf n_ctx=1024 n_threads=4
08-24 09:01:26.613 I/AryaEngineLog(2246): EngineCore nativeLoadModel done handle=... ms=1067
08-24 09:01:42.282 I/AryaEngineLog(2246): EngineService LAB_FIRST_TOKEN id=1 chars=7
```

Same PASS on [32708327358](https://github.com/asmoia/arya-agent/actions/runs/32708327358) (`b037020`). Artifacts: `test-logs/lab-smoke-pass/`. `smoke.yml` now runs this on every `redesign/v1` push.

---

## Phase A1 — `:engine` process + AIDL + EngineService

**Accept (source-ready; device items → USER ACTION REQUIRED):**

- [x] `IEngine.aidl` / `IEngineCallback.aidl` match S1
- [x] `EngineService` isolated `android:process=":engine"`, FGS specialUse
- [x] `EngineCore` one-model / one-generation invariant + MemoryBudget refuse
- [x] `EngineClient` is the only AIDL consumer (DeathRecipient, crash-loop breaker)
- [x] JNI: cancel, state save/load, countTokens, UTF-8 guard, deadlines, delta mode
- [x] CMake target `arya-engine`; ABIs `arm64-v8a` + `x86_64`; llama.cpp `b10566`
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

- [ ] TIME GATE still FAIL until ≥14400s. Do not treat the project as finished.
- [x] Phases A1–E4 tagged (`phase-a1-done` … `phase-e4-done`)
- [x] Voice state-machine tests green; OverlayHost consumes `start_voice`; shared `VoiceCapture`
- [x] Overlay sheet source delivered; device proof USER ACTION
- [ ] S10 — see below
- [x] WORKLOG consistent with commits
- [ ] Final APK: produced by GitHub Actions `release.yml` on tag `v1.1.0` (USER ACTION if CI red)

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

