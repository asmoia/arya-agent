package io.agents.arya.channel.wechat

/** WeChat channel deleted (S8). Stubs keep Settings compiling. */
class WeChatApiClient {
    data class QrResult(val qrcode: String, val qrcodeImgContent: String)
    data class AuthResult(val botToken: String, val baseUrl: String)

    fun getQrCode(): QrResult? = null
    fun pollQrCodeStatus(@Suppress("UNUSED_PARAMETER") qrcode: String): AuthResult? = null
}

object WeChatInbound {
    fun clearContextTokensForAccount(@Suppress("UNUSED_PARAMETER") accountId: String) {}
}
