// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.visual

import android.content.Context
import io.agents.arya.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PHASE 2 — AppAgent-style two-phase exploration.
 *
 * Exploration: open an app, systematically probe its elements, and RECORD what
 * each element does into a per-app "playbook". Deployment (VisualControlAgent)
 * can later load the playbook to act faster and more reliably instead of
 * re-deriving everything from the live screen each time.
 *
 * Persistence target is a JSON under the app files dir.
 */
class AppExplorer(private val context: Context) {

    companion object {
        private const val TAG = "AppExplorer"
    }

    /**
     * A recorded element with its effect (filled in by the user or future learning).
     */
    data class ElementRecord(
        val id: String,
        val text: String,
        val effect: String
    )

    /**
     * Probe an already-open app and return a recorded playbook.
     * This is the Exploration phase (AppAgent pattern).
     */
    fun explore(appName: String): List<ElementRecord> {
        val perceiver = ScreenPerceiver()
        val records = mutableListOf<ElementRecord>()
        val state = perceiver.perceive() ?: return emptyList()

        for (el in state.elements.take(20)) { // cap exploration breadth
            records.add(
                ElementRecord(
                    id = el.id,
                    text = el.text,
                    effect = "unknown"
                )
            )
        }
        savePlaybook(appName, records)
        XLog.i(TAG, "Explored $appName: ${records.size} elements recorded")
        return records
    }

    private fun savePlaybook(appName: String, records: List<ElementRecord>) {
        val dir = File(context.filesDir, "arya/playbooks")
        dir.mkdirs()
        val arr = JSONArray()
        for (record in records) {
            arr.put(JSONObject().apply {
                put("id", record.id)
                put("text", record.text)
                put("effect", record.effect)
            })
        }
        File(dir, "$appName.json").writeText(arr.toString())
    }

    /**
     * Load a previously recorded playbook for an app.
     * Used by the Deployment phase to act faster.
     */
    fun loadPlaybook(appName: String): List<ElementRecord> {
        val file = File(context.filesDir, "arya/playbooks/$appName.json")
        if (!file.exists()) return emptyList()

        val arr = JSONArray(file.readText())
        val out = mutableListOf<ElementRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ElementRecord(
                    id = o.getString("id"),
                    text = o.getString("text"),
                    effect = o.getString("effect")
                )
            )
        }
        return out
    }
}
