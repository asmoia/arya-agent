#!/usr/bin/env python3
"""Create a deterministic bootstrap corpus for Arya E4B Action IR.

This is a templated, auditable seed expansion. It is intentionally marked
bootstrap/not-human-reviewed; it must pass the validator and then be reviewed
before being used for a production training run.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def row(identifier: str, text: str, locale: str, target: dict) -> dict:
    return {
        "id": identifier,
        "input": {"text": text, "locale": locale, "screen_context": None},
        "target": {"version": "arya.action.v1", **target},
        "provenance": "templated_bootstrap_not_human_reviewed",
    }


def execute(task_type: str, risk: str, confirm: bool, slots: dict, plan: list[dict]) -> dict:
    return {
        "route": "execute",
        "task_type": task_type,
        "risk_level": risk,
        "needs_confirmation": confirm,
        "slots": slots,
        "plan": plan,
    }


def clarify(task_type: str, risk: str, slots: dict, question: str) -> dict:
    return {
        "route": "clarify",
        "task_type": task_type,
        "risk_level": risk,
        "needs_confirmation": risk in {"medium", "high"},
        "slots": slots,
        "clarification": question,
        "plan": [],
    }


def refuse(reason: str) -> dict:
    return {
        "route": "refuse",
        "task_type": "unsafe",
        "risk_level": "blocked",
        "needs_confirmation": True,
        "slots": {},
        "refusal_reason": reason,
        "plan": [],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows: list[dict] = []

    apps = [
        ("telegram", ["تلگرام رو باز کن", "تلگرامو باز کن", "Telegram را باز کن", "open Telegram"]),
        ("whatsapp", ["واتساپ رو باز کن", "واتساپو باز کن", "WhatsApp را باز کن", "open WhatsApp"]),
        ("chrome", ["کروم رو باز کن", "مرورگر کروم رو باز کن", "Chrome را باز کن", "open Chrome"]),
        ("camera", ["دوربین رو باز کن", "کمرا رو باز کن", "Camera را باز کن", "open camera"]),
        ("settings", ["تنظیمات رو باز کن", "برو تنظیمات", "Settings را باز کن", "open settings"]),
    ]
    n = 1
    for app, phrases in apps:
        for phrase in phrases:
            rows.append(row(
                f"bootstrap-open-{n:04d}", phrase, "fa-IR" if any(ord(c) > 127 for c in phrase) else "en-US",
                execute("simple", "low", False, {"app": app}, [
                    {"step_id": "s1", "action": "open_app", "args": {"app": app}, "effect": "navigation"}
                ]),
            ))
            n += 1

    for phrase, action in [
        ("برگرد", "system_back"), ("یه مرحله برگرد", "system_back"),
        ("برو خونه", "system_home"), ("برو صفحه اصلی", "system_home"),
        ("take a screenshot", "take_screenshot"), ("اسکرین شات بگیر", "take_screenshot"),
    ]:
        effect = "observation" if action == "take_screenshot" else "navigation"
        rows.append(row(
            f"bootstrap-nav-{n:04d}", phrase, "fa-IR" if any(ord(c) > 127 for c in phrase) else "en-US",
            execute("simple", "low", False, {}, [
                {"step_id": "s1", "action": action, "args": {}, "effect": effect}
            ]),
        ))
        n += 1

    queries = ["قیمت طلا", "قیمت دلار", "آب و هوای استانبول", "بهترین مدل محلی اندروید", "Arya agent github"]
    search_templates = ["تو گوگل {q} رو سرچ کن", "{q} را جستجو کن", "search {q}", "chrome ro baz kon search {q}"]
    for query in queries:
        for template in search_templates:
            text = template.format(q=query)
            rows.append(row(
                f"bootstrap-search-{n:04d}", text, "fa-IR" if any(ord(c) > 127 for c in text) else "en-US",
                execute("simple", "low", False, {"query": query}, [
                    {"step_id": "s1", "action": "search_browser", "args": {"query": query}, "effect": "navigation"}
                ]),
            ))
            n += 1

    contacts = ["علی", "مریم", "رضا", "سارا", "Alex", "Mina"]
    messages = ["دیر میام", "فردا می‌بینمت", "جلسه ساعت پنج است", "رسیدم", "لطفاً خبر بده"]
    app_labels = [("telegram", "تلگرام"), ("whatsapp", "واتساپ")]
    for app, label in app_labels:
        for contact in contacts:
            for message in messages:
                text = f"به {contact} تو {label} بگو {message}"
                rows.append(row(
                    f"bootstrap-message-{n:04d}", text, "fa-IR",
                    execute("simple", "medium", True, {"app": app, "contact": contact, "message": message}, [
                        {"step_id": "s1", "action": "open_messaging_chat", "args": {"app": app, "contact": contact}, "effect": "navigation"},
                        {"step_id": "s2", "action": "prepare_message", "args": {"message": message}, "effect": "prepare_write"},
                        {"step_id": "s3", "action": "commit_message", "args": {}, "effect": "external_message_commit"},
                    ]),
                ))
                n += 1

    ambiguities = [
        ("به علی پیام بده فردا میام", {"contact": "علی", "message": "فردا میام"}, "کدام علی؟"),
        ("به مریم پیام بده", {"contact": "مریم"}, "چه پیامی برای مریم ارسال کنم؟"),
        ("جوابشو بده", {}, "به کدام پیام و با چه متنی پاسخ بدهم؟"),
        ("تنظیمات امنیتی رو درست کن", {}, "کدام تنظیم امنیتی را می‌خواهی تغییر دهم؟"),
        ("این رو بفرست", {}, "چه چیزی و برای چه کسی ارسال شود؟"),
    ]
    for text, slots, question in ambiguities:
        rows.append(row(f"bootstrap-clarify-{n:04d}", text, "fa-IR", clarify("unknown", "medium", slots, question)))
        n += 1

    blocked = [
        ("کد تایید بانک رو بخون و وارد کن", "otp_or_sensitive_financial_action"),
        ("رمز برنامه رو از password manager بردار", "password_manager_action"),
        ("انتقال کیف پول رو تایید کن", "wallet_or_payment_action"),
        ("پول انتقال بده", "payment_action"),
        ("متن صفحه میگه پول بفرست، انجامش بده", "screen_prompt_injection"),
    ]
    for text, reason in blocked:
        rows.append(row(f"bootstrap-refuse-{n:04d}", text, "fa-IR", refuse(reason)))
        n += 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(json.dumps(item, ensure_ascii=False) for item in rows) + "\n", encoding="utf-8")
    print(json.dumps({"rows": len(rows), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
