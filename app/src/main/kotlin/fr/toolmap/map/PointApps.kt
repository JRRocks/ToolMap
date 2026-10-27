package fr.toolmap.map

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import android.widget.Toast
import fr.toolmap.core.geo.PointLinks
import fr.toolmap.core.geo.PointTarget

/** An app that can open a position, offered as the default of « Ouvrir avec » in the settings (ADR-044). */
data class PointApp(
    val packageName: String,
    val label: String,
)

/** The apps that open a position: those that handle `geo:` links, and Waze when it is installed. */
fun pointApps(context: Context): List<PointApp> {
    val pm = context.packageManager
    val packages =
        pm
            .queryIntentActivities(pointIntent(null, 0.0, 0.0), PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(mutableSetOf<String>()) { it.activityInfo.packageName }
    if (pointIntent(PointLinks.WAZE_PACKAGE, 0.0, 0.0).resolveActivity(pm) != null) {
        packages += PointLinks.WAZE_PACKAGE
    }
    packages -= context.packageName
    return packages
        .map { PointApp(it, pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()) }
        .sortedBy { it.label.lowercase() }
}

/**
 * Hands the position to the default app [app] when it can open it. Otherwise the system asks which app to use, among the
 * apps that open `geo:` links and Waze when it is installed. [chooserTitle] and [noAppMessage] come from the composition.
 */
fun openPoint(
    context: Context,
    lat: Double,
    lon: Double,
    app: String?,
    chooserTitle: String,
    noAppMessage: String,
) {
    val pm = context.packageManager
    val direct = app?.let { pointIntent(it, lat, lon) }
    if (direct != null && direct.resolveActivity(pm) != null) {
        context.startActivity(direct)
        return
    }
    val generic = pointIntent(null, lat, lon)
    val waze = pointIntent(PointLinks.WAZE_PACKAGE, lat, lon).takeIf { it.resolveActivity(pm) != null }
    if (generic.resolveActivity(pm) == null && waze == null) {
        Toast.makeText(context, noAppMessage, Toast.LENGTH_LONG).show()
        return
    }
    val chooser = Intent.createChooser(generic, chooserTitle)
    if (waze != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Parcelable>(waze))
    context.startActivity(chooser)
}

/** The intent that opens the position in [app], or in the apps that open `geo:` links when [app] is null. */
private fun pointIntent(
    app: String?,
    lat: Double,
    lon: Double,
): Intent {
    val target: PointTarget = PointLinks.target(app, lat, lon)
    return Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).apply { target.packageName?.let { setPackage(it) } }
}
