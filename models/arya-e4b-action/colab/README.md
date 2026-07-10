# Run the Arya E4B Action Adapter notebook in Colab

Open directly in Colab after the notebook is pushed:

```text
https://colab.research.google.com/github/asmoia/arya-agent/blob/main/models/arya-e4b-action/colab/arya_e4b_action_adapter.ipynb
```

## User-owned setup

1. Open the notebook in Colab.
2. Select **Runtime → Change runtime type → GPU**.
3. Accept the license/terms for the trainable E4B Transformers checkpoint.
4. Add `HF_TOKEN` in **Colab Secrets**.
5. The notebook defaults to `google/gemma-4-E4B-it`. Keep it unless you intentionally choose another trainable E4B checkpoint. The Android `.litertlm` file is not a fine-tuning base.
6. Leave `SMOKE_ONLY=True` for the first run.
7. Run the cells in order.
8. Inspect GPU VRAM and training output.
9. Save the generated adapter/checkpoint to Google Drive.

## Free Colab constraints

- GPU type, VRAM, runtime length and quota are not guaranteed.
- The notebook is deliberately configured for 4-bit QLoRA, batch size 1 and a
  short sequence to make a smoke run plausible on free T4/P100-class sessions.
- A successful smoke run is not a production training result. Expand validated
  data and run held-out evaluation before packaging any adapter for Android.

## No E2B path

This notebook is E4B-only. It does not download, train or route through E2B.

## OOM during PEFT preparation

If Colab reports an OOM from `prepare_model_for_kbit_training`, restart the
runtime and pull the updated notebook. The notebook intentionally avoids that
helper for E4B because some PEFT versions cast large non-4bit tensors to fp32.
It freezes base weights manually, enables input gradients/checkpointing, and
uses conservative free-GPU defaults (`SEQ_LEN=256`, `LORA_R=4`).

If loading the base model itself OOMs after a runtime restart, the assigned GPU
is too small for that checkpoint/session combination. Stop there and use a
larger GPU; do not repeatedly retry or replace the E4B program with E2B.

## PEFT `Gemma4ClippableLinear` target-module error

If PEFT says `Target module Gemma4ClippableLinear ... is not supported`, restart
the Colab runtime and use the updated notebook. E4B wraps quantized projections
in a custom module; the notebook now discovers and targets the supported inner
`*.linear` `Linear4bit` modules, such as `q_proj.linear`, instead of targeting
the wrapper `q_proj` itself.

## TRL `dataset_text_field` error

The notebook no longer uses `trl.SFTTrainer`. Colab can resolve a newer TRL
version whose constructor removed or relocated `dataset_text_field`. The updated
notebook tokenizes the corpus and uses stable `transformers.Trainer` plus
`DataCollatorForLanguageModeling` for the smoke run. Restart the runtime or
replace the entire training cell with the updated version before retrying.

## `No inf checks were recorded for this optimizer`

This means Trainer started without usable adapter gradients, often after a
custom wrapper/gradient-checkpointing interaction. The updated notebook now:

- verifies selected `Linear4bit` LoRA targets;
- runs one real forward/backward autograd preflight before `Trainer`;
- asserts at least one `lora_*` gradient exists;
- disables Trainer AMP/GradScaler and gradient checkpointing for the initial
  one-step smoke run;
- uses one accumulation step in smoke mode.

Restart the Colab runtime and use the updated notebook. If the autograd
preflight fails, copy the printed `first_trainable` names and the preflight
error—do not continue to `trainer.train()`.

## `Loss has no gradient path` during autograd preflight

The notebook now calls `enable_input_require_grads()` after PEFT wraps E4B,
even in smoke mode. This is required for some k-bit Gemma E4B wrapper paths.
It also prints executed LoRA module names and trainable adapter names before
failing, so an unsupported/non-executed target is diagnosed before Trainer.
Restart the runtime and use the updated notebook; if the preflight still fails,
send the printed diagnostic dictionary, not a later Trainer error.
