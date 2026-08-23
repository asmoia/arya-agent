#!/usr/bin/env bash
# S10 grep audits — run from repo root.
set -euo pipefail
echo "== loadLibrary (must be engine only) =="
grep -rn "System.loadLibrary" app/src/main/java || true
echo "== litert / langchain =="
grep -rniE "langchain4j|com.google.ai.edge.litert" app/src/main --include='*.kt' --include='*.kts' || true
echo "== task-state reads =="
grep -rn "isTaskRunning\|currentTask\|taskState" app/src/main/java --include='*.kt' | head -40 || true
echo "OK"
