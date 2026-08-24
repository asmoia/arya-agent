#!/usr/bin/env bash
# Install debug APK, push cached Qwen3-0.6B, start logcat + screenshot loop.
# Invoked from live-lab.yml inside android-emulator-runner (single script file
# because that action runs each YAML line as a separate sh -c).
set -euo pipefail

PKG=io.agents.arya
MODEL_NAME=Qwen_Qwen3-0.6B-Q4_K_M.gguf
MODEL_SRC="${MODEL_CACHE:-$HOME/.cache/arya-models}/$MODEL_NAME"
DEVICE_MODEL_DIR="/sdcard/Android/data/${PKG}/files/models"
LAB_DIR="${LAB_OUT:-$PWD/lab-out}"
mkdir -p "$LAB_DIR"

echo "::group::device"
adb wait-for-device
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
echo "::endgroup::"

echo "::group::install"
APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
if [ -z "$APK" ]; then
  APK="$(find . -name '*debug*.apk' | head -1)"
fi
echo "APK=$APK"
adb install -r -t "$APK"
echo "::endgroup::"

echo "::group::launch + model dir"
adb shell am start -W -n ${PKG}/.ui.splash.SplashActivity || true
sleep 4
adb shell mkdir -p "$DEVICE_MODEL_DIR"
if [ ! -f "$MODEL_SRC" ]; then
  echo "::error::GGUF missing at $MODEL_SRC"
  exit 1
fi
echo "Pushing $(stat -c%s "$MODEL_SRC") bytes"
adb push "$MODEL_SRC" "$DEVICE_MODEL_DIR/$MODEL_NAME"
adb shell ls -l "$DEVICE_MODEL_DIR"
# Also copy into app-private internal if scoped push landed
echo "::endgroup::"

echo "::group::activate local model"
MODEL_PATH="/storage/emulated/0/Android/data/${PKG}/files/models/${MODEL_NAME}"
adb shell am broadcast -a ${PKG}.DEBUG_TASK --es task "config:" \
  --es provider LOCAL --es base_url "$MODEL_PATH" --es model_name qwen3-0.6b
sleep 2
echo "::endgroup::"

echo "::group::logcat + screenshots"
adb logcat -c || true
nohup adb logcat -v time > "$LAB_DIR/logcat-full.txt" 2>&1 &
echo $! > "$LAB_DIR/logcat.pid"
(
  i=0
  while [ "$i" -lt 400 ]; do
    i=$((i+1))
    adb shell screencap -p /sdcard/lab.png >/dev/null 2>&1 || true
    adb pull /sdcard/lab.png "$LAB_DIR/screen-$(printf '%03d' "$i").png" >/dev/null 2>&1 || true
    sleep 20
  done
) >/dev/null 2>&1 &
echo $! > "$LAB_DIR/shots.pid"
echo "::endgroup::"

echo "LAB_BOOT_OK model=$MODEL_PATH"
adb shell ps -A | grep arya || true
