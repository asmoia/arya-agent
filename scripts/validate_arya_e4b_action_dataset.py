#!/usr/bin/env python3
"""Validate Arya E4B Action Adapter JSONL without ML/GPU dependencies."""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

ALLOWED_ROUTES = {"execute", "clarify", "refuse", "escalate"}
ALLOWED_TASK_TYPES = {"simple", "compound", "exploratory", "unsafe", "unknown"}
ALLOWED_RISKS = {"low", "medium", "high", "blocked"}
ALLOWED_EFFECTS = {
    "observation", "navigation", "prepare_write", "external_message_commit",
    "external_call_commit", "settings_mutation", "none",
}
ALLOWED_ACTIONS = {
    "open_app", "open_messaging_chat", "prepare_message", "commit_message",
    "search_browser", "read_visible_content", "summarize_visible_content",
    "get_device_info", "take_screenshot", "system_back", "system_home",
    "wait_for_ui", "clarify_contact", "finish",
}


def fail(line: int, message: str) -> None:
    raise ValueError(f"line {line}: {message}")


def validate(row: dict, line: int) -> None:
    if not isinstance(row.get("id"), str) or not row["id"]:
        fail(line, "missing non-empty id")
    target = row.get("target")
    if not isinstance(target, dict):
        fail(line, "missing target object")
    if target.get("version") != "arya.action.v1":
        fail(line, "unsupported Action IR version")
    route = target.get("route")
    if route not in ALLOWED_ROUTES:
        fail(line, f"unsupported route {route!r}")
    if target.get("task_type") not in ALLOWED_TASK_TYPES:
        fail(line, "unsupported task_type")
    risk = target.get("risk_level")
    if risk not in ALLOWED_RISKS:
        fail(line, "unsupported risk_level")
    if not isinstance(target.get("needs_confirmation"), bool):
        fail(line, "needs_confirmation must be boolean")
    if not isinstance(target.get("slots"), dict):
        fail(line, "slots must be object")
    plan = target.get("plan")
    if not isinstance(plan, list) or len(plan) > 8:
        fail(line, "plan must be a list with at most 8 steps")
    if route in {"clarify", "refuse"} and plan:
        fail(line, f"{route} must not contain executable steps")
    if route == "clarify" and not target.get("clarification"):
        fail(line, "clarify requires clarification text")
    if route == "refuse" and not target.get("refusal_reason"):
        fail(line, "refuse requires refusal_reason")
    if risk == "blocked" and route != "refuse":
        fail(line, "blocked risk must refuse")
    seen_steps = set()
    commit_seen = False
    for step in plan:
        if not isinstance(step, dict):
            fail(line, "plan step must be object")
        step_id = step.get("step_id")
        if not isinstance(step_id, str) or step_id in seen_steps:
            fail(line, "step_id missing or duplicate")
        seen_steps.add(step_id)
        if step.get("action") not in ALLOWED_ACTIONS:
            fail(line, f"unknown action {step.get('action')!r}")
        if step.get("effect") not in ALLOWED_EFFECTS:
            fail(line, "unknown effect")
        if not isinstance(step.get("args"), dict):
            fail(line, "args must be object")
        if step.get("effect") == "external_message_commit":
            commit_seen = True
    if commit_seen and (risk not in {"medium", "high"} or not target["needs_confirmation"]):
        fail(line, "message commit must be medium/high risk and require confirmation")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()

    rows = []
    for line_no, raw in enumerate(args.dataset.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            row = json.loads(raw)
        except json.JSONDecodeError as exc:
            fail(line_no, f"invalid JSON: {exc.msg}")
        validate(row, line_no)
        rows.append(row)

    ids = [row["id"] for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate dataset IDs")
    stats = {
        "rows": len(rows),
        "routes": dict(Counter(row["target"]["route"] for row in rows)),
        "risks": dict(Counter(row["target"]["risk_level"] for row in rows)),
        "locales": dict(Counter(row["input"].get("locale", "unknown") for row in rows)),
        "actions": dict(Counter(step["action"] for row in rows for step in row["target"]["plan"])),
    }
    rendered = json.dumps(stats, ensure_ascii=False, indent=2)
    print(rendered)
    if args.manifest:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(rendered + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"Dataset validation failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
