package io.agents.arya.channel

import io.agents.arya.channel.telegram.TelegramChannelHandler
import io.agents.arya.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

object ChannelManager {

    private const val TAG = "ChannelManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    private val handlers = mutableMapOf<Channel, ChannelHandler>()
    private var messageListener: OnMessageReceivedListener? = null

    interface OnMessageReceivedListener {
        fun onMessageReceived(channel: Channel, message: String, messageID: String)
    }

    @JvmStatic
    fun setOnMessageReceivedListener(listener: OnMessageReceivedListener?) {
        this.messageListener = listener
    }

    @JvmStatic
    @JvmOverloads
    fun init(
        telegramBotToken: String? = null,
        discordBotToken: String? = null,
        wechatBotToken: String? = null,
        wechatApiBaseUrl: String? = null,
    ) {
        @Suppress("UNUSED_VARIABLE")
        val ignoredRemote = Triple(discordBotToken, wechatBotToken, wechatApiBaseUrl)
        handlers[Channel.TELEGRAM] = TelegramChannelHandler(
            scope, httpClient,
            telegramBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )

        handlers.values.forEach { it.init() }
        XLog.i(TAG, "ChannelManager initialized")
    }

    @JvmStatic
    fun reinitFromStorage() {
        handlers.values.forEach { it.reinitFromStorage() }
    }

    @JvmStatic
    fun reconnectIfNeeded() {
        handlers.forEach { (channel, handler) ->
            if (!handler.isConnected()) {
                XLog.i(TAG, "Reconnecting ${channel.displayName} channel")
                handler.reinitFromStorage()
            }
        }
    }

    @JvmStatic
    fun reinitTelegramFromStorage() {
        handlers[Channel.TELEGRAM]?.reinitFromStorage()
    }

    @JvmStatic
    fun disconnectAll() {
        handlers.forEach { (channel, handler) ->
            if (handler.isConnected()) {
                XLog.i(TAG, "Disconnecting ${channel.displayName} channel")
                handler.disconnect()
            }
        }
    }

    @JvmStatic
    fun sendMessage(channel: Channel, content: String, messageID: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) return
        handlers[channel]?.sendMessage(trimmedContent, messageID)
    }

    @JvmStatic
    fun sendImage(channel: Channel, imageBytes: ByteArray, messageID: String) {
        handlers[channel]?.sendImage(imageBytes, messageID)
    }

    @JvmStatic
    fun sendFile(channel: Channel, file: java.io.File, messageID: String) {
        handlers[channel]?.sendFile(file, messageID)
    }

    @JvmStatic
    fun flushMessages(channel: Channel?) {
        channel ?: return
        handlers[channel]?.flushMessages()
    }

    @JvmStatic
    fun reinitWeChatFromStorage() {
        XLog.i(TAG, "WeChat channel archived — no-op")
    }

    @JvmStatic
    fun reinitDiscordFromStorage() {
        XLog.i(TAG, "Discord channel archived — no-op")
    }

    @JvmStatic
    fun dispatchMessage(channel: Channel, message: String, messageID: String) {
        messageListener?.onMessageReceived(channel, message, messageID)
    }
}
