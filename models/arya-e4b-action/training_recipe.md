# E4B Action Adapter Training Recipe

## Goal

Train an Arya-specific E4B adapter to emit compact `arya.action.v1` JSON for
ambiguous or complex commands. It is a planner/router adaptation, not a UI-tap
model and not an alternative to deterministic execution.

## Compute boundary

A 4B QLoRA/LoRA adaptation needs a GPU runner. GitHub hosted Actions runners
are used for corpus/schema validation only. Use one of:

- self-hosted GPU Actions runner;
- managed GPU job with explicit project credentials;
- a controlled local workstation with reproducible container/image.

Do not declare an adapter trained merely because the CPU preflight workflow
completed.

## Data gates

Before a GPU job accepts a JSONL shard:

```bash
python3 scripts/validate_arya_e4b_action_dataset.py \
  models/arya-e4b-action/dataset/seed.fa-en.jsonl \
  --manifest models/arya-e4b-action/artifacts/seed-manifest.json
```

Expand the seed only through validated rows. Teacher-generated rows require:

1. Action IR schema validation;
2. known action allow-list;
3. safety/confirmation validation;
4. no private payloads, OTPs, passwords, financial credentials or tokens;
5. deduplication and human review of ambiguous/risky rows.

## Fine-tuning contract

```text
Input:
  user command + minimal action context

Target:
  one Action IR JSON object

No target:
  chain-of-thought, raw accessibility coordinates, arbitrary tool names,
  direct commit approval, or verbose explanations
```

## Evaluation gates before mobile packaging

- JSON schema validity;
- action allow-list validity;
- exact route/slot accuracy on held-out Persian/Finglish/English corpus;
- safety false-negative rate;
- clarify/refuse correctness;
- expected confirmation semantics;
- comparison against no-LLM compiler and base E4B;
- Android E4B cold/warm latency, RAM and thermal measurements.

## Packaging decision

Do not merge an adapter into a LiteRT artifact until it beats the base E4B on
Action IR validity and route accuracy without regressing hard-reasoning fallback
or exceeding the device memory budget. The runtime must retain its adaptive
Full/Balanced/Compact memory policy.
