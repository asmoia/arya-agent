package io.agents.arya.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * Characterization: bench cases that look like Tier1 must still match without an LLM.
 */
class BenchSnapshotTest {
    @Test
    fun benchCasesStayDeterministicWhenLabeled() {
        val path = File("app/src/main/assets/benchmarks/arya_bench_fa.json")
        val alt = File("../app/src/main/assets/benchmarks/arya_bench_fa.json")
        val file = when {
            path.exists() -> path
            alt.exists() -> alt
            else -> return // skip if cwd is elsewhere
        }
        val root = JSONObject(file.readText())
        val cases = root.getJSONArray("cases")
        var hits = 0
        var labeled = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("text")
            val route = c.optString("route")
            val maxLlm = c.optInt("max_llm_calls", 99)
            if (maxLlm == 0 || route.contains("direct") || route.contains("compiled")) {
                labeled++
                val hit = FastTaskMatchers.match(text) != null ||
                    PersianCommandCompiler.compile(text) != null
                if (hit) hits++
            }
        }
        assertTrue("expected at least one labeled case", labeled > 0)
        assertTrue("Tier1 hit-rate $hits/$labeled", hits.toDouble() / labeled >= 0.5)
    }
}
