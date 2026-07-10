# Arya E4B Action Adapter datasets

| Path | Purpose | Training status |
|---|---|---|
| `seed.fa-en.jsonl` | smoke/test wiring | not production training data |
| `generated/arya_action_bootstrap_v0.1.jsonl` | deterministic template expansion | requires human/offline-agent review |
| `reviewed/` | merged reviewed training shards | eligible after validator + review |
| `heldout/` | evaluation-only rows | never train on these rows |

The bootstrap corpus is intentionally not considered sufficient for a full run.
It supplies diverse action-schema fixtures and a deterministic baseline while
reviewed Persian/Finglish/ambiguity/safety examples are collected.
