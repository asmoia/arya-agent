#!/usr/bin/env bash
# Non-interactive local-generation smoke for API 31 emulator.
# install → push Qwen3-0.6B → DEBUG_TASK chat → assert LAB_FIRST_TOKEN < 90s
set -euo pipefail

PKG=io.agents.arya
MODEL_NAME=Qwen_Qwen3-0.6B-Q4_K_M.gguf
MODEL_SRC="${MODEL_CACHE:-$HOME/.cache/arya-models}/$MODEL_NAME"
DEVICE_MODEL_DIR="/sdcard/Android/data/${PKG}/files/models"
MODEL_PATH="/storage/emulated/0/Android/data/${PKG}/files/models/${MODEL_NAME}"
OUT="${LAB_OUT:-.}"
mkdir -p "$OUT"

adb wait-for-device
APK="$(find app/build/outputs/apk/debug apk -name '*.apk' 2>/dev/null | head -1 || true)"
echo "APK=$APK"
adb install -r -t "$APK"
adb shell am start -W -n ${PKG}/.ui.splash.SplashActivity || true
sleep 5
adb shell mkdir -p "$DEVICE_MODEL_DIR"
test -f "$MODEL_SRC"
adb push "$MODEL_SRC" "$DEVICE_MODEL_DIR/$MODEL_NAME"
adb shell am broadcast -a ${PKG}.DEBUG_TASK --es task "config:" \
  --es provider LOCAL --es base_url "$MODEL_PATH" --es model_name qwen3-0.6b
sleep 2

adb shell dumpsys meminfo ${PKG}:engine > "$OUT/meminfo-before.txt" || true
adb logcat -c || true
START=$(date +%s)
adb shell am broadcast -a ${PKG}.DEBUG_TASK --es chat "Say hi in three words."
echo "SMOKE_CHAT_SENT t=$START"

DEADLINE=$((START + 90))
HIT=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if adb logcat -d -s AryaEngineLog:I AryaEngineJNI:I LocalLlmClient:I DebugTaskReceiver:I EngineService:I \
      | tee "$OUT/logcat-smoke-tail.txt" | grep -E 'LAB_FIRST_TOKEN|nativeGenerateStream|gen_tokens'; then
    HIT=1
    break
  fi
  sleep 3
done

adb logcat -d -v time > "$OUT/logcat-full.txt" || true
adb shell dumpsys meminfo ${PKG}:engine > "$OUT/meminfo-after.txt" || true
adb shell ps -A | grep arya | tee "$OUT/ps-arya.txt" || true
adb shell screencap -p /sdcard/smoke.png || true
adb pull /sdcard/smoke.png "$OUT/smoke.png" || true

if [ -n "$HIT" ]; then
  ELAPSED=$(( $(date +%s) - START ))
  echo "SMOKE_PASS first_token_s=$ELAPSED"
  echo "SMOKE_PASS first_token_s=$ELAPSED" >> "$OUT/smoke-result.txt"
  exit 0
fi
echo "SMOKE_FAIL no LAB_FIRST_TOKEN within 90s"
echo "SMOKE_FAIL" > "$OUT/smoke-result.txt"
exit 1
