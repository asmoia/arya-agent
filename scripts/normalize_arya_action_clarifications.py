#!/usr/bin/env python3
"""Fill deterministic clarification text for structurally valid Action IR rows.

This only repairs missing user-visible clarification strings. It does not change
routes, risk, slots or executable plans.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

QUESTIONS = {
    "contact_disambiguation": "کدام مخاطب را منظور داری؟",
    "recipient": "گیرنده را مشخص کن.",
    "contact": "برای چه کسی این کار را انجام دهم؟",
    "message": "چه پیامی ارسال کنم؟",
    "app": "در کدام برنامه انجام دهم؟",
    "app_disambiguation": "کدام برنامه را منظور داری؟",
    "screen_target": "کدام مورد روی صفحه را منظور داری؟",
    "permission": "اجازهٔ لازم را فعال کنم یا کار دیگری انجام دهم؟",
}


def clarification_for(slots: dict) -> str:
    missing = slots.get("missing", [])
    if isinstance(missing, str):
        missing = [missing]
    for key in missing:
        if key in QUESTIONS:
            return QUESTIONS[key]
    if slots.get("contact"):
        return "کدام مخاطب را منظور داری؟"
    if slots.get("app"):
        return "دقیقاً چه کاری را در این برنامه انجام دهم؟"
    return "برای ادامه جزئیات بیشتری می‌گویی؟"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    out = []
    repaired = 0
    for raw in args.input.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        row = json.loads(raw)
        target = row["target"]
        if target.get("route") == "clarify" and not target.get("clarification"):
            target["clarification"] = clarification_for(target.get("slots", {}))
            repaired += 1
        if target.get("route") == "refuse" and not target.get("refusal_reason"):
            slots = target.get("slots", {})
            target["refusal_reason"] = slots.get("refusal_reason", "unsafe_or_sensitive_action")
            repaired += 1
        out.append(row)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in out) + "\n", encoding="utf-8")
    print(json.dumps({"rows": len(out), "clarifications_repaired": repaired}, ensure_ascii=False))


if __name__ == "__main__":
    main()
