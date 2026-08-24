#!/usr/bin/env bash
set -uo pipefail

PKG=io.agents.arya
RECV=${PKG}/.debug.DebugTaskReceiver
MODEL_NAME=Qwen_Qwen3-0.6B-Q4_K_M.gguf
MODEL_SRC="${MODEL_CACHE:-$HOME/.cache/arya-models}/$MODEL_NAME"
EXT_DIR="/storage/emulated/0/Android/data/${PKG}/files/models"
EXT_PATH="${EXT_DIR}/${MODEL_NAME}"
LAB_DIR="${LAB_OUT:-$PWD/lab-out}"
mkdir -p "$LAB_DIR"

echo "::group::device"
adb wait-for-device
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
echo "::endgroup::"

echo "::group::install"
APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
echo "APK=$APK"
adb install -r -t "$APK" || exit 1
echo "::endgroup::"

echo "::group::launch"
adb shell am start -W -n ${PKG}/.ui.splash.SplashActivity || true
sleep 5
echo "::endgroup::"

echo "::group::push GGUF"
test -f "$MODEL_SRC" || exit 1
echo "HOST_MODEL_BYTES=$(stat -c%s "$MODEL_SRC")"
adb shell mkdir -p "$EXT_DIR"
adb push "$MODEL_SRC" "$EXT_PATH"
adb shell ls -l "$EXT_DIR" | tee "$LAB_DIR/models-external.txt"
echo "::endgroup::"

echo "::group::logcat"
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

echo "::group::activate + probe"
adb shell am broadcast -n "$RECV" -a ${PKG}.DEBUG_TASK --es task "config:" \
  --es provider LOCAL --es base_url "$EXT_PATH" --es model_name qwen3-0.6b
sleep 1
adb shell am broadcast -n "$RECV" -a ${PKG}.DEBUG_TASK --es task "probe:"
sleep 2
echo "::endgroup::"

echo "LAB_BOOT_OK model=$EXT_PATH"
adb shell ps -A | grep arya || true
exit 0
