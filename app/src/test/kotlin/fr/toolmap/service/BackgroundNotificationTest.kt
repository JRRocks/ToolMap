package fr.toolmap.service

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundNotificationTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun line(
        text: String,
        stop: String,
    ) = BackgroundNotification.Line(
        text,
        stop,
        PendingIntent.getBroadcast(app, stop.hashCode(), Intent(stop), PendingIntent.FLAG_IMMUTABLE),
    )

    private fun shown(): Notification? = shadowOf(manager).getNotification(BackgroundNotification.NOTIFICATION_ID)

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    private fun Notification.buttons() = actions.orEmpty().map { it.title.toString() }

    @After
    fun clear() {
        BackgroundNotification.Part.entries.forEach { BackgroundNotification.remove(app, it) }
    }

    @Test
    fun `one notification with a line and a button for each work, in a fixed order`() {
        BackgroundNotification.update(
            app,
            BackgroundNotification.Part.RECORDING,
            line("Enregistrement · 3,2 km · 1 h 05", "Arrêter l'enregistrement"),
        )
        val both =
            BackgroundNotification.put(
                app,
                BackgroundNotification.Part.SHARING,
                line("Position partagée · 1 membre connecté", "Arrêter le partage"),
            )
        assertEquals("ToolMap est actif en arrière-plan", both.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Position partagée · 1 membre connecté\nEnregistrement · 3,2 km · 1 h 05", both.text())
        assertEquals(listOf("Arrêter le partage", "Arrêter l'enregistrement"), both.buttons())
        assertEquals(BackgroundNotification.CHANNEL_ID, both.channelId)
        assertTrue(both.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `a stopped work leaves the line of the others, and the last one leaves nothing`() {
        BackgroundNotification.put(app, BackgroundNotification.Part.SHARING, line("Partage", "Arrêter le partage"))
        BackgroundNotification.put(app, BackgroundNotification.Part.ALERTS, line("Alertes", "Arrêter les alertes"))
        BackgroundNotification.remove(app, BackgroundNotification.Part.SHARING)
        assertEquals(listOf("Arrêter les alertes"), shown()?.buttons())
        manager.cancelAll()
        // The last service: Android takes its notification back, nothing is shown again.
        BackgroundNotification.remove(app, BackgroundNotification.Part.ALERTS)
        assertNull(shown())
    }

    @Test
    fun `its channel shows the icon in the status bar without a sound, and replaces the three old ones`() {
        for (old in listOf("sharing", "recording", "alerts-service")) {
            manager.createNotificationChannel(NotificationChannel(old, old, NotificationManager.IMPORTANCE_LOW))
        }
        BackgroundNotification.put(app, BackgroundNotification.Part.SHARING, line("Partage", "Arrêter le partage"))
        val channel = manager.getNotificationChannel(BackgroundNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(listOf(BackgroundNotification.CHANNEL_ID), manager.notificationChannels.map { it.id })
    }

    @Test
    fun `the listening to the group has a line but no button, and a tap opens what its line says`() {
        val openShare = PendingIntent.getBroadcast(app, 1, Intent("open-share"), PendingIntent.FLAG_IMMUTABLE)
        BackgroundNotification.put(
            app,
            BackgroundNotification.Part.SHARING,
            BackgroundNotification.Line("Groupe · 4 membres : à l'écoute", open = openShare),
        )
        val both =
            BackgroundNotification.put(
                app,
                BackgroundNotification.Part.RECORDING,
                line("Enregistrement · 3,2 km · 1 h 05", "Arrêter l'enregistrement"),
            )
        assertEquals("Groupe · 4 membres : à l'écoute\nEnregistrement · 3,2 km · 1 h 05", both.text())
        assertEquals(listOf("Arrêter l'enregistrement"), both.buttons())
        assertEquals(openShare, both.contentIntent)
    }

    @Test
    fun `hidden when the notifications or its channel are turned off`() {
        assertTrue(BackgroundNotification.visible(app))
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(BackgroundNotification.visible(app))
        shadowOf(manager).setNotificationsEnabled(true)
        BackgroundNotification.put(app, BackgroundNotification.Part.SHARING, line("Partage", "Arrêter le partage"))
        val channel = manager.getNotificationChannel(BackgroundNotification.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        assertFalse(BackgroundNotification.visible(app))
    }
}
