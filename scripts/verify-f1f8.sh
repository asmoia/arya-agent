#!/usr/bin/env bash
# Acceptance greps for the F1–F8 user-test fixes. No APK assemble.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail=0
check() {
  local name="$1" cmd="$2" expect_nonempty="${3:-1}"
  echo "=== $name ==="
  if eval "$cmd"; then
    if [ "$expect_nonempty" = "1" ]; then
      if [ -z "$(eval "$cmd")" ]; then
        echo "FAIL: empty"
        fail=1
      fi
    fi
  else
    echo "FAIL: command error"
    fail=1
  fi
}

echo "GIT_TAG:"
grep -n "GIT_TAG" app/src/main/cpp/CMakeLists.txt
grep -q "GIT_TAG        b10603" app/src/main/cpp/CMakeLists.txt || { echo "FAIL: expected b10603"; fail=1; }

echo "getMemoryInfo:"
grep -rn "getMemoryInfo" app/src/main/java/io/agents/arya/engine/ || { echo "FAIL"; fail=1; }

echo "RECORD_AUDIO:"
grep -n "RECORD_AUDIO" app/src/main/AndroidManifest.xml || { echo "FAIL"; fail=1; }

echo "tryAwaitRelease:"
grep -n "tryAwaitRelease" app/src/main/java/io/agents/arya/ui/chat/ui/InputBar.kt || { echo "FAIL"; fail=1; }

echo "Qwen3 entries:"
n=$(grep -c "Qwen3" app/src/main/java/io/agents/arya/agent/llm/LocalModelManager.kt || true)
echo "count=$n"
if [ "$n" -lt 3 ]; then echo "FAIL: need >=3 Qwen3"; fail=1; fi

echo "totalMemory():"
if grep -rn "totalMemory()" app/src/main/java/io/agents/arya/engine/; then
  echo "FAIL: heap RAM still referenced"
  fail=1
else
  echo "(empty — PASS)"
fi

echo "requestLoad:"
grep -n "requestLoad" app/src/main/aidl/io/agents/arya/engine/IEngine.aidl || { echo "FAIL"; fail=1; }

if [ "$fail" -ne 0 ]; then
  echo "VERIFY FAIL"
  exit 1
fi
echo "VERIFY PASS"
