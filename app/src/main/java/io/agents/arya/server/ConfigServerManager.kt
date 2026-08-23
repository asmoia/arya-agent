package io.agents.arya.server

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Embedded HTTP config server was deleted (S8). These no-ops keep Settings compiling.
 */
object ConfigServerManager {
    val configChanged: SharedFlow<Unit> = MutableSharedFlow()

    fun isRunning(): Boolean = false
    fun start(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
    fun stop() {}
    fun getAddress(): String? = null
}
