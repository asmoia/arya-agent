// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Embedded Hermes core — session persistence (Kotlin port of SessionDB concepts).

package io.agents.arya.agent.hermes.session

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.agents.arya.ClawApplication
import io.agents.arya.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Lightweight SQLite store for Hermes sessions and message history.
 *
 * Inspired by hermes-agent's SessionDB, but scoped to what the Android agent
 * needs: create/resume sessions, append turns, list recent sessions.
 */
class HermesSessionStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    data class Session(
        val id: String,
        val title: String,
        val source: String,
        val createdAt: Long,
        val updatedAt: Long,
        val endedAt: Long? = null,
        val metadataJson: String = "{}"
    )

    data class Message(
        val id: Long,
        val sessionId: String,
        val role: String,
        val content: String,
        val toolName: String? = null,
        val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'arya',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                ended_at INTEGER,
                metadata_json TEXT NOT NULL DEFAULT '{}'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_name TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, id)")
        db.execSQL("CREATE INDEX idx_sessions_updated ON sessions(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only for now
    }

    fun createSession(
        title: String = "",
        source: String = "arya",
        metadata: Map<String, Any?> = emptyMap()
    ): Session {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val metaObj = JSONObject()
        for ((k, v) in metadata) {
            metaObj.put(k, v ?: JSONObject.NULL)
        }
        val meta = metaObj.toString()
        writableDatabase.execSQL(
            "INSERT INTO sessions(id, title, source, created_at, updated_at, metadata_json) VALUES(?,?,?,?,?,?)",
            arrayOf(id, title, source, now, now, meta)
        )
        XLog.i(TAG, "createSession id=$id title='$title'")
        return Session(id, title, source, now, now, null, meta)
    }

    fun touch(sessionId: String, title: String? = null) {
        val now = System.currentTimeMillis()
        if (title != null) {
            writableDatabase.execSQL(
                "UPDATE sessions SET updated_at=?, title=? WHERE id=?",
                arrayOf(now, title, sessionId)
            )
        } else {
            writableDatabase.execSQL(
                "UPDATE sessions SET updated_at=? WHERE id=?",
                arrayOf(now, sessionId)
            )
        }
    }

    fun endSession(sessionId: String, reason: String = "completed") {
        val now = System.currentTimeMillis()
        // Avoid SQLite json_set (not available on older Android SQLite builds).
        val existing = getSession(sessionId)?.metadataJson ?: "{}"
        val meta = try {
            val obj = org.json.JSONObject(existing)
            obj.put("end_reason", reason)
            obj.toString()
        } catch (_: Exception) {
            """{"end_reason":"$reason"}"""
        }
        writableDatabase.execSQL(
            "UPDATE sessions SET ended_at=?, updated_at=?, metadata_json=? WHERE id=?",
            arrayOf(now, now, meta, sessionId)
        )
    }

    fun getSession(sessionId: String): Session? {
        readableDatabase.rawQuery(
            "SELECT id, title, source, created_at, updated_at, ended_at, metadata_json FROM sessions WHERE id=?",
            arrayOf(sessionId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Session(
                id = c.getString(0),
                title = c.getString(1),
                source = c.getString(2),
                createdAt = c.getLong(3),
                updatedAt = c.getLong(4),
                endedAt = if (c.isNull(5)) null else c.getLong(5),
                metadataJson = c.getString(6) ?: "{}"
            )
        }
    }

    fun latestOpenSession(source: String = "arya"): Session? {
        readableDatabase.rawQuery(
            """
            SELECT id, title, source, created_at, updated_at, ended_at, metadata_json
            FROM sessions
            WHERE source=? AND ended_at IS NULL
            ORDER BY updated_at DESC LIMIT 1
            """.trimIndent(),
            arrayOf(source)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Session(
                id = c.getString(0),
                title = c.getString(1),
                source = c.getString(2),
                createdAt = c.getLong(3),
                updatedAt = c.getLong(4),
                endedAt = if (c.isNull(5)) null else c.getLong(5),
                metadataJson = c.getString(6) ?: "{}"
            )
        }
    }

    fun listRecent(limit: Int = 20): List<Session> {
        val out = mutableListOf<Session>()
        readableDatabase.rawQuery(
            """
            SELECT id, title, source, created_at, updated_at, ended_at, metadata_json
            FROM sessions ORDER BY updated_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Session(
                    id = c.getString(0),
                    title = c.getString(1),
                    source = c.getString(2),
                    createdAt = c.getLong(3),
                    updatedAt = c.getLong(4),
                    endedAt = if (c.isNull(5)) null else c.getLong(5),
                    metadataJson = c.getString(6) ?: "{}"
                )
            }
        }
        return out
    }

    fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String? = null
    ) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "INSERT INTO messages(session_id, role, content, tool_name, created_at) VALUES(?,?,?,?,?)",
            arrayOf(sessionId, role, content, toolName, now)
        )
        touch(sessionId)
    }

    fun getMessages(sessionId: String, limit: Int = 200): List<Message> {
        val out = mutableListOf<Message>()
        readableDatabase.rawQuery(
            """
            SELECT id, session_id, role, content, tool_name, created_at
            FROM messages WHERE session_id=?
            ORDER BY id ASC LIMIT ?
            """.trimIndent(),
            arrayOf(sessionId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Message(
                    id = c.getLong(0),
                    sessionId = c.getString(1),
                    role = c.getString(2),
                    content = c.getString(3),
                    toolName = c.getString(4),
                    createdAt = c.getLong(5)
                )
            }
        }
        return out
    }

    /** Export recent transcript as plain text for learning / compression. */
    fun transcript(sessionId: String, maxChars: Int = 12_000): String {
        val msgs = getMessages(sessionId)
        val sb = StringBuilder()
        for (m in msgs) {
            val line = when (m.role) {
                "tool" -> "tool(${m.toolName}): ${m.content}"
                else -> "${m.role}: ${m.content}"
            }
            if (sb.length + line.length + 1 > maxChars) break
            sb.appendLine(line)
        }
        return sb.toString()
    }

    fun exportMessagesJson(sessionId: String): String {
        val arr = JSONArray()
        for (m in getMessages(sessionId)) {
            arr.put(
                JSONObject()
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("tool_name", m.toolName)
                    .put("created_at", m.createdAt)
            )
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "HermesSessionStore"
        private const val DB_NAME = "hermes_sessions.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: HermesSessionStore? = null

        fun getInstance(): HermesSessionStore {
            return instance ?: synchronized(this) {
                instance ?: HermesSessionStore(ClawApplication.instance).also { instance = it }
            }
        }
    }
}
