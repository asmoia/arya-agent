# ARCHITECTURE.md — Arya Agent (آریا) Target Architecture

## 1. System Process Architecture

Arya uses a strict **two-process architecture** to isolate heavy native inference and safeguard the main UI process against native memory crashes or OOMs.

```
+-------------------------------------------------------------------+
|                        Main Process (:main)                       |
|                                                                   |
|  +-----------------------+     +-------------------------------+  |
|  | ComposeChatActivity   |     | AssistantOverlaySheet / Floating |
|  +-----------+-----------+     +---------------+---------------+  |
|              |                                 |                  |
|              v                                 v                  |
|  +-------------------------------------------------------------+  |
|  |                       ChatRuntime                           |  |
|  +-----------------------------+-------------------------------+  |
|                                |                                  |
|                                v                                  |
|  +-------------------------------------------------------------+  |
|  |                    TaskSessionStore                         |  |
|  |            (Single Source of Task State)                    |  |
|  +-----------------------------+-------------------------------+  |
|                                |                                  |
|                                v                                  |
|  +-------------------------------------------------------------+  |
|  |                    EngineClient (Binder)                    |  |
|  +-----------------------------+-------------------------------+  |
+--------------------------------|----------------------------------+
                                 |  AIDL (IPC)
                                 v
+-------------------------------------------------------------------+
|                       Engine Process (:engine)                    |
|                                                                   |
|  +-------------------------------------------------------------+  |
|  |                      EngineService                          |  |
|  +-----------------------------+-------------------------------+  |
|                                |                                  |
|                                v                                  |
|  +-------------------------------------------------------------+  |
|  |                      EngineCore                             |  |
|  |   - Mutex (Single Generation Invariant)                        |  |
|  |   - MemoryBudget / DeviceProfile check                         |  |
|  |   - Prefix/State Cache (llama_state_save_file/load_file)       |  |
|  +-----------------------------+-------------------------------+  |
|                                |                                  |
|                                v                                  |
|  +-------------------------------------------------------------+  |
|  |                  JNI Bridge (libarya-engine.so)             |  |
|  |   - llama.cpp native inference                                 |  |
|  |   - Atomic cancel flag                                         |  |
|  |   - UTF-8 boundary detokenization guard                        |  |
|  +-------------------------------------------------------------+  |
+-------------------------------------------------------------------+
```

---

## 2. AIDL Contract

`IEngineCallback.aidl`:
```aidl
package io.agents.arya.engine;

oneway interface IEngineCallback {
    void onDelta(int requestId, String textDelta);
    void onDone(int requestId, String statsJson);
    void onError(int requestId, int code, String message);
    void onLoadProgress(int pct, String phase);
}
```

`IEngine.aidl`:
```aidl
package io.agents.arya.engine;

import io.agents.arya.engine.IEngineCallback;

interface IEngine {
    void registerCallback(IEngineCallback cb);
    String ensureLoaded(String modelPath, int ctxSize, int nThreads);
    int generate(String requestJson);
    void cancel(int requestId);
    String stats();
    void unload();
    boolean savePrefixState(String key);
    boolean loadPrefixState(String key);
    int countTokens(String text);
}
```

---

## 3. Task Session State Machine (TaskSessionStore)

```
IDLE ──start(task)──▶ ROUTING ──routed──▶ EXECUTING(stepIndex, stepDesc)
ROUTING ──tier1Done──▶ FINISHED(result)
EXECUTING ──needsConfirm──▶ CONFIRM_PENDING(action)
CONFIRM_PENDING ──approve──▶ EXECUTING   │──deny──▶ CANCELLED(byUser=true)
EXECUTING ──stepDone──▶ EXECUTING(step+1) │──done──▶ FINISHED(result)
ANY-NON-TERMINAL ──requestStop()──▶ STOPPING ──stopped──▶ CANCELLED
ANY-NON-TERMINAL ──error──▶ FAILED(reason, lastStep)
(process death) ──restore──▶ FAILED(reason="restored", lastStep)
```

Transitions are persisted synchronously to MMKV (`task.snapshot`). On process relaunch after crash or kill, non-terminal states restore automatically to `FAILED("restored")`.

---

## 4. Perceived Speed & Caching

- **Prefix Cache (S4)**: Static system prompts are prefilled and snapshotted using `llama_state_save_file`. Warm requests load prefill state in milliseconds skipping multi-second CPU prefill.
- **StreamAssembler (S5)**: Consumes native token deltas and performs stop-string holdback, Qwen ChatML `<tool_call>` detection, and UTF-8/Persian combining character protection.
