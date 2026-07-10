# Arya E4B Action Adapter

`arya-e4b-action` is an E4B-specific adaptation program for Arya. It is **not**
a general chatbot and it does not replace deterministic execution.

```text
PersianNormalizer / deterministic compiler
  -> validated Arya Action IR
  -> E4B Action Adapter only on ambiguity / low confidence / hard planning
  -> verifier + effect safety gate
```

## Product constraints

- **No E2B dependency.** E4B is Arya's only local LLM target in this program.
- Obvious commands must continue to use the no-LLM compiler and high-level
  tools. Fine-tuning E4B does not make it the always-on tap loop.
- The adapter emits short typed JSON only. It must not emit chain-of-thought,
  arbitrary tool names, raw accessibility coordinates, or safety decisions that
  bypass the deterministic policy layer.
- High-risk effects remain executor-owned:

  ```text
  Action IR -> resolve semantic target -> ResolvedEffect -> confirm -> grant -> commit
  ```

## Model target

| Item | Target |
|---|---|
| Base | Gemma 4 E4B LiteRT-compatible instruction model |
| Adaptation | QLoRA / LoRA action adapter, merged only after evaluation |
| Output | One `arya.action.v1` JSON object |
| Languages | Persian, English, mixed, Finglish |
| Runtime role | ambiguity, plan repair, complex plan generation, teacher data |
| Not for | open app, back/home, screenshot, obvious search/message, every UI tap |

## Stages

1. **Schema and validator** — `action_ir.schema.json` is the stable contract.
2. **Seed corpus** — hand-authored examples in `dataset/seed.fa-en.jsonl`.
3. **Synthetic expansion** — teacher output may be accepted only after schema,
   tool allow-list, risk, confirmation and plan validation.
4. **GPU fine-tuning** — run externally or on a self-hosted GPU runner. GitHub
   hosted CPU runners validate data and recipes only; they do not pretend to
   train a 4B model.
5. **Evaluation and mobile packaging** — compare adapted E4B with the current
   E4B route on AryaBench-fa before publishing an adapter.

## Training input/output

Each JSONL row holds an instruction plus a target Action IR. The target must be
compact; the executor—not the model—owns UI mechanics and confirmation.

```json
{
  "id": "fa-send-message-001",
  "input": {
    "text": "به علی تو تلگرام بگو دیر میام",
    "locale": "fa-IR",
    "screen_context": null
  },
  "target": {
    "version": "arya.action.v1",
    "route": "execute",
    "task_type": "simple",
    "risk_level": "medium",
    "needs_confirmation": true,
    "slots": {"app": "telegram", "contact": "علی", "message": "دیر میام"},
    "plan": [
      {"step_id": "s1", "action": "open_messaging_chat", "args": {"app": "telegram", "contact": "علی"}, "effect": "navigation"},
      {"step_id": "s2", "action": "prepare_message", "args": {"message": "دیر میام"}, "effect": "prepare_write"},
      {"step_id": "s3", "action": "commit_message", "args": {}, "effect": "external_message_commit"}
    ]
  }
}
```

## Hard acceptance gates

Before a row is training-ready:

- JSON schema passes;
- every action is in `allowed_actions`;
- required slots exist;
- plan IDs are unique and ordered;
- medium/high risk has correct confirmation semantics;
- forbidden domains (OTP, banking, password manager, wallet) use `refuse`;
- `clarify` rows contain no executable plan;
- no raw private content, token, session, password or OTP appears in data.

## Training recipe

See `training_recipe.md`. The GitHub workflow validates corpus, schema and
manifest. It intentionally fails the `train` mode on GitHub hosted CPU runners
with an explicit message instead of pretending a 4B QLoRA run happened.
