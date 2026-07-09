# 🤖 آریا (Arya) — Fork of PokeClaw

> دستیار هوشمند فارسی برای اندروید — آفلاین، متن‌باز، با کنترل کامل گوشی

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
