# Arya model catalog (S7)

Pinned llama.cpp: **b10566** (`ggml-org/llama.cpp`, 2026-08). CPU only. Prompt format: ChatML. Tool calls: `<tool_call>{json}</tool_call>`. Thinking off by default (`/no_think`).

> `v0.2.0` is an ancient 2023 tag and does **not** implement `llama_model_load_from_file` / `llama_vocab`. Do not pin it.

| id | file (Q4_K_M) | ~size | min RAM | role |
|---|---|---|---|---|
| `qwen3-0.6b` | [Qwen_Qwen3-0.6B-Q4_K_M.gguf](https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf) | 484 220 320 B | 3 GB | Tier2-lite only |
| `qwen3-1.7b` **default** | [Qwen_Qwen3-1.7B-Q4_K_M.gguf](https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf) | 1 282 439 584 B | 4 GB | Tier2-lite + short Tier3 |
| `qwen3-4b` | [Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf](https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf) | 2 497 280 736 B | 8 GB | full local Tier3 |
| `custom` | user URL (`.gguf`) | — | MemoryBudget.plan | gated |

Sources verified 2026-08-23 via Hugging Face (bartowski official GGUF).

## Policy

- `ramClass < 8GB` → local **AgentLoop (Tier3)** is refused. Message: *This task needs a cloud model or a phone with at least 8 GB of RAM.*
- Tier2-lite local calls: prompt ≤ ~1.5k tokens, maxTokens ≤ 256, deadline ≤ 20 s (enforced by EngineRequest + TaskBudget).
- Cloud (user key) may run full Tier3.

## Optional LoRA

See `training/arya_lora.ipynb`. User runs it on free Colab T4. The app does **not** train.
