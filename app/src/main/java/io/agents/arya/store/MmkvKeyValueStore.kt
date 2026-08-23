package io.agents.arya.store

import com.tencent.mmkv.MMKV

class MmkvKeyValueStore(private val mmkv: MMKV) : KeyValueStore {
    override fun encode(key: String, value: String) {
        mmkv.encode(key, value)
    }
    override fun decode(key: String, default: String?): String? = mmkv.decodeString(key, default)
}
