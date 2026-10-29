package fr.toolmap.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateEntryPoint {
    fun updateController(): UpdateController
}

/** The answer of Android's package installer to the session of an update (P31): passed on to [UpdateController]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm =
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                null
            }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        EntryPointAccessors
            .fromApplication(context, UpdateEntryPoint::class.java)
            .updateController()
            .onInstallStatus(status, confirm, message)
    }

    companion object {
        const val ACTION = "fr.toolmap.update.INSTALL_STATUS"
    }
}
