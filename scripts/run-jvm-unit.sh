#!/usr/bin/env bash
# Pure-JVM tests (no Android SDK). Requires kotlinc + junit on PATH or /tmp/kt-test.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT="${KOTLINC:-/tmp/kt-test/kotlinc/bin/kotlinc}"
LIB="${JUNIT_LIB:-/tmp/kt-test/lib}"
OUT="${OUT_DIR:-/tmp/kt-test/out-all}"
CP="$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar:$LIB/json-20240303.jar:$LIB/kotlinx-coroutines-core-jvm-1.8.1.jar"
mkdir -p "$OUT"
# shellcheck disable=SC2046
"$KT" \
  "$ROOT/app/src/main/java/io/agents/arya/engine/budget/MemoryBudget.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/engine/budget/GgufHeaderParser.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/engine/PrefixCache.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/llm/LlmEvent.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/llm/StreamAssembler.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/llm/SseParser.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/llm/CatalogPolicy.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/llm/ModelCatalog.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/llm/CloudRetry.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/llm/LegacyModelCleanup.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/PersianNormalizer.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/FastTaskMatchers.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/PersianCommandCompiler.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/DeterministicPlan.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/RoutingPolicy.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/agent/ScreenStructureMatchers.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/ui/settings/SettingsSearch.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/ui/chat/ChatMessage.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/ui/chat/ChatMarkdown.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/debug/BatteryEstimate.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/utils/PersianFormat.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/voice/VoiceInputState.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/engine/EngineWatchdog.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/engine/ModelPaths.kt" \
  "$ROOT/app/src/main/java/io/agents/arya/ui/chat/ChatNoise.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/engine/budget/MemoryBudgetTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/engine/budget/GgufHeaderParserTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/engine/PrefixCacheTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/llm/StreamAssemblerTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/llm/StreamAssemblerFuzzTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/llm/SseParserTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/llm/CatalogPolicyTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/llm/CloudRetryTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/llm/LegacyModelCleanupTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/FastTaskMatchersTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/PersianCommandCompilerTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/RoutingPolicyTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/agent/ScreenStructureMatchersTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/ui/settings/SettingsSearchTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/ui/chat/ChatMarkdownTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/debug/BatteryEstimateTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/utils/PersianFormatTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/voice/VoiceStateMachineTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/engine/EngineWatchdogTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/engine/ModelPathsTest.kt" \
  "$ROOT/app/src/test/java/io/agents/arya/ui/chat/ChatNoiseTest.kt" \
  -cp "$CP" -d "$OUT"

java -cp "$OUT:$CP:/tmp/kt-test/kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore \
  io.agents.arya.engine.budget.MemoryBudgetSingleTest \
  io.agents.arya.engine.budget.MemoryBudgetTableTest \
  io.agents.arya.engine.budget.GgufHeaderParserTest \
  io.agents.arya.engine.PrefixCacheTest \
  io.agents.arya.agent.llm.StreamAssemblerTest \
  io.agents.arya.agent.llm.StreamAssemblerFuzzTest \
  io.agents.arya.agent.llm.SseParserTest \
  io.agents.arya.agent.llm.CatalogPolicyTest \
  io.agents.arya.agent.llm.CloudRetryTest \
  io.agents.arya.agent.llm.LegacyModelCleanupTest \
  io.agents.arya.agent.FastTaskMatchersTest \
  io.agents.arya.agent.PersianCommandCompilerTest \
  io.agents.arya.agent.RoutingPolicyTest \
  io.agents.arya.agent.ScreenStructureMatchersTest \
  io.agents.arya.ui.settings.SettingsSearchTest \
  io.agents.arya.ui.chat.ChatMarkdownTest \
  io.agents.arya.debug.BatteryEstimateTest \
  io.agents.arya.utils.PersianFormatTest \
  io.agents.arya.voice.VoiceStateMachineTest \
  io.agents.arya.engine.EngineWatchdogTest \
  io.agents.arya.engine.ModelPathsTest \
  io.agents.arya.ui.chat.ChatNoiseTest
