#!/usr/bin/env bash
# Non-interactive local-generation smoke for API 31 emulator.
set -euo pipefail

PKG=io.agents.arya
RECV=${PKG}/.debug.DebugTaskReceiver
MODEL_NAME=Qwen_Qwen3-0.6B-Q4_K_M.gguf
MODEL_SRC="${MODEL_CACHE:-$HOME/.cache/arya-models}/$MODEL_NAME"
OUT="${LAB_OUT:-.}"
mkdir -p "$OUT"

adb wait-for-device
APK="$(find app/build/outputs/apk/debug apk -name '*.apk' 2>/dev/null | head -1 || true)"
echo "APK=$APK"
adb install -r -t "$APK"
adb shell am start -W -n ${PKG}/.ui.splash.SplashActivity || true
sleep 6

test -f "$MODEL_SRC"
SZ=$(stat -c%s "$MODEL_SRC")
echo "HOST_MODEL_BYTES=$SZ"
if [ "$SZ" -lt 450000000 ]; then
  echo "::error::GGUF too small ($SZ) — likely truncated"
  exit 1
fi

echo "::group::place model into app filesDir (run-as)"
adb push "$MODEL_SRC" /data/local/tmp/$MODEL_NAME
adb shell run-as $PKG mkdir -p files/models
# run-as cannot always read /data/local/tmp; copy via dd/cat as root-less:
adb shell "cp /data/local/tmp/$MODEL_NAME /sdcard/$MODEL_NAME"
adb shell run-as $PKG cp /sdcard/$MODEL_NAME files/models/$MODEL_NAME || \
  adb shell "run-as $PKG sh -c 'cat /sdcard/$MODEL_NAME > files/models/$MODEL_NAME'"
# also drop into official external files dir
EXT="/storage/emulated/0/Android/data/${PKG}/files/models"
adb shell mkdir -p "$EXT"
adb shell cp /sdcard/$MODEL_NAME "$EXT/$MODEL_NAME" || true
echo "internal:"
adb shell run-as $PKG ls -l files/models || true
echo "external:"
adb shell ls -l "$EXT" || true
echo "::endgroup::"

INT_PATH="/data/user/0/${PKG}/files/models/${MODEL_NAME}"
EXT_PATH="${EXT}/${MODEL_NAME}"

adb logcat -c || true
nohup adb logcat -v time > "$OUT/logcat-full.txt" 2>&1 &
echo $! > "$OUT/logcat.pid"
sleep 1

echo "::group::config + probe"
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es task "config:" \
  --es provider LOCAL --es base_url "$INT_PATH" --es model_name qwen3-0.6b
sleep 1
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es task "config:" \
  --es provider LOCAL --es base_url "$EXT_PATH" --es model_name qwen3-0.6b
sleep 1
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es task "probe:"
sleep 2
echo "::endgroup::"

adb shell dumpsys meminfo ${PKG}:engine > "$OUT/meminfo-before.txt" || true
START=$(date +%s)
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es chat "Say hi in three words."
echo "SMOKE_CHAT_SENT t=$START"

DEADLINE=$((START + 90))
HIT=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if grep -E 'LAB_FIRST_TOKEN|LAB_PROBE|nativeGenerateStream|debug chat' "$OUT/logcat-full.txt" \
      | tee "$OUT/logcat-smoke-tail.txt" | grep -q 'LAB_FIRST_TOKEN'; then
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
grep -E 'LAB_|DebugTask|Engine|LocalLlm|NeedsSetup|AryaEngine' "$OUT/logcat-full.txt" \
  | tee "$OUT/logcat-engine.txt" || true

if [ -n "$HIT" ]; then
  ELAPSED=$(( $(date +%s) - START ))
  echo "SMOKE_PASS first_token_s=$ELAPSED" | tee "$OUT/smoke-result.txt"
  exit 0
fi
echo "SMOKE_FAIL no LAB_FIRST_TOKEN within 90s" | tee "$OUT/smoke-result.txt"
exit 1
