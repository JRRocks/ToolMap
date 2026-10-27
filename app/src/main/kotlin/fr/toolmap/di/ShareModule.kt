package fr.toolmap.di

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.BuildConfig
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.TransportFactory
import fr.toolmap.p2p.FileAuthStore
import fr.toolmap.p2p.FirebaseRelayFactory
import fr.toolmap.p2p.ShareStore
import fr.toolmap.p2p.WebRtcTransportFactory
import fr.toolmap.record.LocationSource
import fr.toolmap.share.AndroidNetworkWatch
import fr.toolmap.share.AndroidPhoneChecks
import fr.toolmap.share.ShareController
import java.io.File
import javax.inject.Singleton

/** Wiring of the position sharing: one controller for the process, shared by the service and the screens. */
@Module
@InstallIn(SingletonComponent::class)
object ShareModule {
    @Provides
    @Singleton
    fun shareStore(
        @ApplicationContext context: Context,
    ): ShareStore = ShareStore(File(context.filesDir, "p2p/share.json"))

    @Provides
    @Singleton
    fun transportFactory(
        @ApplicationContext context: Context,
    ): TransportFactory = WebRtcTransportFactory(context)

    @Provides
    @Singleton
    fun relayFactory(
        @ApplicationContext context: Context,
    ): RelayTransportFactory = FirebaseRelayFactory(FileAuthStore(File(context.filesDir, "p2p/firebase-auth.token")))

    @Provides
    @Singleton
    fun shareController(
        @ApplicationContext context: Context,
        store: ShareStore,
        transports: TransportFactory,
        relays: RelayTransportFactory,
        location: LocationSource,
    ): ShareController =
        ShareController(
            store = store,
            transports = transports,
            relays = relays,
            builtInServer =
                RelayConfig.of(BuildConfig.FIREBASE_DB_URL, BuildConfig.FIREBASE_API_KEY).takeIf(RelayConfig::isUsable),
            location = location,
            deviceName = { Build.MODEL.orEmpty().ifBlank { "ToolMap" } },
            batteryPercent = {
                val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..PERCENT_MAX }
            },
            network = AndroidNetworkWatch(context),
            phone = AndroidPhoneChecks(context),
        )

    private const val PERCENT_MAX = 100
}
