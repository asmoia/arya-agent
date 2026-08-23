package io.agents.arya.agent.hermes.backup

import android.content.Context
import android.net.Uri
import java.io.File

/** Hermes backup archived (S8). */
object HermesBackupManager {
    data class Result(val ok: Boolean, val message: String, val file: File? = null)

    fun importFromUri(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") uri: Uri,
        @Suppress("UNUSED_PARAMETER") replaceAll: Boolean,
    ): Result = Result(false, "Hermes backup is archived in this redesign")

    fun exportToCache(@Suppress("UNUSED_PARAMETER") context: Context): Result =
        Result(false, "Hermes backup is archived in this redesign")
}
