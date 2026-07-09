# 🤖 آریا (Arya) — Fork of PokeClaw

> دستیار هوشمند فارسی برای اندروید — آفلاین، متن‌باز، با کنترل کامل گوشی

## v0.3.0

- Adaptive runtime: Instant (3–4 rounds) / Thinking / High / Adaptive
- Action-first: continue after open_app, force tool if essay, anti-repeat
- Live task status lines + FG keeper (START_STICKY) + RAM-aware caps
- Settings: cycle Task thinking mode

## v0.2.5

- Arena-style compact tool playbook (browser / Telegram / WhatsApp / any UI)
- Task-specific hints only when relevant (saves tokens)
- Local: dense playbook + force first tool if model essays without tools
- Speed: max 8 local rounds, faster sampler, shorter screen settle

## v0.2.4

- ✕ Cancel always visible while task/generation runs (not only after first tool)
- Task marks isTaskRunning=true immediately on send
- Anti-refusal prompts: never claim no Telegram/media access; use tools
- Local task uses compact prompt (faster + less waffle)
- Seed skill: Telegram Saved Messages → play media

## v0.2.3

- Task pure-chat fast path, cancel fix, Persian titles, less heat

## v0.2.1

- `hermes_voice`: metadata + analyze transcript (local Gemma / cloud)
- Prefer offline **Gemma 4 E4B (3.6GB)** when device RAM ≥ 10GB
- GitHub signing secrets wired (valid keystore required for signed release)

## v0.2.0

- هسته Hermes توکار + memory/skills/session/cron/MCP
- تأیید حساس + backup + recovery + OEM guide
- GitHub Releases + update checker برای asmoia/arya-agent

## تغییرات نسبت به PokeClaw اصلی

| تغییر | توضیح |
|---|---|
| 🇮🇷 شخصیت فارسی «آریا» | System prompt فارسی-انگلیسی دو‌زبانه (Hermes identity) |
| 🧠 **هستهٔ هرمس توکار** | `HermesAgentService` — حافظه، مهارت، session، بدون Termux |
| 🛡️ **تأیید کارهای حساس** | قبل از send_message / make_call / … از کاربر می‌پرسد |
| 💾 **Backup / Migration / Recovery** | Export-Import ZIP، DB v2، بازیابی بعد از force-stop |
| 📅 تقویم شمسی | ابزار `shamsi_calendar` — تاریخ شمسی + تبدیل |
| ⚙️ تنظیمات EMUI | ابزار `emui_settings` — کنترل مخصوص هواوی |
| 🔤 رابط فارسی | فایل `values-fa/strings.xml` کامل |
| 🏷️ پکیج جدید | `io.agents.arya` — نصب کنار PokeClaw اصلی |

## هستهٔ هرمس (Embedded Hermes)

برنامه **مستقل** است؛ نیازی به Termux یا `hermes gateway` نیست.

```
UI / Accessibility / Tools
          │
          ▼
   HermesAgentService   ← پیش‌فرض فعال
     ├─ Memory (MEMORY.md + episodes)
     ├─ Skills (skills/*.md)
     ├─ Sessions (SQLite)
     └─ Phone ToolRegistry
```

- فعال/غیرفعال: `KVUtils.setHermesEmbeddedEnabled(true/false)` (پیش‌فرض: true)
- مستندات: `HERMES_CORE.md`

ابزارهای متا برای مدل:

- `hermes_memory` — read / append / write / search / episode
- `hermes_skill` — list / get / write / improve / delete / match

## ساخت

```bash
./gradlew assembleDebug
```

APK در: `app/build/outputs/apk/debug/`

## نصب

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## لایسنس

Apache 2.0 — مثل PokeClaw اصلی
