# USER ACTION REQUIRED — Arya 1.2.5

**Do not uninstall. Do not Clear Data.** Install the signed 1.2.5 APK over 1.2.4.

Your 1.7B file stays at:

`/storage/emulated/0/Android/data/io.agents.arya/files/models/Qwen_Qwen3-1.7B-Q4_K_M.gguf`

The internal copy at `files/models/fast/` is also kept.

## What was wrong on 1.2.4

The engine log you sent is conclusive:

1. Copy to internal storage **worked** (1 282 439 584 bytes in ~2.4s).
2. `nativeLoadModel` “succeeded” in **3 seconds** — that was still a lazy mmap.
3. Chat showed **Model ready. Writing…**
4. `:engine` **died ~8 seconds later** (new pid at 14:42:20). There is **no** `generate` / `LAB_FIRST_TOKEN` line.
5. The UI waited 120s and timed out. Phone Settings ~70 MB is the **UI** process; weights live in `:engine`.

## What 1.2.5 does

First 1.7B chat **reads ~1.2 GB into RAM**. Status will say **Reading weights into RAM** and can take **30–90 seconds**. Leave the app open. After that, send again — answers should start in a few seconds.

If it still fails, send a new debug ZIP. Look for `engine_logs/native-crash.txt` and `VmRSS` lines in `arya-engine.log`.

## Security

A classic GitHub PAT was pasted in chat earlier. **Rotate it** (it can delete the repo). Do not commit tokens.
