package io.agents.arya.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Channel config UI is frozen (S8). Stub launcher so Settings still compiles.
 */
class ChannelConfigActivity : ComponentActivity() {
    enum class ChannelType { TELEGRAM, DISCORD, WECHAT }

    companion object {
        fun registerLauncher(
            activity: ComponentActivity,
            onResult: (Any?) -> Unit,
        ): ActivityResultLauncher<ChannelType> {
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { onResult(null) }
            return object : ActivityResultLauncher<ChannelType>() {
                override fun launch(input: ChannelType) {
                    // No-op: remote channel config is archived.
                }
                override fun launch(input: ChannelType, options: androidx.core.app.ActivityOptionsCompat?) {
                    launch(input)
                }
                override fun unregister() {
                    launcher.unregister()
                }
                override val contract = object : androidx.activity.result.contract.ActivityResultContract<ChannelType, Any?>() {
                    override fun createIntent(context: Context, input: ChannelType): Intent =
                        Intent(context, ChannelConfigActivity::class.java)
                    override fun parseResult(resultCode: Int, intent: Intent?): Any? = null
                }
            }
        }
    }
}
