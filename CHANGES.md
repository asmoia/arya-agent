# 🤖 آریا (Arya) — یادداشت‌های انتشار

> دستیار هوشمند فارسی برای اندروید — آفلاین، متن‌باز، با کنترل کامل گوشی

## v1.2.24 — Fix: tool culling dropped the phone-control + messenger tools

**The long-task tools existed but were unreachable by the model.** The on-device tool list was built by
`LocalPromptBudget` with a plain `tools.take(12)` in *registry order*. Because the 16 generic tools are
registered before the 13 mobile tools, `take(12)` kept only generic tools and silently dropped **every
phone-control/messaging tool** — `tap`, `tap_node`, `swipe`, `scroll_to_find`, `find_and_tap`,
`send_message`, `open_messaging_chat`, and `telegram_read_chat` (registered last). Result from the
v1.2.23 device report: **0 tool calls** and canned replies to "compile my chats in tg" — the model never
even had `telegram_read_chat` in its prompt.

- `LocalPromptBudget` now selects tools in **priority order** (observe/control → long‑task messaging →
  touch/scroll/find → context → orchestration) rather than registry order, so the messenger and
  phone‑UI tools survive the 12‑tool cap.
- `PhoneToolset.CHAT_TOOLS` now includes `telegram_read_chat` and `open_messaging_chat` (and dropped the
  dead `search_browser` reference) so the chat path offers them too.
- Version bumped to **v1.2.24 (129)**.

## v1.2.23 — Long-task support: read a whole Telegram chat + chunked summarizer

- **`telegram_read_chat` tool** — opens Telegram, searches/scrolls to the requested person/group/channel,
  opens the chat, scrolls back through the history and scrapes the visible message texts into a
  de-duplicated list (oldest→newest) for the model to read or summarise. Built on the same proven
  primitives as `open_messaging_chat` (open app → chat list → search/scroll-and-find-and-click), plus a
  paged scroll-and-scrape pass. `complete`/`pages`/`message_count` tell the caller whether it reached
  the top of the history or hit the page budget. Never sends messages.
  - ⚠️ Telegram renders its message list in a virtualized RecyclerView, so scraping is by the
    currently-visible bubbles; the bounds/text heuristic may need calibration on a specific device/ROM.
- **`LongSummaryEngine`** — pure, unit-tested chunked **map-reduce** summarizer that lets a 2048-token
  on-device model handle arbitrarily long content: it chunks the input, summarizes each chunk, then
  recursively merges chunk summaries until one fits the window. This is the mechanism that makes
  "read all my chats with someone and summarize" feasible with a small local model (instead of trying
  to grow the context).
- Registered the reader in `ToolRegistry`; version bumped to **v1.2.23 (128)**.

## v1.2.22 — Fix :engine crash-loop on cold start (regression from 1.2.21)

**Recovered from the second debug ZIP (arya-debug-20260830_171625, same Huawei ADY-LX9).**
The 1.2.21 build installed and the foreground-service crash was fixed, but a new / serious
problem appeared: the `:engine` process was **recreated on every ensureLoaded attempt and
died about 1s after fork** (arya-engine.log shows `:engine` pid 12749→12809, 13211→13238,
… over and over). Every `ensureLoaded` timed out at 15s and **no model ever loaded**.

- **fixed: `UninitializedPropertyAccessException` in `EngineService.onCreate`.** To beat the
  5s foreground-service window I had moved `startForegroundIfNeeded()` to the top of
  `onCreate`, but that method reads `engineCore.isLoaded` **before `engineCore = EngineCore(this)`
  runs**, so `:engine` crashed on every start. `startForegroundIfNeeded()` and `emitProgress()`
  now guard the read with `::engineCore.isInitialized` (and the early startForeground stays).

- **fixed: cold-start bind timeout too tight.** `EngineClient.getOrBindService()` waited only
  15s for `bindService()`. On a throttled/low-memory device spawning `:engine` (fork + onCreate
  + native lib load) can exceed that. Raised to 30s — a wait is better than a silent task failure.

- Bump default version to **1.2.22 (127)**.

## v1.2.21 — Fix "does not work at all": prompt/context, FGS crash, JNI callback

**Recovered from the user debug ZIP (arya-debug-20260830_162929, Huawei ADY-LX9 / Kirin 9000S, Android 12).**

- **fixed «prompt_exceeds_ctx» — the real blocker.** The engine loaded ctx=1024 while the FunctionGemma prompt tokenised to ~1.2k–4.6k, so *every* message failed immediately.
  - `MemoryBudget.plan` now takes `minCtxSize` and never silently picks a window smaller than the caller needs (it used to drop to 1024 whenever `avail RAM < 6 GB`). It also caps the window at 2048 so we never request more than the model's trained context and never re-bloat the KV cache that OOM-killed the 1.7B engine.
  - `EngineCore.ensureLoaded` forwards the caller's requested context into the planner as a floor.
  - New `LocalPromptBudget` in `LocalLlmClient`: sizes the window from the *actual* prompt and trims oldest history (never the latest user turn) or shrinks the tool list so the prompt can never overflow whichever window is loaded. Persian messages that are too big are truncated, never dropped.
  - The user-facing chat path and the full 29-tool agent path both route through this, so both now produce output instead of dying with `prompt_exceeds_ctx`.

- **fixed `ForegroundServiceDidNotStartInTimeException` (main-process crash).** A service started with `startForegroundService()` must call `startForeground()` within 5 s. `onCreate` no longer bails out to `stopSelf()` before posting the foreground notification, `goForeground()` emits the notification unconditionally (a missing `POST_NOTIFICATIONS` only hides it, it can't trigger the crash), and `start()` falls back to `startService()` if Android rejects a background-sourced `startForegroundService`. Uses `ServiceCompat.startForeground` with the declared `specialUse` type so it is correct on Android 14+ too.

- **fixed `NoSuchMethodError` on the native stream callback.** `NoSuchMethodError: no non-static method L<cls>;.onDeltaPiece(...)` was thrown from `EngineNative.nativeGenerateStream` when R8 obfuscated the callback slot. The JNI now resolves `onDeltaPiece` against the `@Keep`-annotated `EngineNative$NativeStreamCallback` interface (cached global ref) instead of the runtime object class, and ProGuard keeps `EngineNative`, `NativeStreamCallback`, `NativeLoadCallback` and `StreamBridge`. `onProgress` was already dead in the loader (it no longer re-enters Java); keep rules added for it as well.

- Bump default version to **1.2.21 (126)**.

## v0.5.1 — Fixed model download + robust tool calling

- **مدل پیش‌فرض Qwen2.5-1.5B-Instruct** (Q4_K_M, 940 MB) جایگزین BitNet شد
  - پشتیبانی فارسی بسیار بهتر
  - دانلود مستقیم از HuggingFace بدون ۴۰۴
  - فرمت ChatML بومی
- **BitNet 2B i2_s** هم در کاتالوگ باقی مانده (۲.۹ GB)
- **ابزارفراخوانی بازطراحی کامل**:
  - پرامپت فشرده و صریح برای مدل‌های کوچک
  - اعتبارسنجی نام ابزار در مقابل ابزارهای موجود
  - پارسر چندلایه با fallback (tag, block, JSON, direct mention)
  - مثال ابزارفراخوانی در system prompt
  - تریم نتایج ابزار به ۵۰۰ کاراکتر برای جلوگیری از prompt طولانی
- **مشکل لود مدل قدیمی رفع شد**:
  - آستانه RAM برای GGUF: ۱.۵ GB حداقل (در مقابل ۵ GB برای E4B)
  - تشخیص خودکار نوع مدل (GGUF vs LiteRT)
- LocalRuntimePolicy: `checkBitNetAdmission` مستقل از E4B

## v0.5.0 — BitNet on-device inference

- **مدل BitNet b1.58 2B4T** به‌عنوان مدل محلی پیش‌فرض اضافه شد.
  - وزن‌های ۱.۵۸-بیت (ternary {-1, 0, +1}) — ~۱ گیگابایت حجم
  - اینفرنس با llama.cpp (JNI) — بدون وابستگی به LiteRT-LM برای این مدل
  - سازگار با هر مدل GGUF (Qwen, Phi, Bonsai, …)
- **BitNetLlmClient** جدید: کلاینت LLM مبتنی بر llama.cpp با پشتیبانی streaming و ابزارفراخوانی
- **BitNetNative**: پل JNI بین Kotlin و llama.cpp
- معماری LlmProvider ارتقا یافت: `OPENAI | ANTHROPIC | LOCAL | BITNET`
- LocalModelManager دوباره فعال شد با کاتالوگ BitNet + دانلود از HuggingFace
- LocalRuntimePolicy: آستانه RAM برای BitNet (~۲ GB آزاد) بسیار پایین‌تر از E4B
- پرامپت ChatML برای مدل‌های کوچک بهینه شد
- پشتیبانی ABI: arm64-v8a, x86_64

## v0.4.5 — Fast Local default

- Gemma E4B/E2B auto-download، auto-select و runtime use بازنشسته شدند.
- Fast Local مسیر پیش‌فرض است: compiler deterministic، intentهای native و high-level toolها بدون model load اجرا می‌شوند.
- انتخاب legacy large local model در preferences، chat، task و single-shot به‌صورت fail-fast و روشن block می‌شود.
- Cloud AI فقط به‌صورت opt-in برای requestهای پیچیده باقی مانده است.
- pipeline training/model E4B و artifactهای مرتبط از product path حذف شدند.

## v0.4.4 — Adaptive E4B memory budget

- این release برای سازگاری تاریخی نگه داشته شده است؛ مسیر E4B در v0.4.5 بازنشسته شد.

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
