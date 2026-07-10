# Arya Agent — راهنمای عملی توسعه و QA

این سند برای تغییر امن رفتار Agent در آریاست. هدف اصلی: **کار درست، قابل‌مشاهده و سریع روی گوشی واقعی**؛ نه صرفاً یک demo.

## اصل‌های غیرقابل مذاکره

1. پیش از تغییر Agent، مسیر deterministic را بررسی کنید. اگر task پارامترهای روشن دارد، LLM نباید فقط برای تصمیم ساده اجرا شود.
2. نتیجهٔ ابزار باید واقعی باشد. «success» فقط وقتی معتبر است که ابزار واقعاً app/UI موردنظر را باز یا تغییر داده باشد.
3. هیچ تغییر speed نباید confirmation عملیات حساس، cancellation یا محدودیت زمان را دور بزند.
4. برای permission، OTP، banking، wallet، password manager و پرداخت، رفتار fail-safe و user-controlled لازم است.
5. هر task جدید باید حداقل یک تست parser/router یا unit test داشته باشد؛ هر تغییر UI/runtime باید smoke matrix را سبز نگه دارد.

## مسیر task در آریا

```text
TaskFlowController
→ PipelineRouter
  → DirectIntent / DirectTool / Skill
  → PrimeThenAgent (navigation deterministic + summary محدود)
  → HermesAgentService
→ ToolRegistry
→ Accessibility / Notification / Android APIs
```

### ترتیب بهینه

1. **Direct route:** بازکردن اپ، اطلاعات دستگاه، browser search، پیام با پارامتر صریح.
2. **Skill:** workflow کوچک و bounded مثل Telegram Saved Media.
3. **Prime then Agent:** ابتدا chat/group/channel را با ابزار باز کن، سپس یک پاس محدود برای خواندن صفحه.
4. **Agent loop:** فقط برای کار مبهم یا چنداپی.

## Local runtime

- مدل local برای taskهای پیچیده مفید است، اما باید latency محدود داشته باشد.
- schema ابزارها باید task-aware باشند.
- هر inference و کل task زمان محدود دارند؛ timeout باید به UI و log گزارش شود.
- retry خطای local معمولاً سرعت را بهتر نمی‌کند؛ قبل از retry علت failure را بررسی کنید.

## چک‌لیست QA قبل از tag

1. `./gradlew testDebugUnitTest`
2. `./gradlew assembleDebug`
3. GitHub emulator matrix API 29/31/33/34/35 سبز باشد.
4. روی دستگاه واقعی حداقل این موارد را بررسی کنید:
   - بازکردن سادهٔ app
   - browser search
   - Telegram/Telegram X navigation
   - cancellation حین local inference
   - confirmation ارسال پیام
   - بازگشت بعد از task
5. versionCode/versionName و `CHANGES.md` را به‌روزرسانی کنید.
6. فقط بعد از موارد بالا tag release بسازید.

## گزارش bug مفید

گزارش باید شامل این موارد باشد:

- مدل گوشی، ROM، نسخهٔ Android
- نسخهٔ Arya و Local/Cloud mode
- متن دقیق task
- انتظار کاربر و نتیجهٔ واقعی
- وضعیت Accessibility و Notification Access
- debug report پس از بازبینی دادهٔ حساس

## منابع کد

- Router: `agent/PipelineRouter.kt`
- Task lifecycle: `TaskOrchestrator.kt`, `TaskFlowController.kt`
- Hermes runtime: `agent/hermes/core/`
- ابزارها: `tool/impl/`
- تست‌ها: `app/src/test/`
