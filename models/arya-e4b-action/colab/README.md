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
