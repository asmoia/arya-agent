# MODELS.md — Arya Agent (آریا) Model Catalog & Guidelines

## 1. Official Model Lineup (Qwen3 Series)

| Model ID | Name | File Format | ~Size | Min RAM | Target Role | Download Link |
|---|---|---|---|---|---|---|
| `qwen3-0.6b` | Qwen3 0.6B Instruct | `Q4_K_M.gguf` | ~500 MB | 3 GB | Tier2-lite only | [Download GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf) |
| `qwen3-1.7b` **(DEFAULT)** | Qwen3 1.7B Instruct | `Q4_K_M.gguf` | ~1.2 GB | 4 GB | Tier2-lite + short Tier3 | [Download GGUF](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf) |
| `qwen3-4b` | Qwen3 4B Instruct | `Q4_K_M.gguf` | ~2.5 GB | 8 GB | Full local Tier3 Agent | [Download GGUF](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf) |
| `custom` | Custom GGUF Model | `.gguf` | Custom | Dynamic | User provided URL | Any valid GGUF URL |

---

## 2. RAM Gating Policy

- Devices with **< 8 GB RAM** are restricted from running full local Tier3 agent loops on 4B models.
- When an oversized model or task is requested on low RAM devices, Arya displays an actionable Persian message suggesting a lighter model or switching to cloud inference.

---

## 3. Fine-Tuning with Free Google Colab (Optional)

You can fine-tune Qwen3 models for Persian tool calls using our free Google Colab notebook:
- Notebook path: `training/arya_lora.ipynb`
- Uses **Unsloth QLoRA** on a free T4 GPU
- Exports a `Q4_K_M.gguf` file ready for installation in Arya Agent via the custom URL slot.
