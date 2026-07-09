# پایداری آریا روی OEMها (هواوی / شیائومی / سامسونگ)

## مدل لوکال پاک نمی‌شود (مگر Clear Data)

مسیر:

- ترجیح: `Android/data/io.agents.arya/files/models/`
- پشتیبان: `files/models` داخلی اپ

| عمل | مدل | حافظه Hermes | API key |
|---|---|---|---|
| آپدیت APK | ✅ می‌ماند | ✅ | ✅ |
| Clear Cache | ✅ | ✅ | ✅ |
| Force Stop | ✅ | ✅ | ✅ |
| ریستارت گوشی | ✅ | ✅ | ✅ |
| Clear Data | ❌ | ❌ | ❌ |
| Uninstall | ❌ | ❌ | ❌ |

قبل از Clear Data: **Settings → پشتیبان Hermes (Export)**

## دسترسی‌های لازم (کافی برای Siri-like phone agent)

| دسترسی | لازم؟ | برای چه |
|---|---|---|
| Accessibility | **ضروری** | خواندن صفحه، تپ، سوایپ |
| Notification Access | **برای مانیتور پیام** | خواندن اعلان‌ها / auto-reply |
| Overlay | توصیه‌شده | حباب وضعیت task |
| Notifications (POST) | توصیه‌شده | foreground service |
| Battery unrestricted | **قویاً توصیه‌شده** | زنده ماندن در پس‌زمینه |
| Contacts | برای نام مخاطب در تماس/پیام | اختیاری ولی مفید |
| Storage / All files | بعضی OEMها برای مدل/فایل | |
| Internet | cloud LLM + آپدیت | |

**عمداً نداریم / لازم نیست برای هسته:**
- `CALL_PHONE` مستقیم — از `ACTION_DIAL` استفاده می‌شود (تأیید کاربر در دیالر)
- دسترسی SMS کامل — از UI اپ پیام‌رسان + Accessibility
- Location / Camera دائم — فقط با intent اپ دوربین در shortcutها

## چک‌لیست هواوی / EMUI / Harmony

1. Accessibility → آریا ON  
2. باتری → راه‌اندازی برنامه → آریا → **دستی** (همه سه‌تایی دستی/مجاز)  
3. باتری → مصرف بیشتر → بدون محدودیت  
4. اعلان‌ها مجاز  
5. مدیریت تلفن → محافظت → آریا را از لیست kill خارج کن  

## شیائومی / HyperOS

1. Autostart ON  
2. ذخیره باتری → بدون محدودیت  
3. قفل کردن اپ در Recent (قفل)  
4. نمایش پنجره‌های پاپ‌آپ / overlay  

## سامسونگ / One UI

1. Sleeping apps → آریا را حذف کن  
2. Deep sleeping → نباشد  
3. Never sleeping apps → اضافه کن (اختیاری)  

## بعد از ریبوت

- Accessibility معمولاً می‌ماند  
- Cronهای Hermes دوباره schedule می‌شوند (`BootReceiver`)  
- Foreground service عمداً روی boot استارت نمی‌شود (کمتر مزاحم) — با باز کردن اپ / شروع task فعال می‌شود  
