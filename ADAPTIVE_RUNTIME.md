# Adaptive Hermes Runtime (workspace — not pushed unless you ask)

## Modes (Settings → حالت فکر Task)
- **ADAPTIVE** (default): picks Instant / Thinking / High from task complexity + free RAM
- **INSTANT**: 3–4 rounds, tightest sampler/settle, tighter sampler
- **THINKING**: balanced
- **HIGH**: more rounds for messy multi-app UI

## Action-first
- After open_app/tap/swipe: auto nudge to continue with tools (no silent stop)
- Essay without tools on phone tasks: force tool turn
- Repeat same action too many times: force strategy change
- Live status lines in chat (`⏳ …`) + notification text

## App keeper
- Foreground service while task depth > 0
- START_STICKY
- Trim-memory tracking; tighter caps under low RAM

## Not a timeout
No forced wall-clock kill; visibility + continue logic instead.

## E4B only
All knobs assume on-device E4B; no cloud required.
