package fr.toolmap.draw

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.route.RoutingError
import fr.toolmap.data.routing.BRouterEngine
import fr.toolmap.ui.theme.TextButton

/** A [RouteNotice] shown in the map's snackbar host, which draws it with [RouteNoticeSnackbar]. */
class RouteNoticeVisuals(
    val notice: RouteNotice,
    override val message: String,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Long

    companion object {
        fun of(
            resources: Resources,
            notice: RouteNotice,
        ): RouteNoticeVisuals {
            val text =
                when (notice) {
                    is RouteNotice.LegByHand -> {
                        resources.getString(
                            when (notice.error) {
                                RoutingError.NO_DATA, RoutingError.NOT_INSTALLED, RoutingError.UNKNOWN_PROFILE -> {
                                    R.string.route_no_data_leg
                                }

                                RoutingError.NO_PATH -> {
                                    R.string.route_no_path_leg
                                }

                                RoutingError.TIMEOUT -> {
                                    R.string.route_timeout_leg
                                }

                                RoutingError.OTHER -> {
                                    R.string.route_failed_leg
                                }
                            },
                        )
                    }

                    is RouteNotice.AllByHand -> {
                        resources.getString(
                            when (notice.error) {
                                RoutingError.NO_DATA, RoutingError.NOT_INSTALLED, RoutingError.UNKNOWN_PROFILE -> {
                                    R.string.route_no_data_all
                                }

                                RoutingError.NO_PATH -> {
                                    R.string.route_no_path_all
                                }

                                RoutingError.TIMEOUT -> {
                                    R.string.route_timeout_all
                                }

                                RoutingError.OTHER -> {
                                    R.string.route_failed_all
                                }
                            },
                        )
                    }

                    is RouteNotice.UnknownProfile -> {
                        resources.getString(R.string.route_unknown_profile, notice.profile)
                    }
                }
            return RouteNoticeVisuals(notice, text)
        }
    }
}

/**
 * The notice of a leg BRouter could not compute (screen 4 of `docs/previews/p19-routing.html`): the message, then
 * « Tout dessiner à la main » when the routing goes on, and « Ouvrir BRouter » to download the missing data.
 */
@Composable
fun RouteNoticeSnackbar(
    visuals: RouteNoticeVisuals,
    onAllByHand: () -> Unit,
    onOpenBRouter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Snackbar(modifier = modifier.padding(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(visuals.message)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                val actionColor = MaterialTheme.colorScheme.inversePrimary
                if (visuals.notice is RouteNotice.LegByHand) {
                    TextButton(onClick = onAllByHand) {
                        Text(stringResource(R.string.route_all_by_hand), color = actionColor)
                    }
                }
                TextButton(onClick = onOpenBRouter) {
                    Text(stringResource(R.string.route_open_brouter), color = actionColor)
                }
            }
        }
    }
}

/** Opens the BRouter app (to download the routing data of an area), or its page to install it. */
fun openBRouter(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(BRouterEngine.PACKAGE)
    if (launch != null) {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
        installBRouter(context)
    }
}

/** « Installer » of the routing switch: BRouter on F-Droid, else the store of the phone. */
fun installBRouter(context: Context) {
    val pages = listOf(F_DROID_PAGE, "market://details?id=${BRouterEngine.PACKAGE}")
    for (page in pages) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next page.
        }
    }
}

private const val F_DROID_PAGE = "https://f-droid.org/packages/btools.routingapp/"
