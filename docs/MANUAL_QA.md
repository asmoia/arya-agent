# Manual device checklist (≤ 25 items)

Owner runs these on a physical phone after `assembleDebug`.

## Group K — kill resilience
1. Start a local generation. `adb shell kill -9 <engine-pid>`. UI stays up, error chip, next send rebinds.
2. Force-stop app mid-task. Relaunch shows restored-FAILED, not a zombie running bar.
3. Fill RAM with other apps. Engine unloads (notification gone). Next use reloads.

## Group T — tiers
4. `تلگرامو باز کن` or `open settings` — <1s, no model load.
5. `how much battery` / `read clipboard` — device-data tools.
6. Explicit Google search (`search google for cats`).
7. Explicit send-message with confirm gate.
8. One local Tier2-lite extraction on a 4–6 GB phone.
9. One cloud Tier3 task if a key is configured.

## Group L — latency
10. Record cold vs warm first-token ms (InferenceTelemetry). Target warm ≤ 40% of cold.

## Group P — permissions
11. Each Settings row opens the correct system screen (PermissionRouter).
12. Revoke Accessibility mid-task → task fails with a clear English/Persian message.

## Group U — UI
13. Rotate during a stream — stream continues (ChatRuntimeRegistry).
14. Long model name ellipsizes in settings.
15. RTL glance on chat + settings (fa locale).
16. Stop from chat bar, floating circle, and notification — all call `requestStop()`, one CANCELLED.

## Group V — voice
17. Mic button opens listening sheet; partials render; final lands in input (or auto-send).
18. Unavailable recognizer shows fallback message.
19. TTS toggle speaks only short Tier1 answers.

## Group O — overlay
20. Floating circle opens assistant sheet over another app (overlay permission).

## Group M — models
21. 3 GB device: 4B row disabled. 8 GB: 4B enabled.
22. Custom URL slot accepts a `.gguf` after StatFs check.
