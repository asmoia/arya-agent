// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.content.Context
import io.agents.arya.utils.XLog

/**
 * PHASE 1 — Local wake-word detection (e.g. "Hey Arya").
 *
 * Runs ENTIRELY on-device (no audio leaves the phone until the wake word fires).
 *
 * CURRENT STATUS: SCAFFOLD — this is a stub that always fires immediately.
 *
 * TODO: Integrate microWakeWord or a bundled .tflite keyword spotter.
 * See: https://github.com/gabrieljoelj/microWakeWord
 *
 * Add to build.gradle.kts when ready:
 *   implementation("com.github.gabrieljoelj:microWakeWord:1.0.0")
 *
 * IMPORTANT: Software wake-word detection keeps the CPU awake.
 * This drains more battery than Google's dedicated low-power DSP.
 * Make wake-word detection OPT-IN in settings, and warn users.
 */
interface WakeWordDetector {
    /** Start listening for the wake word. Calls onWake() when detected. */
    fun start(onWake: () -> Unit)
    fun stop()
}

/**
 * Stub wake-word detector that fires immediately.
 * Replace with real microWakeWord integration before shipping the feature.
 */
class StubWakeWordDetector(private val context: Context) : WakeWordDetector {

    companion object {
        private const val TAG = "WakeWord"
    }

    override fun start(onWake: () -> Unit) {
        XLog.w(TAG, "WakeWordDetector is a STUB — not yet implemented. onWake() will NOT fire automatically.")
        // TODO: When sherpa-onnx/microWakeWord is integrated:
        // detector = MicroWakeWord(context, "hey_arya.tflite")
        // detector.start { onWake() }
    }

    override fun stop() {
        XLog.i(TAG, "WakeWordDetector stopped")
        // detector?.stop()
    }
}
