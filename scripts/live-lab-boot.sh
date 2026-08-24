#!/usr/bin/env bash
set -euo pipefail

PKG=io.agents.arya
RECV=${PKG}/.debug.DebugTaskReceiver
MODEL_NAME=Qwen_Qwen3-0.6B-Q4_K_M.gguf
MODEL_SRC="${MODEL_CACHE:-$HOME/.cache/arya-models}/$MODEL_NAME"
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
adb install -r -t "$APK"
echo "::endgroup::"

echo "::group::launch"
adb shell am start -W -n ${PKG}/.ui.splash.SplashActivity || true
sleep 5
echo "::endgroup::"

echo "::group::place GGUF"
test -f "$MODEL_SRC"
echo "HOST_MODEL_BYTES=$(stat -c%s "$MODEL_SRC")"
adb push "$MODEL_SRC" /data/local/tmp/$MODEL_NAME
adb shell "cp /data/local/tmp/$MODEL_NAME /sdcard/$MODEL_NAME"
adb shell run-as $PKG mkdir -p files/models
adb shell run-as $PKG cp /sdcard/$MODEL_NAME files/models/$MODEL_NAME || \
  adb shell "run-as $PKG sh -c 'cat /sdcard/$MODEL_NAME > files/models/$MODEL_NAME'"
EXT="/storage/emulated/0/Android/data/${PKG}/files/models"
adb shell mkdir -p "$EXT"
adb shell cp /sdcard/$MODEL_NAME "$EXT/$MODEL_NAME" || true
adb shell run-as $PKG ls -l files/models | tee "$LAB_DIR/models-internal.txt"
adb shell ls -l "$EXT" | tee "$LAB_DIR/models-external.txt"
echo "::endgroup::"

INT_PATH="/data/user/0/${PKG}/files/models/${MODEL_NAME}"

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
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es task "config:" \
  --es provider LOCAL --es base_url "$INT_PATH" --es model_name qwen3-0.6b
sleep 1
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es task "probe:"
sleep 2
echo "::endgroup::"

echo "LAB_BOOT_OK model=$INT_PATH"
adb shell ps -A | grep arya || true
