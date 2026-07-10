# Arya — الگوی پاسخ به issueها

این فایل جای replyهای مربوط به پروژه‌های دیگر را می‌گیرد. پاسخ issue آریا باید بر اساس نسخهٔ فعلی و reproduction واقعی نوشته شود.

## اطلاعات لازم از گزارش‌دهنده

```text
- نسخهٔ Arya و build type
- مدل گوشی، ROM و نسخهٔ Android
- Local یا Cloud mode و نام مدل
- متن دقیق task
- نتیجهٔ مورد انتظار و نتیجهٔ واقعی
- وضعیت Accessibility / Notification Access
- آیا task قابل تکرار است؟
- Debug report بازبینی‌شده، در صورت نبود دادهٔ حساس
```

## قالب پاسخ اولیه

```text
ممنون از گزارش. برای بررسی دقیق لطفاً نسخهٔ Arya، مدل گوشی، نسخهٔ Android و متن دقیق task را بفرستید.

اگر امکانش هست یک‌بار reproduction کنید و بعد از Settings → About → Share Debug Report، فایل را فقط پس از بررسی اطلاعات حساس attach کنید.

برای taskهای local، نام مدل و حالت Thinking (Instant/Thinking/High) هم لازم است.
```

## قالب پاسخ برای automation ناموفق

```text
آریا نباید موفقیت را بدون نتیجهٔ قابل مشاهده اعلام کند. لطفاً بگویید task در کدام مرحله متوقف شد: بازکردن app، پیدا کردن UI، تایپ، submit، یا خواندن نتیجه.

اگر Telegram/Telegram X یا browser درگیر است، نام و نسخهٔ همان app نیز مفید است؛ UI این اپ‌ها بین نسخه‌ها تفاوت دارد.
```
