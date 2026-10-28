package fr.toolmap.share

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import fr.toolmap.R
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.TextButton

/**
 * A phone setting that stops the sharing, or will stop it, and that only the user can change (P21.5 o and c; variant B
 * of `docs/previews/p21-alertes.html`, validated on 2026-10-24). In order of gravity: the first one goes in the
 * notification.
 */
enum class ShareAlert(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val text: Int,
    @param:StringRes val action: Int,
    /** Its item in the dialog shown as the sharing starts. */
    @param:StringRes val item: Int,
    /** Its line in ToolMap's notification. */
    @param:StringRes val line: Int,
    /** It stops the sharing now (red), rather than may stop it later (amber). */
    val now: Boolean,
) {
    /** The location switch is off: no position goes out at all. */
    LOCATION_OFF(
        R.drawable.ic_alert_location_off,
        R.string.share_alert_location_title,
        R.string.share_alert_location_text,
        R.string.share_alert_location_action,
        R.string.share_alert_location_item,
        R.string.share_alert_location_line,
        now = true,
    ),

    /** The power saver turns the GPS off while the screen is off (One UI, second live test, P21.5 o). */
    POWER_SAVER(
        R.drawable.ic_alert_battery_saver,
        R.string.share_alert_saver_title,
        R.string.share_alert_saver_text,
        R.string.share_alert_saver_action,
        R.string.share_alert_saver_item,
        R.string.share_alert_saver_line,
        now = false,
    ),

    /** The app's battery use is optimized: Android may stop it in the background. */
    BATTERY_OPTIMIZED(
        R.drawable.ic_alert_battery,
        R.string.share_alert_battery_title,
        R.string.share_alert_battery_text,
        R.string.share_alert_battery_action,
        R.string.share_alert_battery_item,
        R.string.share_alert_battery_line,
        now = false,
    ),
}

/** Reads the phone settings behind the [ShareAlert]s, in their order. */
fun interface PhoneChecks {
    fun alerts(): List<ShareAlert>
}

class AndroidPhoneChecks(
    private val context: Context,
) : PhoneChecks {
    override fun alerts(): List<ShareAlert> =
        buildList {
            val location = context.getSystemService(LocationManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            if (location != null && !LocationManagerCompat.isLocationEnabled(location)) add(ShareAlert.LOCATION_OFF)
            if (power?.isPowerSaveMode == true) add(ShareAlert.POWER_SAVER)
            if (power != null && !power.isIgnoringBatteryOptimizations(context.packageName)) {
                add(ShareAlert.BATTERY_OPTIMIZED)
            }
        }
}

/** The settings screen that clears [alert]; the general one when the phone has no such screen. */
@SuppressLint("BatteryLife")
private fun settingsFor(
    alert: ShareAlert,
    context: Context,
): List<Intent> {
    val own =
        when (alert) {
            ShareAlert.LOCATION_OFF -> {
                listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }

            ShareAlert.POWER_SAVER -> {
                listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }

            ShareAlert.BATTERY_OPTIMIZED -> {
                listOf(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
        }
    return own + Intent(Settings.ACTION_SETTINGS)
}

/** One card per alert, under the sharing switch; each goes as soon as its setting is changed ([onBack]). */
@Composable
fun ShareAlertCards(
    alerts: List<ShareAlert>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onBack() }
    for (alert in alerts) {
        val colors = alertColors(alert.now)
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(alert.icon), contentDescription = null, modifier = Modifier.size(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(alert.title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(alert.text), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            settingsFor(alert, context).firstOrNull { intent ->
                                runCatching { launcher.launch(intent) }.isSuccess
                            }
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = colors.button,
                                contentColor = colors.onButton,
                            ),
                    ) {
                        Text(stringResource(alert.action))
                    }
                }
            }
        }
    }
}

/** Shown as the sharing starts when [alerts] may cut it; the sharing goes on whatever the answer. */
@Composable
fun ShareStartAlertDialog(
    alerts: List<ShareAlert>,
    onDismiss: () -> Unit,
) {
    val lead =
        when (alerts.size) {
            1 -> R.string.share_alert_dialog_lead_one
            2 -> R.string.share_alert_dialog_lead_two
            else -> R.string.share_alert_dialog_lead_three
        }
    val items = alerts.map { stringResource(it.item) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_alert_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(lead))
                items.forEachIndexed { i, item ->
                    Text("•  " + item + if (i == items.lastIndex) "." else " ;")
                }
            }
        },
        // "Régler" closes the dialog on the cards, just under the switch.
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.share_alert_dialog_fix)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.share_alert_dialog_anyway)) }
        },
    )
}

internal class AlertColors(
    val container: Color,
    val content: Color,
    val button: Color,
    val onButton: Color,
)

/** Red for a setting that stops the sharing [now], amber for one that may stop it; the map chip is amber. */
@Composable
internal fun alertColors(now: Boolean): AlertColors {
    val scheme = MaterialTheme.colorScheme
    if (now) return AlertColors(scheme.errorContainer, scheme.onErrorContainer, scheme.error, scheme.onError)
    val dark = scheme.surface.luminance() < HALF
    return if (dark) {
        AlertColors(Color(AMBER_DARK_BG), Color(AMBER_DARK_TEXT), Color(AMBER_DARK_TEXT), Color(AMBER_DARK_BG))
    } else {
        AlertColors(Color(AMBER_BG), Color(AMBER_TEXT), Color(AMBER_BUTTON), Color.White)
    }
}

private const val HALF = 0.5f
private const val AMBER_BG = 0xFFFDF0D5
private const val AMBER_TEXT = 0xFF5C4100
private const val AMBER_BUTTON = 0xFF7A5600
private const val AMBER_DARK_BG = 0xFF3D2E00
private const val AMBER_DARK_TEXT = 0xFFFAC775
