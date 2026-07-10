# Structurally repaired delegated corpus

These corpora came from the heavy offline delegation and were normalized by
`scripts/normalize_arya_action_clarifications.py` to meet the current
`arya.action.v1` validator:

- 150 train + 45 eval clarify rows gained deterministic `clarification` text;
- 150 train + 45 eval refuse rows gained `refusal_reason` from controlled slot
  metadata;
- all rows pass the in-repo validator after normalization.

They are **structurally reviewed**, not proof of production quality. Before a
production adapter run, sample semantic quality, improve Finglish/English
coverage, and evaluate output behavior on the held-out split.
