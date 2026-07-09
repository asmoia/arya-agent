# 🤖 آریا (Arya) — Fork of PokeClaw

> دستیار هوشمند فارسی برای اندروید — آفلاین، متن‌باز، با کنترل کامل گوشی

## تغییرات نسبت به PokeClaw اصلی

| تغییر | توضیح |
|---|---|
| 🇮🇷 شخصیت فارسی «آریا» | System prompt فارسی-انگلیسی دو‌زبانه |
| 📅 تقویم شمسی | ابزار `shamsi_calendar` — تاریخ شمسی + تبدیل |
| ⚙️ تنظیمات EMUI | ابزار `emui_settings` — کنترل مخصوص هواوی |
| 🔤 رابط فارسی | فایل `values-fa/strings.xml` کامل |
| 🏷️ پکیج جدید | `io.agents.arya` — نصب کنار PokeClaw اصلی |

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
