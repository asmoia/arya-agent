# مشخصات Skill در Arya

## هدف

Skill یک workflow محدود و قابل‌پیش‌بینی است که قبل از agent loop اجرا می‌شود تا مدل محلی برای navigation ساده چندین دور inference انجام ندهد.

نمونهٔ مناسب: بازکردن Telegram Saved Messages و تلاش برای لمس کنترل Play قابل‌مشاهده.

## دو نوع Skill

### 1) Built-in Kotlin skills

در مسیر زیر تعریف می‌شوند:

```text
app/src/main/java/io/agents/arya/agent/skill/BuiltInSkills.kt
```

هر Skill شامل این بخش‌هاست:

- `id`
- `name` و `description`
- `category`
- `steps`
- `triggerPatterns` در صورت نیاز
- `fallbackGoal`

هر step یک `toolName`، پارامتر، optional flag و تعداد retry دارد.

### 2) Hermes Markdown skills

Skillهای persistent Hermes داخل app-private storage نگهداری می‌شوند:

```text
files/hermes/skills/<safe-id>.md
```

ساختار فایل:

```markdown
---
id: telegram-saved-media
name: Telegram Saved Media
improved_count: 0
triggers:
  - "پیام‌های ذخیره"
  - "saved messages"
---

# Steps

1. وضعیت صفحه را بررسی کن.
2. فقط از ابزارهای لازم استفاده کن.
3. اگر نتیجه قابل مشاهده نیست، ادعای موفقیت نکن.
```

## قواعد ایمنی و کیفیت

1. Skill فقط برای workflowهای تکراری و bounded مناسب است.
2. Skill نباید blind tap روی محتوای حساس انجام دهد.
3. در failure، fallback کوتاه و context-aware بنویسید؛ نه یک task عمومی که از ابتدا navigation را تکرار کند.
4. `send_message`، `make_call` و عملیات بیرونی همچنان از confirmation عبور می‌کنند.
5. برای هر matcher/skill جدید unit test اضافه کنید.
6. متن Markdown skill را دادهٔ غیرقابل‌اعتماد تلقی کنید؛ auto-learning نباید secret یا instruction مخرب را persist کند.
