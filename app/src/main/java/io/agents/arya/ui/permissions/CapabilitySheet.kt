package io.agents.arya.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.agents.arya.AppCapabilitySnapshot
import io.agents.arya.AppRequirement
import io.agents.arya.ServiceBindingState
import io.agents.arya.ui.theme.LocalAryaPalette

data class CapabilityRow(
    val id: String,
    val title: String,
    val why: String,
    val granted: Boolean,
    val action: CapabilityAction,
)

sealed class CapabilityAction {
    data class System(val requirement: AppRequirement) : CapabilityAction()
    data class Runtime(val permissions: Array<String>) : CapabilityAction()
}

@Composable
fun CapabilitySheet(
    snapshot: AppCapabilitySnapshot,
    onOpenSystem: (AppRequirement) -> Unit,
    onRequestRuntime: (Array<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAryaPalette.current
    val context = LocalContext.current
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED
    val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    val call = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED
    val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    val rows = listOf(
        CapabilityRow(
            "a11y", "Accessibility",
            "Lets Arya tap, type, and read the screen.",
            snapshot.accessibilityState == ServiceBindingState.READY,
            CapabilityAction.System(AppRequirement.ACCESSIBILITY),
        ),
        CapabilityRow(
            "overlay", "Appear on top",
            "Siri-like overlay over other apps.",
            snapshot.overlayGranted,
            CapabilityAction.System(AppRequirement.OVERLAY),
        ),
        CapabilityRow(
            "notif_access", "Notification access",
            "Read incoming messages so Arya can reply.",
            snapshot.notificationAccessState == ServiceBindingState.READY,
            CapabilityAction.System(AppRequirement.NOTIFICATION_ACCESS),
        ),
        CapabilityRow(
            "notif", "Notifications",
            "Download progress and task status.",
            snapshot.notificationPermissionGranted,
            CapabilityAction.Runtime(
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                else emptyArray(),
            ),
        ),
        CapabilityRow(
            "mic", "Microphone",
            "Voice button in the center of the home screen.",
            mic,
            CapabilityAction.Runtime(arrayOf(Manifest.permission.RECORD_AUDIO)),
        ),
        CapabilityRow(
            "battery", "Unrestricted battery",
            "Keeps the local engine alive in the background.",
            snapshot.batteryOptimizationIgnored,
            CapabilityAction.System(AppRequirement.BATTERY_OPTIMIZATION),
        ),
        CapabilityRow(
            "storage", "All files",
            "Optional: send files from Downloads / DCIM.",
            snapshot.storageAccessGranted,
            CapabilityAction.System(AppRequirement.STORAGE),
        ),
        CapabilityRow(
            "contacts", "Contacts",
            "Resolve names when sending messages.",
            contacts,
            CapabilityAction.Runtime(arrayOf(Manifest.permission.READ_CONTACTS)),
        ),
        CapabilityRow(
            "phone", "Phone",
            "Place calls when you ask.",
            phone && call,
            CapabilityAction.Runtime(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)),
        ),
        CapabilityRow(
            "sms", "SMS",
            "Send texts when you confirm.",
            sms,
            CapabilityAction.Runtime(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)),
        ),
        CapabilityRow(
            "camera", "Camera",
            "Open camera and capture on request.",
            camera,
            CapabilityAction.Runtime(arrayOf(Manifest.permission.CAMERA)),
        ),
    )

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp)
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(palette.hairline),
        )
        Text("Capabilities", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "An agent is only as useful as the access you give it. Grant these so Arya can act on the phone, not just talk.",
            fontSize = 14.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface)
                    .clickable {
                        when (val a = row.action) {
                            is CapabilityAction.System -> onOpenSystem(a.requirement)
                            is CapabilityAction.Runtime -> {
                                if (a.permissions.isEmpty()) return@clickable
                                onRequestRuntime(a.permissions)
                            }
                        }
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.text)
                    Text(row.why, fontSize = 13.sp, color = palette.textSecondary)
                }
                Text(
                    if (row.granted) "On" else "Allow",
                    color = if (row.granted) palette.success else palette.accent,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(28.dp))
    }
}
