# USER ACTION REQUIRED — Arya 1.2.2 (F1–F8)

Source is on `redesign/v1`. **Do not uninstall. Do not Clear Data.** The 0.6B GGUF already on the phone stays at:

`/storage/emulated/0/Android/data/io.agents.arya/files/models/Qwen_Qwen3-0.6B-Q4_K_M.gguf`

This tree is **source-only**. No APK was assembled here. Signed APKs must come from GitHub Actions `release.yml` using the **existing** repo keystore secrets (same key as 1.2.0 / 1.2.1).

## Security

A classic GitHub PAT was pasted in chat earlier. **Rotate it now** (it has very broad scopes, including `delete_repo`). Do not commit tokens.

## 1. Pull and tag a signed release

```bash
git fetch origin redesign/v1
git checkout redesign/v1
git log --oneline -12
# expect [F1]…[F8] then version 1.2.2 / 105

# Same keystore as existing installs — upgrade in place
git tag v1.2.2
git push origin v1.2.2
```

Watch: https://github.com/asmoia/arya-agent/actions  
Release: https://github.com/asmoia/arya-agent/releases/tag/v1.2.2

Install the signed APK **over** 1.2.1. Confirm package `io.agents.arya` and that the model file is still ~484 220 320 bytes.

## 2. First chat after upgrade (Huawei ADY-LX9)

1. Open Arya. Status should show **Qwen3 0.6B** (local).
2. Send a short English message (“Say hello in one sentence.”).
3. Expect a **percent loading bar** (“Loading GGUF (25%)” … “Model ready”). First mmap from emulated storage can take **30–90 s**. Later chats should be seconds.
4. If it fails, pull a new debug ZIP (**do not Clear Data**) and check:
   - `adb logcat | grep -E 'AryaEngineJNI|EngineService|LocalLlmClient|EngineClient'`
   - `adb shell ps -A | grep arya` → both `io.agents.arya` and `io.agents.arya:engine`

## 3. Microphone / hold-to-talk

1. First hold on the orb/mic → Android runtime **RECORD_AUDIO** dialog. Allow it.
2. **Press and hold ≥ 300 ms** = push-to-talk (starts on down, stops on release).
3. **Release before 300 ms** = cancel (does not send). A short tap then toggles lock-listen.
4. If you previously chose “Don’t ask again”: Arya shows a **Persian** message and **Open app settings**. Enable Microphone there.
5. Huawei without Google app may still return SpeechRecognizer `ERROR_CLIENT`. The hold UI is fixed; STT quality on EMUI is a remaining device risk.

## 4. What these eight commits actually changed

| ID | Commit | What was wrong | What is in the tree |
|---|---|---|---|
| F1 | pin llama.cpp **b10603** | b3800 cannot load Qwen3 | `CMakeLists.txt` `GIT_TAG b10603`; JNI matches b10603 `llama_state_*` / penalties / batch |
| F2 | device RAM | JVM heap (~256 MB) used as RAM | `ActivityManager.getMemoryInfo` (`totalMem`/`availMem`) in EngineCore + DeviceProfileManager |
| F3 | async load | sync binder mmap + bench → ANR/timeout | `oneway requestLoad` + `onLoadResult`; 120 s client timeout; % indicator |
| F4 | bind | poll loop | `onServiceConnected` resumes coroutine; **start service before bind**; 15 s |
| F5 | mic permission | no runtime request | `RequestPermission` before listen; Persian + settings deep-link on permanent deny |
| F6 | hold-to-talk | mic was `onClick` | `detectTapGestures` + `tryAwaitRelease`; overlay mic too; cancel if < 300 ms |
| F7 | callbacks | one field overwritten | `ConcurrentHashMap` by `requestId`, dropped on done/error/cancel |
| F8 | catalog | Qwen3 not actually in LocalModelManager | three bartowski Qwen3 Q4_K_M URLs, exact sizes, RAM 3/4/8; no BitNet entry |

## 5. What we will **not** do

- Do not generate a new keystore.
- Do not uninstall / Clear Data (wipes the 0.6B download).
- Do not pin llama.cpp back to `b3800` or `v0.2.0`.
