package fr.toolmap.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.toolmap.R
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.service.BackgroundNotification
import fr.toolmap.ui.theme.TextButton

/**
 * The question asked as a work that goes on in the background starts (sharing, recording) while ToolMap's
 * notification is hidden (P21.7): Android then shows nothing of it. Returns the check to call once the work is
 * started; « Autoriser » asks Android for the permission, or opens ToolMap's notification settings when Android no
 * longer asks (permission refused for good, notifications or the channel turned off by hand).
 */
@Composable
fun rememberNotificationAsk(
    @StringRes text: Int,
): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var asking by remember { mutableStateOf(false) }
    var rationaleBefore by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // No window both before and after: Android refused at once, only the settings can give the permission.
            val refusedForGood = !granted && !rationaleBefore && !rationale(activity)
            EventLog.event("notify.ask.result", "granted" to granted, "settings" to refusedForGood)
            if (refusedForGood) openSettings(context, channel = false)
        }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.background_ask_title)) },
            text = { Text(stringResource(text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        EventLog.event("notify.ask.answer", "allow" to true)
                        when {
                            NotificationManagerCompat.from(context).areNotificationsEnabled() -> {
                                openSettings(context, channel = true)
                            }

                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context) -> {
                                rationaleBefore = rationale(activity)
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }

                            else -> {
                                openSettings(context, channel = false)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.background_ask_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        asking = false
                        EventLog.event("notify.ask.answer", "allow" to false)
                    },
                ) { Text(stringResource(R.string.background_ask_later)) }
            },
        )
    }
    return remember(context) {
        {
            if (!BackgroundNotification.visible(context)) {
                EventLog.event("notify.ask")
                asking = true
            }
        }
    }
}

private fun granted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun rationale(activity: Activity?): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)

/** ToolMap's notification settings, or those of its background channel when only that one is turned off. */
private fun openSettings(
    context: Context,
    channel: Boolean,
) {
    val intent =
        if (channel) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_CHANNEL_ID, BackgroundNotification.CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
    runCatching {
        context.startActivity(
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
