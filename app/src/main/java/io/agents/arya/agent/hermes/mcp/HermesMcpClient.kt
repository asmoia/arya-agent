package io.agents.arya.agent.hermes.mcp

/** MCP client archived (S8). */
object HermesMcpClient {
    private var url: String = ""
    private var enabled: Boolean = false
    fun getUrl(): String = url
    fun setUrl(value: String) {
        url = value
    }
    fun setEnabled(value: Boolean) {
        enabled = value
    }
    fun isEnabled(): Boolean = enabled
}
