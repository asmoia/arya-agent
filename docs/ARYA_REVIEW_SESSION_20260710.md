# Arya Architecture Review — Decision Record

> Living record for the multi-agent review session. This is intentionally a
> decision/evidence document, not a raw transcript. Update it only when a claim
> is revalidated against the current `main` source or a decision changes.

## Validation metadata

- Android-runtime findings in this record were rechecked against `a0b947d9`.
- Later commits used to host this review room change the Flask/workflow support,
  not the Android runtime. Do not mistake a room-only commit for fresh Android
  evidence.
- P0-B lifecycle and P0-C safety acceptance are accepted design directions with
  static evidence; focused reproduction tests remain required before claiming
  user-visible regression severity.

## Scope

Product goal:

```text
Persian local-first Android assistant
→ deterministic execution for explicit tasks
→ E4B only for ambiguity, planning, recovery and concise summary
```

Non-goal: make an LLM decide every UI click or promise Siri-level latency for
unknown third-party UI states.

## Evidence discipline

Every finding must state:

1. current commit and file/function;
2. reproduction or static proof;
3. user impact;
4. testable acceptance criteria.

Trace ownership rule: instrument each local generation exactly once at the
highest layer that still knows flow semantics. Current intended boundaries are
ChatSessionController direct conversation send (`CHAT`), LocalLlmClient native
send (`AGENT_OUTER`/`QUICK_CHAT`) and LocalModelRuntime.runSingleShot
(`BACKGROUND`). EngineHolder contributes runtime create/reuse/backend counters
only when correlated with a valid trace handle.

Claims from the pre-foundation/release snapshot must not be treated as current
findings without revalidation.

## Accepted P0 work

### P0-A — One outer local turn must have a measurable native-generation boundary

**Problem:** `LocalLlmClient` can send multiple native messages for fragments of
one logical outer turn. Bootstrap evidence, hints and tool results must not
silently multiply expensive E4B generations.

**Required invariants:**

```text
flow: AGENT_OUTER | QUICK_CHAT | CHAT | BACKGROUND
outer_round_count: emitted only by the Hermes AGENT_OUTER loop
native_attempt/completed/failed(reason): emitted at the native boundary
conversation_create_count
engine_recreate_count
first_tool_ms
```

PR-1a characterizes the current known bootstrap fan-out; it does not make an
incorrect equality assertion on the baseline. PR-1b makes the scoped gate:

```text
AGENT_OUTER native_attempts == outer_rounds + explicitly tagged retries
native_completed + native_failed == native_attempts
```

Parse fallback is part of the same native attempt, not a retry. Quick chat,
normal chat and background flows have independent budgets.

**PR split:**

- **PR-1a:** behavior-neutral instrumentation and a minimal native boundary
  seam/recorder; characterization tests of current fan-out, parsing failure and
  cancellation. Extend existing `agent/TaskPerfTrace.kt`; do not create a
  duplicate trace type.
- **PR-1b:** typed/deterministic `LocalTurnBundle` composition and coalescing;
  enforce one native generation per outer round except explicitly tagged retry.
  System instruction remains ConversationConfig-owned. Do not use hidden LLM
  summaries during deterministic compression.

### P0-B — Hermes foreground/lifecycle ownership must balance exactly once

**Problem:** task-start/task-end ownership must be proven for normal completion,
error, cancellation and timeout. A current-source static edge case is now
identified: the pure-chat fast path can return before any keep-alive acquire,
while outer `finally` still invokes legacy end logic; a naked atomic decrement
can leave active depth negative and suppress foreground start on the next task.
Normal agent flow also has multiple start sites with one terminal end.

**Acceptance:** one idempotent run-id lease and exactly one outer task lease
window per user-visible task. The scope exists for every task but acquires only
after the pure-chat guard; closing an unacquired scope is a no-op. For
normal/error/cancel/timeout, starts=ends=1 and active state returns to zero.
Cancellation signals work and must not independently double-release the
lifecycle lease. Foreground notification derives only from outer lease state.

Focused labels:

```text
AK-1 pure chat cannot underflow
AK-2 phone task has exactly one outer lease
AK-3 all terminal paths close
AK-4 close idempotent under cancel/finally race
AK-5 next task starts from zero
AK-6 foreground remains during active work and disappears after terminal
```

### P0-C — Sensitive actions require effect-level, not tool-name-level, confirmation

**Problem:** a chain of primitives can reach a semantic Send/Delete/Call action
without invoking a high-level tool name.

**Target contract:**

```text
selector resolution
→ PreparedAction / ResolvedEffect
→ policy evaluation
→ exact, single-use grant
→ commit with package + revision revalidation
```

`ResolvedEffect` must bind plan/action id, package, snapshot revision, semantic
target, risk, recipient/content hash where relevant, evidence and expiry.
Unresolved raw coordinate actions cannot claim a HIGH/CRITICAL effect.

## P1 / design queue

- `RouteResult`: distinguish `VERIFIED`, `EXECUTED_UNVERIFIED`, `PARTIAL` and
  `FAILED`; no success merely because an Android intent/tool did not throw.
  The same typed terminal contract must feed `TaskEvent`, channel automation and
  UI; a failure must never be encoded as a `Completed("Failed: ...")` string.
  Android Intent launch defaults to `EXECUTED_UNVERIFIED` until observed
  package/action evidence exists.
- Structural `UiSnapshot` separate from LLM `ScreenDsl` projection. Resolver
  result must be `Unique`, `Ambiguous` or `Missing`; only `Unique` reaches
  prepare/commit.
- Revisioned stable target identity: resource id/class/window/package/role plus
  bounds as evidence/fallback, not bounds-only identity.
- Fix `StuckDetector` to consume actual UI delta and tool errors.
- Debug report exposes trace counters; build tests assert invariants rather than
  using DebugReport only as diagnostics.
- Debug-only fake target app / bound test oracle for popup, keyboard, loading,
  stale target and commit verification. No test-only broadcast listener in
  production Hermes code.
- Adapter framework first; Telegram and Browser are proof slices only after P0.
  No broad 50-app registry/200 regex expansion before evidence.

## Benchmark policy

Start with a small AryaBench-fa seed. A task includes expected route, expected
verifier evidence, cold/warm state, backend, admission state, latency stages and
outcome class. Expand with ambiguity, safety, offline, popup and keyboard
variants only after the seed is reproducible.

Do not publish TTFT/TPS/success numbers without the device, runner, thermal
state, model hash and corpus result.

## Explicitly withdrawn or unverified claims

- Old snapshot claims about UI prelaunch/duplicate bootstrap and TASK acquire
  ordering are not current P0s without an exact current-main reproduction.
- Manufacturer-substring backend heuristics, prompt caches, micro-probes,
  voice/wake-word, giant app registries and ranked tool filtering are benchmark
  candidates, not accepted P0 fixes.
- `ActivityManager.trimMemory()` is not a mechanism to reclaim other apps'
  memory.

## Session operating rules

- Keep agent identity as `#agent N` in review messages.
- Store findings/files, not secrets, tokens, sessions or private logs.
- Agents may research asynchronously; return with a status and exact evidence.
- Avoid repeated summaries; contribute a patch seam, test, counterexample or
  current-source correction.
