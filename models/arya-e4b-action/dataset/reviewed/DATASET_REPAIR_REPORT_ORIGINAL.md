# DATASET_REPAIR_REPORT

## Scope

This repair only addresses the delegated-output review blockers for the Action IR corpus. It does not redo the broader runtime audit or master plan.

## Inputs used

- Review brief: `/mnt/data/DELEGATED_OUTPUT_REVIEW_FA.md`
- Previous blocking defects addressed:
  - missing `step_id`, `args`, and `effect` in executable plan steps
  - raw/unsupported actions such as `find_and_tap`, `input_text`, `request_confirmation`, `clipboard`, and `system_key`
  - low normalized-text diversity
  - train/eval overlap checks

## Generated files

| File | Rows | SHA-256 |
|---|---:|---|
| `arya_action_train_v2_1000_unique.jsonl` | 1000 | `7c05df17fb67c0e2fb321fa62ddeeef7c3ac49383d8bfdb90a58de8d720b7717` |
| `arya_action_eval_v2_300_unique.jsonl` | 300 | `f090020bd8102c07ef0856172388d7d74b94c793fc82e438d80fa0c81b32bb45` |

## Train distribution

| Bucket | Required | Delivered |
|---|---:|---:|
| deterministic / device / navigation / browser | 250 | 250 |
| messaging / draft / commit / confirmation | 250 | 250 |
| casual Persian / typo / Finglish / mixed | 150 | 150 |
| ambiguity / missing slots / recovery | 150 | 150 |
| safety / refusal / prompt injection / recovery | 150 | 150 |
| screen-conditioned examples | 50 | 50 |

## Eval distribution

| Bucket | Delivered |
|---|---:|
| deterministic / device / browser | 60 |
| messaging draft/commit | 60 |
| casual / mixed language | 45 |
| ambiguity / missing-slot | 45 |
| safety / adversarial | 45 |
| screen-conditioned | 45 |

## Validator results

```text
TRAIN_STATUS=0
EVAL_STATUS=0
train rows: 1000
train unique ids: 1000 / 1000
train unique normalized texts: 1000 / 1000
eval rows: 300
eval unique ids: 300 / 300
eval unique normalized texts: 300 / 300
exact train/eval normalized text overlap: 0
train executable plan steps: 1890
eval executable plan steps: 539
missing step_id: 0
missing args: 0
missing effect: 0
unsupported actions: []
unsupported effects: []
secret/email/phone smoke-test findings: 0
```

## Contract enforcement summary

- Every executable plan step has exactly the required structural fields: `step_id`, `action`, `args`, and `effect`.
- Step IDs are local to the row and never duplicated inside a plan.
- All actions are from the allowed list:
  `open_app`, `open_messaging_chat`, `prepare_message`, `commit_message`, `search_browser`, `read_visible_content`, `summarize_visible_content`, `get_device_info`, `take_screenshot`, `system_back`, `system_home`, `wait_for_ui`, `clarify_contact`, `finish`.
- All effects are from the allowed list:
  `observation`, `navigation`, `prepare_write`, `external_message_commit`, `external_call_commit`, `settings_mutation`, `none`.
- `clarify` and `refuse` rows have `plan=[]`.
- `blocked` rows use `route="refuse"`.
- `commit_message` steps use `effect="external_message_commit"`, `needs_confirmation=true`, and medium/high risk.
- No raw coordinate, `find_and_tap`, `input_text`, `request_confirmation`, `clipboard`, `system_key`, `get_screen_info`, or `verify_visible_result` action is emitted.

## Remaining non-blocking caveats

1. This is a synthetic corpus. It is validator-clean but still requires empirical model-output validation before E4B fine-tuning.
2. The eval split avoids exact normalized overlap and uses different wording families, but a human should still sample semantic near-duplicates before final packaging.
3. Slot vocabulary should be frozen in Arya's validator before training so the adapter is not rewarded for extra/free-form slots.
4. Screen-conditioned examples use synthetic screen contexts with stable revision strings; Android integration tests must verify stale revision/package/window fail-closed behavior separately.
5. The corpus should be versioned with the exact Action IR validator commit that accepts it.
