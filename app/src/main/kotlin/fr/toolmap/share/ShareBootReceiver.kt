package fr.toolmap.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.toolmap.core.eventlog.EventLog

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShareEntryPoint {
    fun controller(): ShareController
}

/**
 * Brings back the listening to the group when the phone has restarted or the app was updated (P22.3, ADR-090), only
 * when the device is in a group. The sharing of the position waits for the app to come on screen: Android does not
 * let the type `location` start from there.
 */
class ShareBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val controller = EntryPointAccessors.fromApplication(context, ShareEntryPoint::class.java).controller()
        controller.load()
        val inGroup = controller.state.value.inGroup
        EventLog.event("share.boot", "action" to intent.action, "group" to inGroup)
        if (inGroup) ShareService.listen(context)
    }
}
