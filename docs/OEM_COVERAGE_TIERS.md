# Arya — لایه‌های تست Android و OEM

## Tier 1 — GitHub Emulator Matrix

Matrix روی APIهای 29، 31، 33، 34 و 35 اجرا می‌شود و این موارد را بررسی می‌کند:

- build و install APK
- launch app
- process alive پس از launch
- crashهای اولیه و incompatibilityهای API

این تست جای دستگاه واقعی را نمی‌گیرد؛ GPU واقعی، سیاست battery OEM و تفاوت Accessibility در One UI / HyperOS / EMUI را پوشش نمی‌دهد.

## Tier 2 — Firebase Test Lab

در صورت تنظیم secrets زیر، workflow می‌تواند smoke روی دستگاه‌های واقعی cloud اجرا کند:

```text
GCP_PROJECT_ID
GCP_SA_KEY_JSON
```

بدون این secrets، workflow به‌صورت واضح skip می‌شود؛ نباید نتیجهٔ skip را معادل تست واقعی دانست.

## Tier 3 — دستگاه‌های واقعی

برای releaseهای مهم، این سناریوها روی دستگاه واقعی بررسی شوند:

- Local model روی GPU و CPU fallback
- Telegram و Telegram X
- Chrome یا browser OEM
- cancel حین inference
- Accessibility reconnect پس از background / reboot
- ارسال پیام با confirmation
- نصب update signed روی release قبلی

## گزارش OEM

برای هر bug، مدل دستگاه، ROM، Android version، مدل فعال، permission state و متن دقیق task را ثبت کنید. Debug report را پیش از share از نظر دادهٔ حساس بررسی کنید.
