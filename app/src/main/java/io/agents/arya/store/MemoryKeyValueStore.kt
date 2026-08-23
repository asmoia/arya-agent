package io.agents.arya.store

class MemoryKeyValueStore : KeyValueStore {
    private val map = LinkedHashMap<String, String>()
    override fun encode(key: String, value: String) {
        map[key] = value
    }
    override fun decode(key: String, default: String?): String? = map[key] ?: default
}
