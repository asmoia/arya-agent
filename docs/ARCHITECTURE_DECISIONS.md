# Arya Agent — تصمیم‌های معماری

این سند وضعیت فعلی آریا را توضیح می‌دهد؛ ادعای roadmap یا رفتار تست‌نشده نیست.

## D1 — Local-first با Cloud اختیاری

آریا از LiteRT-LM برای inference محلی و از providerهای OpenAI-compatible یا Anthropic برای Cloud استفاده می‌کند. Local mode برای حریم خصوصی و کنترل هزینه مفید است، اما latency آن به سخت‌افزار دستگاه وابسته است. Cloud mode باید با اطلاع کاربر فعال شود، چون context لازم task می‌تواند به provider منتخب ارسال شود.

## D2 — Router قبل از LLM

PipelineRouter task را قبل از Agent بررسی می‌کند:

- Direct intent/tool برای دستورهای روشن
- Skill برای workflowهای bounded
- PrimeThenAgent برای navigation قطعی + reasoning کوتاه
- Agent loop برای کارهای مبهم

این تصمیم برای جلوگیری از inference بیهوده، کاهش گرما و کاهش latency است.

## D3 — ToolRegistry تنها نقطهٔ اجرا

عملیات گوشی از ToolRegistry عبور می‌کنند. ابزارها مسئول success/failure واقعی‌اند؛ prompt نباید جای ابزار یا verification را بگیرد.

## D4 — Agent محلی باید bounded باشد

هر inference محلی و کل task محلی deadline دارد. timeout بهتر از UIای است که برای چندین دقیقه بدون نتیجه نشان می‌دهد «thinking».

## D5 — Accessibility با محدودیت ایمنی

Accessibility برای navigation و مشاهدهٔ UI لازم است، اما سطح دسترسی بالایی دارد. عملیات بیرونی مانند ارسال پیام و تماس confirmation می‌خواهند. Automation در اپ‌های حساس مالی/رمز نباید استفاده شود.

## D6 — حافظه و skills Hermes

Hermes memory، sessions و skillها در app-private storage نگهداری می‌شوند. memory و skillها نباید به محل ذخیرهٔ secret یا instruction غیرقابل‌اعتماد تبدیل شوند؛ هر تغییر در learning باید با فرض persistent prompt injection بازبینی شود.

## D7 — مسیر release

Release رسمی باید از GitHub Actions با release keystore ثابت ساخته شود. همان key برای update درجا ضروری است. unit test و emulator matrix بخشی از gate هستند؛ Firebase Test Lab در صورت configured بودن یک لایهٔ تکمیلی است.
