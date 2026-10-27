package fr.toolmap.alert

import android.content.res.Resources
import fr.toolmap.R
import fr.toolmap.core.alert.AlertEvent
import fr.toolmap.ui.Formats

/** What an alert says, on the notification and on the map. */
data class AlertText(
    val title: String,
    val body: String,
)

object AlertTexts {
    fun of(
        resources: Resources,
        formats: Formats,
        event: AlertEvent,
    ): AlertText =
        when (event) {
            is AlertEvent.Proximity -> {
                val name =
                    event.place.name?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.alert_unnamed_place)
                AlertText(
                    resources.getString(R.string.alert_proximity_title, name),
                    resources.getString(R.string.alert_proximity_text, formats.distance(event.distanceMeters)),
                )
            }

            is AlertEvent.OffTrack -> {
                AlertText(
                    resources.getString(R.string.alert_off_title),
                    resources.getString(
                        R.string.alert_off_text,
                        formats.distance(event.distanceMeters),
                        formats.distance(event.limitMeters),
                    ),
                )
            }

            AlertEvent.BackOnTrack -> {
                AlertText(resources.getString(R.string.alert_back_title), resources.getString(R.string.alert_back_text))
            }

            AlertEvent.Arrived -> {
                AlertText(
                    resources.getString(R.string.alert_arrived_title),
                    resources.getString(R.string.alert_arrived_text),
                )
            }
        }
}
