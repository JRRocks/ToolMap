package fr.toolmap.operation

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import fr.toolmap.core.record.Fix
import fr.toolmap.data.hierarchy.OperationController
import fr.toolmap.data.hierarchy.OperationStorage
import fr.toolmap.operation.demo.DemoKeyVault
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The notification of an operation (P39.14.5, L4 A, `tour8/img/07`; L4 bis a, `img/09`): its line while the position of
 * this phone goes, « Hors réseau » under it while the network is missing, nothing once the sharing is cut; the service
 * is asked as soon as a position is to go. The notification itself is taken on the emulator (operation-screens.sh).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OperationNotificationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val noGps =
        object : LocationSource {
            override fun fixes(
                minIntervalMillis: Long,
                minDistanceMeters: Float,
            ): Flow<Fix> = emptyFlow()
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the line of the operation, and « Hors réseau » under it while the network is missing`() {
        assertEquals(
            "Position partagée · Exercice Vallon",
            OperationService.text(context, OperationNotice("Exercice Vallon", offline = false)),
        )
        assertEquals(
            "Position partagée · Exercice Vallon\nHors réseau : envoi dès son retour",
            OperationService.text(context, OperationNotice("Exercice Vallon", offline = true)),
        )
    }

    @Test
    fun `the notice follows the sharing of a placed phone, the network, and starts the service`() {
        val storage = OperationStorage(temp.newFolder("operations"), DemoKeyVault())
        val session = OperationSession(context, OperationController(storage, null, { null }), storage, noGps)
        idle()
        assertNull(session.notice.value, "no operation: no notification")
        session.startDemo()
        var waited = 0
        while (session.demo.value == null && waited < DEMO_WAIT_MS) {
            idle()
            Thread.sleep(POLL_MS)
            waited += POLL_MS.toInt()
        }
        assertNotNull(session.demo.value, "the demonstration is built")
        session.setSharing(true)
        session.viewAs(7)
        idle()
        assertEquals(OperationNotice("Exercice Vallon", offline = false), session.notice.value)
        val started = generateSequence { shadowOf(context).nextStartedService }.toList()
        assertEquals(
            OperationService::class.java.name,
            started.lastOrNull()?.component?.className,
            "the service is asked as soon as the position goes",
        )
        session.offline.value = true
        idle()
        assertEquals(OperationNotice("Exercice Vallon", offline = true), session.notice.value)
        session.offline.value = false
        session.setSharing(false)
        idle()
        assertNull(session.notice.value, "« Arrêter le partage »: the notification goes, the phone stays in")
        session.setSharing(true)
        session.stopDemo()
        idle()
    }

    private companion object {
        const val DEMO_WAIT_MS = 60_000
        const val POLL_MS = 50L
    }
}
