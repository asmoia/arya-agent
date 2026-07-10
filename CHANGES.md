# 🤖 آریا (Arya) — یادداشت‌های انتشار

> دستیار هوشمند فارسی برای اندروید — آفلاین، متن‌باز، با کنترل کامل گوشی

## v0.4.3 — Keep-alive lifecycle hotfix

- رفع underflow در `HermesAppKeeper`: پایان task بدون lease فعال دیگر شمارندهٔ foreground را منفی نمی‌کند.
- حذف start دوم keep-alive در مسیر Hermes؛ task غیر-pure اکنون یک start و یک پایان lifecycle دارد.
- regression test برای pure-chat پشت‌سرهم و سپس phone task اضافه شد.
- تصمیم‌ها و backlog بازبینی معماری در `docs/ARYA_REVIEW_SESSION_20260710.md` ثبت شدند.
- CI GitHub برای hotfix lifecycle اجرا و سبز شد؛ build محلی اجرا نشد.

## v0.4.2 — E4B local runtime coordination

- `LocalInferenceCoordinator` مالکیت conversation مدل Local را بین Chat، Task و background work سریال می‌کند.
- E4B برای Chat و Task از context مشترک 2048 استفاده می‌کند تا handoff مدل باعث reload بی‌دلیل engine نشود.
- Task E4B قبل از inference، RAM آزاد و readiness مدل را بررسی می‌کند؛ E4B روی CPU برای Task اجرا نمی‌شود.
- direct taskها conversation Chat را بیهوده نمی‌بندند؛ acknowledgement فوری پیش از token مدل نمایش داده می‌شود.
- Screen context به nodeهای actionable و مرتبط کاهش می‌یابد؛ نتیجهٔ ابزار دیگر به 400 کاراکتر اول محدود نمی‌شود.
- batch محدود فقط برای navigation/readهای deterministic مجاز است؛ عملیات حساس همچنان single-step و confirmation-gated هستند.
- learning پنهان Hermes پس از Local Task غیرفعال است تا RAM و thermal budget سریع آزاد شود.
- failure مسیر Saved Messages به Agent کند fallback نمی‌شود و error واقعی را برمی‌گرداند.

## v0.4.1 — Arya product identity and documentation

- README، GitHub Pages، demo و OG preview فقط هویت و قابلیت‌های Arya را توضیح می‌دهند.
- بخش شفاف «پروژه‌ها و فناوری‌های استفاده‌شده» اضافه شد؛ attribution قانونی در NOTICE باقی مانده است.
- متن‌های user-facing باقی‌مانده در notification، config page، debug report، issue links و چند locale از نام قدیمی به Arya منتقل شدند.
- Contributor Agreement و workflow مرتبط به repository و نام Arya اشاره می‌کنند.
- نام Gradle project به `AryaAgent` تغییر کرد.

## v0.4.0 — Fast task runtime

- **Telegram Saved Messages → Play** اکنون مسیر deterministic و bounded دارد: بازکردن Telegram/Telegram X، رفتن به Saved Messages و لمس فقط کنترل Play قابل‌مشاهده، بدون چند دور inference مدل.
- **Telegram X** در بازکردن اپ، پیام‌رسانی، monitor و Notification Listener پشتیبانی می‌شود.
- دستورهای فارسی صریح برای **ارسال پیام**، **بازکردن اپ**، **جستجوی گوگل/مرورگر**، دکمهٔ Home/Back و داده‌های گوشی (باتری، وای‌فای، اعلان، حافظه و …) قبل از LLM route می‌شوند.
- **Browser search** و **بازکردن مخاطب/گروه/کانال Telegram** ابزارهای high-level دارند تا مدل لازم نباشد چند دور فقط navigation انجام دهد.
- تحلیل یک کانال/گروه نام‌دار ابتدا chat را deterministic باز می‌کند؛ سپس فقط یک پاس محدود برای خواندن پیام‌های قابل‌مشاهده می‌گیرد.
- مدل محلی دیگر قبل از direct route initialize نمی‌شود؛ schema ابزارها task-aware شده‌اند.
- سقف زمان هر inference محلی ۷۵ ثانیه و سقف کل task محلی ۲ تا ۴ دقیقه است؛ چرخه‌های چندین‌دقیقه‌ای fail-fast می‌شوند.
- CI: unit test اجباری شده و smoke matrix از package قدیمی جدا شده است.
- Config server دیگر secret خام برنمی‌گرداند، CORS باز ندارد و برای session محلی token کوتاه‌عمر می‌خواهد.

## v0.3.4

- **Remove bootstrap scroll spam** (find_and_tap max_scrolls gone from bootstrap)
- find_and_tap / scroll_to_find defaults: 2–3 scrolls not 10
- After open: next-step hints without waiting on useless scrolls
- Status: «ادامه اقدام» after tools already ran (not only «فکر مدل»)

## v0.3.3 (workspace — ship when ready)

- **HermesDirectOpen**: open apps via PackageManager (no 20s Accessibility wait)
- **OpenAppTool** uses DirectOpen first; a11y only for OEM allow dialog (shorter poll)
- **TaskFlow prelaunch**: opens known apps before agent/LLM queue
- Accessibility default wait 5s (was 20s)
- Bootstrap soft find_and_tap for Saved Messages labels (fail OK)
- Local prompt skips memory dump; skip double screen prewarm after bootstrap
- updateConfig soft already in 0.3.2; reinforced

## v0.3.2

- **Root fix:** stop reloading LiteRT engine on every task (`updateConfig` soft path)
- **Bootstrap runs first** in Hermes (before prompt/session/LLM) — Telegram/Chrome open immediately
- Pure-chat router no longer steals phone tasks that end with «؟»
- looksLikeTask expanded for سیو/پلی/آهنگ/میتونی بری

## v0.3.1

- **Emergency:** Instant = 3 rounds (not 10); default mode INSTANT
- Bootstrap without waiting for first E4B token: open_app + get_screen_info for Telegram/Chrome/WhatsApp/YouTube
- Status shows «فکر مدل…» while blocked on LLM
- Settings thinking mode cycles without recreate (visible Instant/Adaptive/Thinking/High)
- AppViewModel agent ceiling 8 (never 60)

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

## ویژگی‌های اختصاصی آریا

| تغییر | توضیح |
|---|---|
| 🇮🇷 شخصیت فارسی «آریا» | System prompt فارسی-انگلیسی دو‌زبانه (Hermes identity) |
| 🧠 **هستهٔ هرمس توکار** | `HermesAgentService` — حافظه، مهارت، session، بدون Termux |
| 🛡️ **تأیید کارهای حساس** | قبل از send_message / make_call / … از کاربر می‌پرسد |
| 💾 **Backup / Migration / Recovery** | Export-Import ZIP، DB v2، بازیابی بعد از force-stop |
| 📅 تقویم شمسی | ابزار `shamsi_calendar` — تاریخ شمسی + تبدیل |
| ⚙️ تنظیمات EMUI | ابزار `emui_settings` — کنترل مخصوص هواوی |
| 🔤 رابط فارسی | فایل `values-fa/strings.xml` کامل |
| 🏷️ پکیج جدید | `io.agents.arya` — مسیر نصب و update مستقل آریا |

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

Apache-2.0 — attributionهای لازم در NOTICE نگهداری شده‌اند
