#!/usr/bin/env bash
# Non-interactive local-generation smoke for API 31 emulator.
set -uo pipefail

PKG=io.agents.arya
RECV=${PKG}/.debug.DebugTaskReceiver
MODEL_NAME=Qwen_Qwen3-0.6B-Q4_K_M.gguf
MODEL_SRC="${MODEL_CACHE:-$HOME/.cache/arya-models}/$MODEL_NAME"
EXT_DIR="/storage/emulated/0/Android/data/${PKG}/files/models"
EXT_PATH="${EXT_DIR}/${MODEL_NAME}"
OUT="${LAB_OUT:-.}"
mkdir -p "$OUT"

adb wait-for-device
APK="$(find app/build/outputs/apk/debug apk -name '*.apk' 2>/dev/null | head -1 || true)"
echo "APK=$APK"
adb install -r -t "$APK" || exit 1
adb shell am start -W -n ${PKG}/.ui.splash.SplashActivity || true
sleep 6

test -f "$MODEL_SRC" || exit 1
SZ=$(stat -c%s "$MODEL_SRC")
echo "HOST_MODEL_BYTES=$SZ"
[ "$SZ" -ge 450000000 ] || exit 1

echo "::group::push GGUF to app external files"
adb shell mkdir -p "$EXT_DIR"
adb push "$MODEL_SRC" "$EXT_PATH"
adb shell ls -l "$EXT_DIR"
echo "::endgroup::"

adb logcat -c || true
nohup adb logcat -v time > "$OUT/logcat-full.txt" 2>&1 &
echo $! > "$OUT/logcat.pid"
sleep 1

echo "::group::explicit config + probe"
adb shell am broadcast -n "$RECV" -a ${PKG}.DEBUG_TASK --es task "config:" \
  --es provider LOCAL --es base_url "$EXT_PATH" --es model_name qwen3-0.6b
sleep 2
adb shell am broadcast -n "$RECV" -a ${PKG}.DEBUG_TASK --es task "probe:"
sleep 2
echo "::endgroup::"

adb shell dumpsys meminfo ${PKG}:engine > "$OUT/meminfo-before.txt" || true
START=$(date +%s)
adb shell am broadcast -n "$RECV" -a ${PKG}.DEBUG_TASK --es chat "Say hi in three words."
echo "SMOKE_CHAT_SENT t=$START"

DEADLINE=$((START + 90))
HIT=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if grep -q 'LAB_FIRST_TOKEN' "$OUT/logcat-full.txt" 2>/dev/null; then
    HIT=1
    break
  fi
  sleep 3
done

kill "$(cat "$OUT/logcat.pid")" 2>/dev/null || true
adb shell dumpsys meminfo ${PKG}:engine > "$OUT/meminfo-after.txt" || true
adb shell ps -A | grep arya | tee "$OUT/ps-arya.txt" || true
adb shell screencap -p /sdcard/smoke.png || true
adb pull /sdcard/smoke.png "$OUT/smoke.png" || true
grep -E 'LAB_|DebugTask|Engine|LocalLlm|NeedsSetup|AryaEngine|broadcast' "$OUT/logcat-full.txt" \
  | tee "$OUT/logcat-engine.txt" || true

if [ -n "$HIT" ]; then
  echo "SMOKE_PASS first_token_s=$(( $(date +%s) - START ))" | tee "$OUT/smoke-result.txt"
  exit 0
fi
echo "SMOKE_FAIL no LAB_FIRST_TOKEN within 90s" | tee "$OUT/smoke-result.txt"
exit 1
