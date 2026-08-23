# Arya user manual (English)

## First run
1. Install the debug/release APK.
2. Open Arya → grant Accessibility, overlay, notifications, battery unrestricted.
3. Optional: Settings → LLM Config → pick a local Qwen3 GGUF or paste a cloud key.
4. First local load runs a ~15s device bench once.

## Everyday use
- **Chat** for questions. **Task** (send as command) for phone control.
- Fast commands (`open settings`, `how much battery`, `تلگرامو باز کن`) skip the model.
- Mic: tap the mic, speak, confirm. Toggle auto-send and TTS in Settings.
- Floating circle: tap for the assistant sheet over another app; long-press for voice.

## Models
See `MODELS.md`. Phones under 8 GB cannot run local Tier3 agent loops.

## If something breaks
Settings → Share Debug Report. Engine crash is recoverable — send again.
