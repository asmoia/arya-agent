#!/bin/bash
set -x
PKG=io.agents.arya
RECV=${PKG}/.debug.DebugTaskReceiver
MODEL=Qwen_Qwen3-0.6B-Q4_K_M.gguf
echo "=== date ==="; date -u
echo "=== ps ==="; adb shell ps -A | grep arya || true
echo "=== ls models ==="
adb shell ls -l /sdcard/$MODEL /storage/emulated/0/Android/data/$PKG/files/models /data/local/tmp/$MODEL 2>/dev/null || true
adb shell run-as $PKG ls -l files/models 2>/dev/null || true
echo "=== explicit probe/config/chat ==="
# if host cache exists on runner, push
if [ -f "$HOME/.cache/arya-models/$MODEL" ]; then
  adb push "$HOME/.cache/arya-models/$MODEL" /data/local/tmp/$MODEL
  adb shell cp /data/local/tmp/$MODEL /sdcard/$MODEL
  adb shell run-as $PKG mkdir -p files/models
  adb shell run-as $PKG cp /sdcard/$MODEL files/models/$MODEL || true
fi
INT=/data/user/0/$PKG/files/models/$MODEL
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es task "config:" --es provider LOCAL --es base_url "$INT" --es model_name qwen3-0.6b
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es task "probe:"
sleep 2
adb shell am broadcast -n $RECV -a ${PKG}.DEBUG_TASK --es chat "Say hi in three words."
sleep 8
echo "=== meminfo engine ==="
adb shell dumpsys meminfo ${PKG}:engine | head -30 || true
echo "=== logcat snippet ==="
adb logcat -d -s DebugTaskReceiver:I LAB_PROBE:I AryaEngineLog:I AryaEngineJNI:I LocalLlmClient:I EngineClient:I | tail -80
