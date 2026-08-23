package io.agents.arya.store

interface KeyValueStore {
    fun encode(key: String, value: String)
    fun decode(key: String, default: String? = null): String?
}
