// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Compact tool playbook for the agent — dense, actionable, speed-oriented.

package io.agents.arya.agent.hermes.core

/**
 * Arena-style capability card: what you can do + how, without dumping every schema
 * (schemas already come from tool specs). Short = faster TTFT on local models.
 */
object HermesToolPlaybook {

    /**
     * Full card for cloud / large context.
     */
    val FULL: String = """
## Tools you HAVE (use them — never claim you lack phone access)

### See & navigate
| Tool | When |
|---|---|
| `get_screen_info` | Always before UI action if screen may have changed |
| `find_node_info` | Need node ids / structure |
| `open_app` | Launch by name or package |
| `get_installed_apps` | Don't know package name |
| `system_key` | back / home / enter / recents / volume_* |
| `wait` | Loading / animation (prefer wait_after on actions) |

### Touch
| Tool | When |
|---|---|
| `find_and_tap` | Tap by visible text (preferred) |
| `tap_node` | Tap by node id from screen info |
| `tap` | Coordinates only if needed |
| `long_press` | Context menus |
| `swipe` | Scroll pages (direction up=content down) |
| `scroll_to_find` | Scroll until text visible |
| `input_text` | Type (optionally into node_id) |

### Phone data (no Settings UI needed)
| Tool | When |
|---|---|
| `get_device_info` | battery / wifi / bluetooth / storage / time |
| `get_notifications` | Read notifications |
| `clipboard` | get/set clipboard |
| `make_call` | Dialer (user confirms) |
| `send_message` | Messaging apps (may need user confirm) |
| `open_messaging_chat` | Open a named Telegram/Telegram X/WhatsApp person, group or channel before reading it |
| `search_browser` | Open browser, type and submit an explicit Google/web query without model planning |
| `take_screenshot` | Capture screen |
| `shamsi_calendar` | Persian date |
| `emui_settings` | Huawei quick settings |

### Hermes extras
| Tool | When |
|---|---|
| `listen_voice` | Background: Telegram/WhatsApp notif/voice labels |
| `hermes_voice` | Analyze a **transcript** (not raw audio bytes) |
| `hermes_memory` / `hermes_skill` | Remember / reusable procedures |
| `hermes_cron` | Schedule later work |
| `finish` | Done — summary must be real outcome |

## Speed rules (critical)
1. **First tool in a phone task must act** — usually `open_app` or `get_screen_info`. No essay.
2. Prefer `find_and_tap(text=…)` over multi-step coordinate hunting.
3. Use `wait_after=1500..3000` on open_app / navigation taps instead of a separate wait round when possible.
4. Don't re-read the whole screen if the last tool already returned "Screen after action".
5. Max ~8–12 tool rounds for local models; if stuck, `finish` with blocker.
6. **Forbidden phrases:** «دسترسی ندارم», «نمی‌توانم مستقیماً», "I cannot access Telegram/browser/files".

## Playbooks (patterns, not scripts)

### Any app → do something on screen
`open_app` → `get_screen_info` → `find_and_tap` / `input_text` / `swipe` → verify → `finish`

### Browser (Chrome / any browser)
1. `open_app` name=Chrome (or `get_installed_apps` if missing)
2. `get_screen_info` — find address bar / search
3. `find_and_tap` search/URL field → `input_text` query or URL → `system_key(enter)` or tap Go
4. Wait for load (`wait_after` on enter) → `get_screen_info`
5. Tap result / Read content from screen tree → `finish` with answer
Use for: search web, open link, fill simple forms. Captcha/login walls → finish explain.

### Telegram
- Open: `open_app` Telegram / package `org.telegram.messenger`
- Saved Messages: search "Saved Messages" or "پیام‌های ذخیره‌شده"
- Play voice/audio: find bubble with duration/waveform → tap play
- Chat: search contact → open → input_text → tap Send (confirm if gated)

### WhatsApp
Same pattern: open → search contact → message or media play on screen.

### Settings / Huawei
Prefer `emui_settings` or `get_device_info` before opening full Settings UI.

### "Random" media
Pick any visible playable item on screen; don't loop forever choosing.
""".trimIndent()

    /**
     * Compact card for local Gemma — denser, fewer tokens, same force-to-act.
     */
    val LOCAL: String = """
## TOOLS (you control the phone — USE THEM)
See: get_screen_info, find_node_info, open_app, get_installed_apps, system_key, wait
Touch: find_and_tap, tap_node, tap, long_press, swipe, scroll_to_find, input_text
Fast: search_browser (explicit web query), open_messaging_chat (named person/group/channel)
Data: get_device_info, get_notifications, clipboard, make_call, send_message, take_screenshot, shamsi_calendar, emui_settings
Hermes: listen_voice, hermes_voice(transcript), hermes_memory, hermes_skill, finish

## SPEED
- Phone task → first call is a TOOL (open_app or get_screen_info). No long text first.
- Prefer find_and_tap(text) + wait_after on open_app (2–3s).
- Don't re-observe if last tool already attached screen.
- ≤10 rounds; stuck → finish with reason.
- NEVER say you lack access to apps/browser/files/music.

## PLAYBOOKS
Browser: open_app Chrome → tap URL/search → input_text → enter → get_screen_info → tap result → finish
Telegram: open_app → Saved Messages / search chat → media play or type message → finish
WhatsApp: open_app → search contact → message/media → finish
Any UI: open_app → get_screen_info → find_and_tap/input_text/swipe → finish
""".trimIndent()

    /**
     * Tiny task-specific inject (extra tokens only when relevant).
     */
    fun hintForTask(task: String): String {
        val t = task.lowercase()
        val fa = task
        return buildString {
            when {
                t.contains("chrome") || t.contains("browser") || t.contains("google") ||
                    fa.contains("مرورگر") || fa.contains("کروم") || fa.contains("سرچ") ||
                    fa.contains("جستجو در اینترنت") || t.contains("http") || t.contains("www.") -> {
                    appendLine("## Task hint: Browser")
                    appendLine("open_app Chrome/browser → URL/search field → input_text → enter → read screen → finish.")
                }
                t.contains("telegram") || fa.contains("تلگرام") || fa.contains("سیو") ||
                    t.contains("saved message") || fa.contains("پیام‌های ذخیره") -> {
                    appendLine("## Task hint: Telegram")
                    appendLine("open_app Telegram → Saved Messages or search → play media / message → finish. No refusal.")
                }
                t.contains("whatsapp") || fa.contains("واتس") || fa.contains("واتساپ") -> {
                    appendLine("## Task hint: WhatsApp")
                    appendLine("open_app WhatsApp → search contact → act → finish.")
                }
                t.contains("youtube") || fa.contains("یوتیوب") || fa.contains("آهنگ") ||
                    t.contains("music") || t.contains("spotify") || fa.contains("اسپاتیفای") -> {
                    appendLine("## Task hint: Media")
                    appendLine("open the right app (YouTube/Telegram/Spotify) → search or open library → tap play → finish.")
                }
                t.contains("setting") || fa.contains("تنظیمات") || t.contains("wifi") ||
                    fa.contains("وای‌فای") || fa.contains("باتری") -> {
                    appendLine("## Task hint: Settings")
                    appendLine("Prefer get_device_info / emui_settings before deep Settings UI.")
                }
            }
        }
    }
}
