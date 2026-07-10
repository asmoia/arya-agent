# Arya E4B — Heavy Research, Training and Implementation Delegation

این فایل برای یک ChatGPT Agent اصلی است که browser دارد و می‌تواند 6 sub-agent
موازی ایجاد کند. Bash sub-agentها اینترنت ندارد؛ برای خواندن GitHub یا مستندات
فقط از browser خود agent استفاده شود.

## مأموریت اصلی

Arya را به یک Android local-first assistant سریع تبدیل کنید که از **Gemma 4
E4B** استفاده می‌کند، اما E4B برای هر tap یا هر command ساده صدا زده نمی‌شود.

```text
deterministic compiler / executor
→ validated Action IR
→ E4B Action Adapter only on ambiguity, hard planning and recovery
→ effect safety + verifier
```

### Constraints

- **E2B ممنوع است.** هیچ architecture، fallback یا recommendation مبتنی بر E2B ندهید.
- Cloud فقط opt-in صریح کاربر است.
- Android `.litertlm` inference artifact است؛ training روی checkpoint
  `google/gemma-4-E4B-it` انجام می‌شود.
- هیچ token، secret، password، session، phone number یا data شخصی در output نباشد.
- ادعای benchmark بدون device/run evidence ممنوع است.
- هر finding باید current-source evidence داشته باشد؛ snapshot/release قدیمی را
  با HEAD فعلی قاطی نکنید.

## Browser sources to inspect

با browser این‌ها را بخوانید:

```text
https://github.com/asmoia/arya-agent
https://huggingface.co/google/gemma-4-E4B-it
https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
https://github.com/huggingface/peft
https://github.com/huggingface/transformers
https://ai.google.dev/gemma/docs
```

به‌طور خاص بررسی کنید:

```text
TaskFlowController
TaskOrchestrator
HermesAgentService
HermesAppKeeper
LocalModelRuntime
LocalRuntimePolicy
LocalLlmClient
TaskPerfTrace
ToolRegistry
SensitiveActionGate
ClawAccessibilityService
PersianCommandCompiler
PlanExecutor
ScreenDsl
```

---

# تقسیم کار 6 sub-agent

## Agent A — Current-main runtime audit

### Deliverable

```text
CURRENT_MAIN_RUNTIME_AUDIT.md
```

### کار سنگین

- current commit SHA را ثبت کند.
- call graph دقیق بسازد:

```text
TaskFlow → AppViewModel → TaskOrchestrator → AgentService → Hermes/Default
→ LocalLlmClient / ChatSessionController / LocalModelRuntime
→ EngineHolder / Conversation
```

- تمام generation boundaryها را با file/function/line پیدا کند.
- hidden generation fan-out را با static proof نشان دهد.
- lifecycle/lease/cancel/timeout/foreground ownership را audit کند.
- stale claims را جداگانه با label `WITHDRAWN` یا `UNVERIFIED` بنویسد.
- PR-1a / PR-1b / PR-2 sequence را با file-level diff plan بدهد.

### کیفیت

حداقل 3 diagram متنی و 20 citation file/function داشته باشد.

---

## Agent B — E4B fine-tuning / PEFT compatibility research

### Deliverable

```text
E4B_TRAINING_COMPATIBILITY_REPORT.md
```

### کار سنگین

- Gemma 4 E4B config، multimodal/text architecture، `Gemma4ClippableLinear`
  و `Linear4bit` paths را بررسی کند.
- دلیل دقیق خطاهای Colab زیر را تحلیل کند:

```text
prepare_model_for_kbit_training fp32 OOM
Gemma4ClippableLinear unsupported by PEFT
LoRA parameter has no gradient
No inf checks were recorded for optimizer
```

- سه strategy عملی مقایسه کند:

```text
1. text-only direct Linear4bit LoRA
2. custom PEFT wrapper for Gemma4ClippableLinear if truly needed
3. prompt/prefix tuning fallback on E4B
```

- برای هر strategy بگوید:

```text
compatibility risk
VRAM need
Colab feasibility
code changes
how to detect success before long training
```

- یک recommendation نهایی بدهد؛ بدون حدس و با source links.

---

## Agent C — High-value Action IR training corpus

### Deliverable

```text
arya_action_train_1000.jsonl
DATASET_QUALITY_REPORT.md
```

### کار سنگین

حداقل **1000 row** Action IR تولید کند، نه 20 مثال.

Distribution حداقلی:

```text
250 deterministic / device / navigation / browser
250 messaging / draft / commit / confirmation
150 Persian casual / typo / Finglish / mixed language
150 ambiguity / missing slots / app/contact conflicts
150 safety / refusal / prompt injection / recovery
50 screen-conditioned examples
```

### Schema contract

هر line باید این structure را داشته باشد:

```json
{
  "id": "unique-id",
  "input": {"text": "...", "locale": "fa-IR", "screen_context": null},
  "target": {
    "version": "arya.action.v1",
    "route": "execute|clarify|refuse|escalate",
    "task_type": "simple|compound|exploratory|unsafe|unknown",
    "risk_level": "low|medium|high|blocked",
    "needs_confirmation": false,
    "slots": {},
    "plan": []
  }
}
```

Rules:

```text
- clarify/refuse → plan=[]
- blocked → refuse
- external_message_commit → confirmation=true and medium/high risk
- no invented actions
- no real secrets/personal data
- unique IDs
- no chain-of-thought
```

---

## Agent D — Held-out benchmark and evaluator

### Deliverable

```text
arya_action_eval_holdout_300.jsonl
ARYA_ACTION_EVALUATION_SPEC.md
```

### کار سنگین

- 300 مثال evaluation بسازد که هیچ overlap متنی/semantic ساده با train نداشته باشند.
- شامل Persian/Finglish/English/ambiguity/safety/context باشد.
- معیارهای دقیق بدهد:

```text
JSON validity
route accuracy
slot exact/F1
plan validity
confirmation correctness
refusal correctness
safety false negative
clarify correctness
latency / native generations / RAM (runtime benchmark)
```

- rubric برای pass/fail adapter قبل از packaging بسازد.

---

## Agent E — P0 implementation design and test plan

### Deliverable

```text
P0_IMPLEMENTATION_PLAN.md
```

### کار سنگین

برای P0های پذیرفته‌شده plan code-level بدهد:

```text
P0-A: trace handle + native generation characterization + coalescing
P0-B: KeepAlive lifecycle remaining AK-3..AK-6
P0-C: ResolvedEffect prepare/confirm/commit
```

برای هر P0:

```text
- exact current files/functions
- smallest safe PR split
- tests
- acceptance gates
- rollback risk
- no behavior claim without test
```

Contractهای مهم:

```text
AGENT_OUTER native_attempts == outer_rounds + tagged retries (after coalescing)
no raw sensitive content in default trace
PreparedAction must not mutate
single-use grant
stale revision/package/window fail closed
terminal typed once
```

---

## Agent F — Mobile deployment and LiteRT packaging plan

### Deliverable

```text
E4B_ADAPTER_MOBILE_DEPLOYMENT_PLAN.md
```

### کار سنگین

- مسیر دقیق artifactها را توضیح دهد:

```text
base E4B Transformers checkpoint
+ adapter checkpoint
→ evaluation
→ merge or adapter loading decision
→ LiteRT-compatible conversion/package
→ Android model manager
→ adaptive Full/Balanced/Compact memory test
```

- تفاوت train artifact و `.litertlm` inference artifact را دقیق بنویسد.
- adapter packaging options و risks را بگوید.
- Android benchmark plan بدهد:

```text
cold/warm
Full/Balanced/Compact
GPU backend
RAM available
thermal state
first action
native generation count
success/verifier evidence
```

- هیچ conversion command را production-ready اعلام نکند مگر source رسمی/verified داشته باشد.

---

# Parent-agent integration task

بعد از تکمیل 6 خروجی، parent agent باید:

1. duplicate/stale findingها را حذف کند؛
2. dataset را schema-check منطقی کند؛
3. train/eval overlap را بررسی کند؛
4. 10 ریسک بزرگ را rank کند؛
5. یک فایل نهایی بسازد:

```text
ARYA_E4B_MASTER_PLAN.md
```

شامل:

```text
- architecture final
- training readiness
- GPU/Colab decision
- data readiness
- code PR order
- mobile packaging gates
- benchmark gates
- explicit unknowns
```

## خروجی نهایی که باید به developer برگردد

فقط این فایل‌ها:

```text
CURRENT_MAIN_RUNTIME_AUDIT.md
E4B_TRAINING_COMPATIBILITY_REPORT.md
arya_action_train_1000.jsonl
DATASET_QUALITY_REPORT.md
arya_action_eval_holdout_300.jsonl
ARYA_ACTION_EVALUATION_SPEC.md
P0_IMPLEMENTATION_PLAN.md
E4B_ADAPTER_MOBILE_DEPLOYMENT_PLAN.md
ARYA_E4B_MASTER_PLAN.md
```

فایل‌ها را مستقیم به developer attach کنید. Parent agent نباید بدون validation، dataset یا adapter را production-ready اعلام کند.
