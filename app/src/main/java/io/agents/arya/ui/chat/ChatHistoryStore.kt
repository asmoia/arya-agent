package io.agents.arya.ui.chat

import android.content.Context
import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ConversationMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val lastMessageAt: Long,
    val isPinned: Boolean = false
)

class ChatHistoryStore(private val context: Context) {

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }
    private val conversationsDir: File by lazy {
        File(context.filesDir, "chat_conversations").apply { mkdirs() }
    }

    fun listConversations(): List<ConversationMeta> {
        val jsonStr = mmkv.decodeString("conversations_index", null) ?: return emptyList()
        val list = mutableListOf<ConversationMeta>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ConversationMeta(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        createdAt = obj.getLong("createdAt"),
                        lastMessageAt = obj.getLong("lastMessageAt"),
                        isPinned = obj.optBoolean("isPinned", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedWith(compareByDescending<ConversationMeta> { it.isPinned }.thenByDescending { it.lastMessageAt })
    }

    fun saveConversation(id: String, title: String, messages: List<ChatMessage>) {
        val file = File(conversationsDir, "$id.json")
        val array = JSONArray()
        for (m in messages) {
            array.put(JSONObject().apply {
                put("role", m.role.name)
                put("content", m.content)
                put("timestamp", m.timestamp)
                put("modelName", m.modelName ?: "")
            })
        }
        file.writeText(array.toString())

        // Update index
        val currentIndex = listConversations().toMutableList()
        val existingIdx = currentIndex.indexOfFirst { it.id == id }
        val now = System.currentTimeMillis()
        if (existingIdx >= 0) {
            val existing = currentIndex[existingIdx]
            currentIndex[existingIdx] = existing.copy(
                title = if (title.isNotBlank()) title else existing.title,
                lastMessageAt = now
            )
        } else {
            currentIndex.add(
                ConversationMeta(
                    id = id,
                    title = if (title.isNotBlank()) title else "گفتگوی جدید",
                    createdAt = now,
                    lastMessageAt = now
                )
            )
        }

        saveIndex(currentIndex)
    }

    fun loadConversation(id: String): List<ChatMessage> {
        val file = File(conversationsDir, "$id.json")
        if (!file.exists()) return emptyList()
        val list = mutableListOf<ChatMessage>()
        try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val roleStr = obj.getString("role")
                val role = try { ChatMessage.Role.valueOf(roleStr) } catch (_: Exception) { ChatMessage.Role.USER }
                list.add(
                    ChatMessage(
                        role = role,
                        content = obj.getString("content"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        modelName = obj.optString("modelName", null)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun deleteConversation(id: String) {
        File(conversationsDir, "$id.json").delete()
        val currentIndex = listConversations().filter { it.id != id }
        saveIndex(currentIndex)
    }

    fun renameConversation(id: String, newTitle: String) {
        val current = listConversations().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(title = newTitle)
            saveIndex(current)
        }
    }

    fun togglePinConversation(id: String) {
        val current = listConversations().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isPinned = !current[idx].isPinned)
            saveIndex(current)
        }
    }

    fun exportMarkdown(id: String): String {
        val meta = listConversations().firstOrNull { it.id == id }
        return ChatMarkdown.render(meta?.title ?: id, loadConversation(id))
    }

    private fun saveIndex(list: List<ConversationMeta>) {
        val array = JSONArray()
        for (c in list) {
            array.put(JSONObject().apply {
                put("id", c.id)
                put("title", c.title)
                put("createdAt", c.createdAt)
                put("lastMessageAt", c.lastMessageAt)
                put("isPinned", c.isPinned)
            })
        }
        mmkv.encode("conversations_index", array.toString())
    }
}
