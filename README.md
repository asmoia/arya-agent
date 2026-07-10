<p align="center">
  <img src="banna.png" width="600" alt="Arya Agent" />
</p>

# آریا — دستیار هوشمند فارسی اندروید

**آریا (Arya)** یک دستیار Android با رویکرد local-first است: می‌تواند با ابزارهای خود گوشی کار کند، صفحه را از راه Accessibility ببیند، با اپ‌ها تعامل کند و برای کارهای سخت‌تر از مدل محلی یا مدل ابری استفاده کند.

> **وضعیت محصول:** نسخهٔ فعلی یک پروژهٔ experimental است. برای اپ بانکی، کیف پول، password manager، OTP، پرداخت، یا دادهٔ خصوصی حساس از automation استفاده نکنید.

- **آخرین نسخهٔ امضاشده:** [v0.4.0](https://github.com/asmoia/arya-agent/releases/tag/v0.4.0)
- **Package:** `io.agents.arya`
- **حداقل Android:** Android 9 / API 28
- **License:** Apache-2.0

---

## آریا چه کارهایی انجام می‌دهد؟

### کارهای سریع و deterministic
این مسیرها برای کم‌کردن انتظار مدل طراحی شده‌اند و تا جای ممکن بدون LLM انجام می‌شوند:

- باز کردن اپ‌های ساده: `تلگرامو باز کن`، `کروم رو باز کن`
- خواندن دادهٔ گوشی: باتری، وای‌فای، بلوتوث، فضای ذخیره‌سازی، اپ‌های نصب‌شده، اعلان‌ها و clipboard
- جست‌وجوی صریح مرورگر: `در گوگل قیمت طلا رو سرچ کن`
- ارسال پیام صریح: `به علی در تلگرام بگو سلام`
- Home، Back و screenshot
- بازکردن Telegram Saved Messages و پخش یک مدیای قابل‌مشاهده

### Telegram و Telegram X

آریا از Telegram اصلی و **Telegram X** پشتیبانی می‌کند:

- باز کردن اپ و رفتن به chat / group / channel نام‌دار
- ارسال پیام با تأیید کاربر
- خواندن پیام‌های قابل‌مشاهده و خلاصه‌سازی محدود
- مانیتور notification و auto-reply، در صورت فعال‌کردن صریح توسط کاربر
- Saved Messages → پیدا کردن کنترل Play قابل‌مشاهده → پخش media

نمونه‌ها:

```text
تلگرامو باز کن، برو تو سیو مسیجم، یه آهنگ رندوم پلی کن
به تیم تو تلگرام X پیام بده انتشار نسخه انجام شد
پیام های جدید کانال خبر فوری در تلگرام رو تحلیل کن
```

برای درخواست سوم، آریا اول chat را با search داخلی Telegram باز می‌کند و سپس فقط پیام‌های **واقعاً قابل‌مشاهده** را می‌خواند؛ نباید محتوای دیده‌نشده را حدس بزند.

### مرورگر و اپ‌های دیگر

- جست‌وجوی صریح در browser بدون planning چندمرحله‌ای
- بازکردن اپ، گرفتن UI tree، تایپ، tap، swipe و خواندن نتیجهٔ روی صفحه
- در کارهای مبهم یا چندمرحله‌ای، Agent loop از مدل استفاده می‌کند.

---

## مدل‌ها و سرعت

آریا دو حالت دارد:

| حالت | کاربرد |
|---|---|
| **Local** | inference روی خود گوشی با LiteRT-LM و مدل Gemma؛ مناسب حریم خصوصی و کارهای معمول، ولی سرعت به RAM/CPU/GPU گوشی وابسته است. |
| **Cloud** | provider سازگار با OpenAI یا Anthropic؛ برای reasoning پیچیده‌تر، با این تفاوت که context موردنیاز task به provider منتخب ارسال می‌شود. |

برای جلوگیری از گیرکردن طولانی در Local mode:

- schema ابزارها متناسب با task کوچک می‌شوند؛
- مسیرهای صریح قبل از LLM route می‌شوند؛
- هر inference محلی سقف زمانی دارد؛
- کل task محلی نیز زمان محدود دارد؛
- اگر مدل یا runtime واقعاً گیر کند، task با خطای روشن متوقف می‌شود، نه اینکه چندین دقیقه روی «thinking» بماند.

مدل‌های محلی پیشنهادی در تنظیمات:

- **Gemma 4 E2B** — حدود 2.6GB، مناسب دستگاه‌های با حداقل 8GB RAM
- **Gemma 4 E4B** — حدود 3.6GB، مناسب دستگاه‌های با حداقل 10GB RAM

---

## معماری

```text
UI / TaskFlowController
        │
        ▼
TaskOrchestrator
 ├─ Fast deterministic router
 ├─ Built-in skills
 └─ HermesAgentService
        ├─ Local LiteRT-LM یا Cloud LLM
        ├─ ToolRegistry
        ├─ Memory / Skills / Sessions
        ├─ Cron
        └─ MCP (اختیاری)
                │
                ▼
Accessibility / Notifications / Android Intents
```

### ابزارهای دستگاه

بسته به permissionهای فعال‌شده، آریا می‌تواند از این قابلیت‌ها استفاده کند:

- Accessibility: مشاهدهٔ tree صفحه، tap، swipe، input و screenshot
- Notification Access: خواندن اعلان‌های فعال و مانیتور پیام‌ها
- اطلاعات دستگاه: باتری، شبکه، storage، Bluetooth و فهرست اپ‌ها
- ابزارهای فارسی: تقویم شمسی
- ابزارهای Huawei/EMUI: shortcutهای تنظیمات سازگار با EMUI

---

## حریم خصوصی و ایمنی

1. **Local mode** inference را روی دستگاه انجام می‌دهد؛ اما permissionهایی مانند Accessibility و Notification Access همچنان دادهٔ گوشی را در اختیار خود اپ قرار می‌دهند.
2. در **Cloud mode**، متن task، context لازم، و نتیجهٔ ابزارهای موردنیاز ممکن است برای provider تنظیم‌شده ارسال شوند.
3. عملیات حساس مانند ارسال پیام، تماس، auto-reply و ارسال فایل confirmation می‌خواهند. این confirmation جایگزین احتیاط کاربر نیست.
4. External Automation و Local Config Server به‌صورت پیش‌فرض نباید برای اپ‌ها/شبکه‌های غیرقابل اعتماد فعال شوند.
5. از automation در اپ‌های مالی، OTP، رمز عبور و پرداخت استفاده نکنید.
6. Debug report را پیش از share بررسی کنید؛ ممکن است شامل اطلاعات تشخیصی دستگاه یا log باشد.

---

## نصب و به‌روزرسانی

1. APK را فقط از [GitHub Releases](https://github.com/asmoia/arya-agent/releases/latest) دریافت کنید.
2. فایل `SHA256SUMS.txt` همان release را برای بررسی hash دانلود کنید.
3. APK را نصب کنید و از Settings، مدل و permissionهای موردنیاز را تنظیم کنید.
4. برای update درجا، APK باید با همان release signing key امضا شده باشد.

نمونهٔ بررسی checksum در کامپیوتر:

```bash
sha256sum --check SHA256SUMS.txt
```

> buildهای Debug برای تست‌اند و ممکن است روی release signed قبلی install/update درجا نشوند.

---

## ساخت از سورس

نیازمندی‌ها:

- JDK 17
- Android SDK API 36
- Android Studio جدید یا Gradle Wrapper پروژه

```bash
git clone https://github.com/asmoia/arya-agent.git
cd arya-agent
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

APK debug در مسیر زیر تولید می‌شود:

```text
app/build/outputs/apk/debug/
```

CI روی GitHub شامل unit test، build و smoke matrix برای APIهای 29، 31، 33، 34 و 35 است.

---

## پروژه‌ها و فناوری‌های استفاده‌شده

آریا یک پروژهٔ مستقل با package و release pipeline خودش است، اما از پروژه‌ها و فناوری‌های زیر استفاده یا الهام معماری گرفته است:

| پروژه / فناوری | نقش در آریا |
|---|---|
| [agents-io/PokeClaw](https://github.com/agents-io/PokeClaw) | پایهٔ fork و بخشی از معماری اولیهٔ Android phone-agent. Attribution و NOTICE اصلی حفظ شده‌اند. |
| [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) | الهام معماری برای memory، skills، sessions و چرخهٔ Hermes. آریا یک پیاده‌سازی Kotlin توکار دارد؛ Hermes Python gateway یا Termux را bundle نمی‌کند. |
| [Google AI Edge LiteRT-LM](https://ai.google.dev/edge/litert/llm/overview) | runtime inference مدل‌های محلی روی Android. |
| [Gemma](https://ai.google.dev/gemma) / مدل‌های LiteRT community | مدل‌های محلی قابل‌انتخاب در تنظیمات آریا. |
| [LangChain4j](https://github.com/langchain4j/langchain4j) | abstraction برای providerهای LLM و tool specificationها. |
| [AndroidX و Jetpack Compose](https://developer.android.com/jetpack/compose) | UI و lifecycle Android. |
| [OkHttp](https://square.github.io/okhttp/)، [Retrofit](https://square.github.io/retrofit/)، [Gson](https://github.com/google/gson) | ارتباط شبکه و serialization. |
| [Tencent MMKV](https://github.com/Tencent/MMKV) | key-value storage محلی. |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | config server محلی و محدودشدهٔ اختیاری. |

فهرست دقیق dependencyها و نسخه‌ها در [`gradle/libs.versions.toml`](gradle/libs.versions.toml) و [`app/build.gradle.kts`](app/build.gradle.kts) قرار دارد.

---

## مشارکت و گزارش مشکل

- برای bug مربوط به سرعت یا automation، device model، Android version، مدل فعال، متن دقیق task و نتیجهٔ واقعی را ذکر کنید.
- برای vulnerability یا افشای اطلاعات، لطفاً گزارش را عمومی نکنید تا مسیر گزارش امنیتی رسمی پروژه تکمیل شود.
- پیش از pull request، `./gradlew testDebugUnitTest` را اجرا کنید.

---

## لایسنس و attribution

آریا تحت [Apache License 2.0](LICENSE) منتشر می‌شود. attributionهای لازم پروژه‌های پایه در [NOTICE](NOTICE) نگهداری شده‌اند.
