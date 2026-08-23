# آریا (Arya Agent) — دستیار هوشمند و عامل محلی اندروید

**آریا** یک دستیار هوشمند و عامل خودمختار محلی برای سیستم‌عامل اندروید است که با تمرکز بر زبان فارسی، سرعت بالا و حریم خصوصی طراحی شده است.

---

## ویژگی‌های اصلی (Key Features)

1. **معماری دو فرآیندی (:engine)**: جداسازی کامل فرآیند پردازش سنگین هوش مصنوعی از رابط کاربری جهت جلوگیری از کرش و مصرف بهینه حافظه RAM.
2. **پشتیبانی از مدل‌های محلی GGUF**: اجرای مدل‌های سری Qwen3 به صورت آفلاین روی پردازنده گوشی با کتابخانه `llama.cpp`.
3. **مسیریابی ۳ لایه‌ای (3-Tier Pipeline)**:
   - **Tier 1**: دستورات مستقیم و سریع (کمتر از ۱ ثانیه بدون نیاز به هوش مصنوعی)
   - **Tier 2**: مهارت‌ها و ابزارهای سریع
   - **Tier 3**: حلقه کامل عامل هوشمند (Agent Loop) محلی یا ابری
4. **ورودی صوتی شباهت به سیری (Siri-like Voice Input)**: تشخیص گفتار فارسی (`SpeechRecognizer` با `fa-IR`)، برگه شنود متحرک و خواندن پاسخ‌های کوتاه با TTS.
5. **برگه شناور دستیار (Assistant Overlay Sheet)**: دسترسی سریع روی سایر برنامه‌ها با لمس آیکون شناور.
6. **مدیریت یکپارچه وضعیت (TaskSessionStore)**: ماشین حالت یکپارچه برای پیگیری و توقف ایمن کارها.

---

## مدل‌های پشتیبانی‌شده (Model Catalog)

- **Qwen3 0.6B**: مدل خیلی سبک برای گوشی‌های با ۳ گیگابایت رم
- **Qwen3 1.7B (پیش‌فرض)**: مدل بهینه و سریع برای رم ۴ گیگابایت به بالا
- **Qwen3 4B**: مدل کامل برای استدلال و کارهای پیچیده روی رم ۸ گیگابایت به بالا
- **سرویس‌های ابری**: پشتیبانی از APIهای سازگار با OpenAI و Anthropic

---

## راهنمای نصب و اجرا (Installation)

1. فایل APK را دانلود و نصب کنید.
2. دسترسی‌های مورد نیاز (دسترسی‌پذیری Accessibility، اعلان‌ها و پنجره شناور) را فعال کنید.
3. در صورت تمایل، مدل محلی Qwen3 را از بخش تنظیمات مدل دانلود نمایید.

---

# Arya Agent — Local-First Persian Android Assistant

Arya is a fast, local-first Persian Android AI agent built on a 3-tier routing architecture with isolated `:engine` inference process and llama.cpp runtime.

### Key Highlights
- **Isolated Process (`:engine`)**: Native llama.cpp inference runs in a separate process.
- **Prefix Caching**: Sub-second prefill for system prompts using llama.cpp state persistence.
- **Siri-like Voice Input**: Native Persian speech recognition (`fa-IR`) and TTS support.
- **Zero Heavy Dependencies**: Removed LiteRT-LM, LangChain4j, and legacy channels in favor of lean OkHttp SSE and custom JNI bridges.

### What was removed
LiteRT-LM / MediaPipe LLM, LangChain4j, WeChat + Discord runtimes, embedded HTTP config server, Hermes parallel agent/cron/MCP (archived under `archive/`). Telegram *automation via Accessibility* is kept.

### Build (Android Studio)
```
./gradlew :app:assembleDebug
./gradlew :app:test
```
This redesign environment does **not** run the Android/NDK toolchain. See `PROGRESS.md` for the owner checklist.

### Docs
- `ARCHITECTURE.md` — processes, AIDL, state machine
- `MODELS.md` — GGUF URLs and RAM policy
- `training/arya_lora.ipynb` — optional Colab LoRA (you run it)
