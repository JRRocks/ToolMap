package fr.toolmap.draw

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.toolmap.R
import fr.toolmap.core.route.RouteMarkers
import fr.toolmap.core.route.RoutePlan
import fr.toolmap.core.track.ElevationProfile
import fr.toolmap.core.track.NameDates
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.core.track.TrackPath
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.WaypointSymbols
import fr.toolmap.map.ActionTile
import fr.toolmap.map.ActionTiles
import fr.toolmap.map.PANEL_INSET
import fr.toolmap.map.TextMenu
import fr.toolmap.map.ToolFooter
import fr.toolmap.map.ToolPanel
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.tracks.ConfirmDialog
import fr.toolmap.tracks.ElevationProfileView
import fr.toolmap.tracks.RenameDialog
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton

/** Tolerance choices of "simplify", in meters. */
private val SIMPLIFY_TOLERANCES = listOf(1.0, 2.0, 5.0, 10.0, 25.0)

/**
 * The panel of a line being drawn or edited (P29.10, « Équerre »): a panel of the bar, floating above it, with the petrol
 * band (« Nouvelle trace », « Itinéraire » or the name of the line, its points and length) and ✕ to leave; the actions
 * at the crosshair as tiles (add, move, delete, free hand), then undo / redo, « Outils » and « Enregistrer », the only
 * filled button. Taps on the map are handled by the map screen, which hands them to the view model.
 *
 * A route also has « Suivre les chemins (BRouter) » (P19): on, the panel of the computed routes replaces the drawing
 * by hand (screens 2 to 5 of `docs/previews/p19-routing.html`); [hooks] gives it what it needs from the map.
 */
@Composable
fun DrawBar(
    ui: DrawUi,
    vm: DrawViewModel,
    center: () -> Pair<Double, Double>,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    hooks: RouteHooks? = null,
) {
    val fmt = LocalFormats.current
    var askQuit by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    var askTolerance by remember { mutableStateOf(false) }
    var stopsOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var chartOpen by remember { mutableStateOf(false) }
    var askMany by remember { mutableStateOf(false) }
    // « Quel point ? » (P30.1): a new point of interest at the crosshair, or one already placed.
    var poiSheet by remember { mutableStateOf<PoiTarget?>(null) }
    // « Simplifier… » of the question before following the ways: the route is computed once simplified.
    var routeAfterSimplify by remember { mutableStateOf(false) }
    val route = ui.route
    val quit = { if (ui.dirty) askQuit = true else vm.cancel() }
    // Back leaves the drawing as ✕ does: unsaved changes are not lost without asking. A stop taken at the crosshair
    // is put back first.
    BackHandler(enabled = backEnabled) {
        when {
            ui.zoneRadius != null -> vm.cancelZone()
            ui.taken != null -> vm.cancelTake()
            ui.movingPoi != null -> vm.cancelMovePoi()
            stopsOpen -> stopsOpen = false
            else -> quit()
        }
    }
    if (route != null) {
        // BRouter may have been installed (« Installer ») while the user was away.
        LifecycleResumeEffect(vm) {
            vm.refreshBRouter()
            onPauseOrDispose {}
        }
    }
    val reach = { hooks?.reachMeters?.invoke() ?: 0.0 }
    val onToggle: (Boolean) -> Unit = { on ->
        if (on && route != null && route.plan.isManual && route.plan.stops.size > MANY_STOPS) {
            askMany = true
        } else {
            vm.setRouting(on)
        }
    }
    val save = {
        if (ui.target is DrawTarget.NewTrack || ui.target is DrawTarget.NewRoute) askName = true else vm.save(null)
    }
    val unnamed = stringResource(R.string.tracks_unnamed)
    val takenLabel = route?.let { r -> ui.taken?.let { stopLabel(r.plan, it) } }
    val target = ui.target
    val title =
        when {
            ui.zoneRadius != null -> stringResource(R.string.route_zone_title)
            takenLabel != null -> stringResource(R.string.route_taken_title, takenLabel)
            ui.movingPoi != null -> stringResource(R.string.route_poi_taken_title)
            target is DrawTarget.Track -> target.name ?: unnamed
            target is DrawTarget.Route -> target.name ?: unnamed
            target == DrawTarget.NewRoute -> stringResource(R.string.draw_route_band)
            else -> stringResource(R.string.draw_new_track)
        }
    val length = fmt.distance(ui.lengthMeters)
    // A route that follows the ways shows its figures in the pill at the top of the map (P19).
    val status =
        when {
            ui.routed -> null
            ui.selected != null -> stringResource(R.string.draw_status_selected, ui.selected + 1, length)
            else -> stringResource(R.string.draw_status, ui.pointCount, length)
        }

    ToolPanel(
        title = title,
        maxHeight = maxHeight,
        modifier = modifier,
        subtitle = status,
        onClose = {
            when {
                ui.zoneRadius != null -> vm.cancelZone()
                ui.taken != null -> vm.cancelTake()
                ui.movingPoi != null -> vm.cancelMovePoi()
                else -> quit()
            }
        },
        closeDescription =
            stringResource(
                if (ui.taken != null ||
                    ui.zoneRadius != null ||
                    ui.movingPoi != null
                ) {
                    R.string.action_cancel
                } else {
                    R.string.draw_quit
                },
            ),
    ) {
        if (route != null && hooks != null && stopsOpen && route.plan.stops.isNotEmpty()) {
            Column(Modifier.padding(horizontal = PANEL_INSET)) {
                StopsSheet(
                    ui = ui,
                    vm = vm,
                    hooks = hooks,
                    onClose = { stopsOpen = false },
                    onEditPoi = { i ->
                        route.plan.pois.getOrNull(i)?.let { poiSheet = PoiTarget(i, it.lat, it.lon) }
                    },
                )
            }
        } else if (route != null && hooks != null && ui.routed) {
            RoutedPanel(
                ui = ui,
                route = route,
                vm = vm,
                hooks = hooks,
                center = center,
                onToggle = onToggle,
                onProfile = { profileOpen = true },
                onStops = { stopsOpen = true },
                onChart = { chartOpen = true },
                onSave = save,
                onPoi = { i ->
                    val (lat, lon) = center()
                    poiSheet = PoiTarget(i, lat, lon)
                },
            )
        } else {
            if (route != null) {
                if (ui.movingPoi == null) {
                    RoutingSwitchRow(
                        route = route,
                        onToggle = onToggle,
                        onProfile = { profileOpen = true },
                        onInstall = { hooks?.onInstall?.invoke() },
                        modifier = Modifier.padding(start = PANEL_INSET, end = 6.dp),
                    )
                }
                if (ui.pointCount > 0 && ui.movingPoi == null) PoseToggle(ui.poiMode, vm::setPoiMode)
            }
            if (ui.pointCount == 0) {
                Text(
                    stringResource(R.string.draw_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val handPoi by remember(route?.plan) { derivedStateOf { hooks?.poiUnder?.invoke() } }
            val moving = ui.movingPoi
            if (route != null && moving != null) {
                MovingPoiTiles(moving, vm, center)
            } else {
                ActionTiles {
                    val poi = handPoi
                    if (route != null && poi != null) {
                        ActionTile(Icons.Filled.Edit, stringResource(R.string.route_poi_edit), highlighted = true) {
                            val (lat, lon) = center()
                            poiSheet = PoiTarget(poi, lat, lon)
                        }
                    } else if (route != null && ui.poiMode && ui.pointCount > 0) {
                        ActionTile(RouteIcons.PushPin, stringResource(R.string.route_poi_add), highlighted = true) {
                            val (lat, lon) = center()
                            poiSheet = PoiTarget(null, lat, lon)
                        }
                    } else {
                        ActionTile(
                            RouteIcons.AddLocationAlt,
                            stringResource(R.string.draw_add_here),
                            highlighted = true,
                        ) {
                            val (lat, lon) = center()
                            vm.addAt(lat, lon, reach())
                        }
                    }
                    ActionTile(RouteIcons.OpenWith, stringResource(R.string.draw_move_here), ui.selected != null) {
                        val (lat, lon) = center()
                        vm.moveSelectedTo(lat, lon)
                    }
                    ActionTile(
                        RouteIcons.Delete,
                        stringResource(R.string.draw_delete_point),
                        ui.selected != null,
                        onClick = vm::deleteSelected,
                    )
                    ActionTile(
                        RouteIcons.Gesture,
                        stringResource(R.string.draw_freehand),
                        selected = ui.freehand,
                    ) { vm.setFreehand(!ui.freehand) }
                }
            }
            ToolFooter(
                onUndo = vm::undo,
                canUndo = ui.canUndo,
                onRedo = vm::redo,
                canRedo = ui.canRedo,
                menu = {
                    TextMenu(stringResource(R.string.draw_tools)) { close ->
                        ToolItem(R.string.draw_tool_reverse, ui.pointCount >= 2) {
                            close()
                            vm.reverse()
                        }
                        ToolItem(R.string.draw_tool_simplify, ui.pointCount >= 3) {
                            close()
                            askTolerance = true
                        }
                        ToolItem(R.string.draw_tool_trim_before, ui.selected != null) {
                            close()
                            vm.trimBeforeSelected()
                        }
                        ToolItem(R.string.draw_tool_trim_after, ui.selected != null) {
                            close()
                            vm.trimAfterSelected()
                        }
                        if (ui.target is DrawTarget.Track) {
                            ToolItem(R.string.draw_tool_split, ui.selected != null) {
                                close()
                                vm.splitAtSelected()
                            }
                        }
                        ToolItem(R.string.draw_tool_clear, ui.pointCount > 0) {
                            close()
                            vm.clear()
                        }
                    }
                },
                main = {
                    Button(onClick = save, enabled = ui.pointCount >= 2) { Text(stringResource(R.string.draw_save)) }
                },
            )
        }
    }

    poiSheet?.let { target ->
        val index = target.index
        val existing = index?.let { route?.plan?.pois?.getOrNull(it) }
        if (index == null || existing != null) {
            PoiSheet(
                poi = existing,
                onDone = { name, sym ->
                    poiSheet = null
                    if (index == null) vm.addPoi(target.lat, target.lon, name, sym) else vm.editPoi(index, name, sym)
                },
                onDismiss = { poiSheet = null },
                onMove =
                    index?.let { i ->
                        {
                            poiSheet = null
                            val (lat, lon) = center()
                            vm.takePoi(i, lat, lon)
                        }
                    },
                onDelete =
                    index?.let { i ->
                        {
                            poiSheet = null
                            vm.removePoi(i)
                        }
                    },
            )
        }
    }
    if (askQuit) {
        ConfirmDialog(
            title = stringResource(R.string.draw_quit_title),
            message = stringResource(R.string.draw_quit_message),
            confirmLabel = stringResource(R.string.draw_quit_confirm),
            onConfirm = {
                askQuit = false
                vm.cancel()
            },
            onDismiss = { askQuit = false },
        )
    }
    if (askName) {
        val date = NameDates.format(System.currentTimeMillis())
        RenameDialog(
            title = stringResource(if (ui.isRoute) R.string.draw_name_route else R.string.draw_name_track),
            initial =
                stringResource(if (ui.isRoute) R.string.draw_default_route_name else R.string.draw_default_name, date),
            onConfirm = { name ->
                askName = false
                vm.save(name)
            },
            onDismiss = { askName = false },
        )
    }
    if (askTolerance) {
        AlertDialog(
            onDismissRequest = {
                askTolerance = false
                routeAfterSimplify = false
            },
            title = { Text(stringResource(R.string.draw_simplify_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.draw_simplify_ask))
                    SIMPLIFY_TOLERANCES.forEach { meters ->
                        TextButton(onClick = {
                            askTolerance = false
                            vm.simplify(meters)
                            if (routeAfterSimplify) {
                                routeAfterSimplify = false
                                vm.routeAllNow()
                            }
                        }) { Text(fmt.distance(meters)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    askTolerance = false
                    routeAfterSimplify = false
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (askMany && route != null) {
        AlertDialog(
            onDismissRequest = { askMany = false },
            title = { Text(stringResource(R.string.route_many_title)) },
            text = { Text(stringResource(R.string.route_many_message, route.plan.stops.size)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        askMany = false
                        vm.routeAllNow()
                    }) { Text(stringResource(R.string.route_many_anyway)) }
                    TextButton(onClick = {
                        askMany = false
                        routeAfterSimplify = true
                        askTolerance = true
                    }) { Text(stringResource(R.string.route_many_simplify)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { askMany = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (profileOpen && route != null) {
        ProfileDialog(
            route = route,
            onPick = { p, all ->
                profileOpen = false
                vm.setProfile(p, all)
            },
            onDismiss = { profileOpen = false },
        )
    }
    if (chartOpen && route != null) {
        RouteProfileSheet(
            route = route,
            onCursor = vm::setCursor,
            onDismiss = {
                chartOpen = false
                vm.setCursor(null)
            },
        )
    }
}

/** A route drawn by hand with more stops than this asks before following the ways (screen 5). */
private const val MANY_STOPS = 30

/**
 * The panel of a route that follows the ways (screen 2, variant A; tiles since P29.10): the switch and the profile
 * chip; « Insérer ici » (« Ajouter ici » when the crosshair is off the route, « Prendre l'étape » when it is on a stop),
 * move and delete the selected stop, the list of the stops; undo, redo, the tools and « Enregistrer ». Empty, it says
 * where to start and offers « Depuis ma position ». While a stop is taken (variant A of
 * `docs/previews/p19-reprendre-etape.html`), only « Poser ici », « Annuler » and « Supprimer l'étape » stay. The
 * crosshair in a zone to avoid offers « Retirer la zone »; while one is placed, only its radii, « Éviter cette zone »
 * and « Annuler » stay (P19.8).
 */
@Composable
private fun RoutedPanel(
    ui: DrawUi,
    route: RouteUi,
    vm: DrawViewModel,
    hooks: RouteHooks,
    center: () -> Pair<Double, Double>,
    onToggle: (Boolean) -> Unit,
    onProfile: () -> Unit,
    onStops: () -> Unit,
    onChart: () -> Unit,
    onSave: () -> Unit,
    /** « Ajouter le point » (`null`, at the crosshair) or « Modifier le point » of point of interest N (P30.1). */
    onPoi: (Int?) -> Unit,
) {
    val empty = route.plan.stops.isEmpty()
    // Follows the camera, but the panel is only composed again when the answer changes.
    val insertLeg by remember(route.plan) { derivedStateOf { hooks.insertLegAt() } }
    val under by remember(route.plan) { derivedStateOf { hooks.stopUnder() } }
    val zone by remember(route.plan) { derivedStateOf { hooks.zoneUnder() } }
    val poiHover by remember(route.plan) { derivedStateOf { hooks.poiUnder() } }
    val taken = ui.taken
    val placing = ui.zoneRadius
    val movingPoi = ui.movingPoi
    if (empty && taken == null && placing == null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                stringResource(R.string.route_empty_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                stringResource(R.string.route_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // The crosshair in a zone to avoid, on no stop: how to change its size takes the place of the switch (P30.4).
    val inHand = taken != null || placing != null || movingPoi != null
    val sizing = !empty && !inHand && under == null && poiHover == null && zone != null
    if (sizing) {
        Text(
            stringResource(R.string.route_zone_resize_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else if (!inHand) {
        RoutingSwitchRow(
            route = route,
            onToggle = onToggle,
            onProfile = onProfile,
            onInstall = hooks.onInstall,
            modifier = Modifier.padding(start = PANEL_INSET, end = 6.dp),
        )
    }
    // « Poser : Étape | Point d'intérêt » (P30.1, variant B of `docs/previews/p30-itineraires.html`).
    if (!empty && !inHand) PoseToggle(ui.poiMode, vm::setPoiMode)
    val add = {
        val (lat, lon) = center()
        val leg = insertLeg
        if (leg != null) vm.insertAt(leg, lat, lon, hooks.reachMeters()) else vm.addAt(lat, lon, hooks.reachMeters())
    }
    if (placing != null) {
        ZoneTiles(placing, vm, center)
    } else if (taken != null) {
        TakenStopTiles(vm, center)
    } else if (movingPoi != null) {
        MovingPoiTiles(movingPoi, vm, center)
    } else if (empty) {
        ActionTiles {
            val position = hooks.position()
            if (position != null) {
                ActionTile(SettingsIcons.MyLocation, stringResource(R.string.route_from_position), minHeight = TILE) {
                    vm.addAt(position.first, position.second, hooks.reachMeters())
                }
            }
            ActionTile(
                RouteIcons.AddLocationAlt,
                stringResource(R.string.draw_add_here),
                minHeight = TILE,
                highlighted = true,
                onClick = add,
            )
        }
    } else {
        ActionTiles {
            val stop = under
            val poi = poiHover
            val inZone = zone
            val inserting = insertLeg != null
            if (poi != null && (ui.poiMode || stop == null)) {
                // The crosshair on a point of interest (a stop comes first, unless points of interest are put).
                ActionTile(
                    Icons.Filled.Edit,
                    stringResource(R.string.route_poi_edit),
                    minHeight = TILE,
                    highlighted = true,
                ) {
                    onPoi(poi)
                }
            } else if (ui.poiMode) {
                ActionTile(
                    RouteIcons.PushPin,
                    stringResource(R.string.route_poi_add),
                    minHeight = TILE,
                    highlighted = true,
                ) {
                    onPoi(null)
                }
            } else if (stop == null && inZone != null) {
                // The crosshair in a zone to avoid (a stop comes first, the route after the zone).
                ActionTile(
                    RouteIcons.Block,
                    stringResource(R.string.route_zone_remove),
                    minHeight = TILE,
                    danger = true,
                ) { vm.removeZone(inZone) }
            } else if (stop != null) {
                // The crosshair on a stop: take it, as « Ajouter ici » becomes « Insérer ici » on the route.
                ActionTile(
                    RouteIcons.PanTool,
                    stringResource(R.string.route_take_stop, stopLabel(route.plan, stop)),
                    minHeight = TILE,
                    highlighted = true,
                ) {
                    val (lat, lon) = center()
                    vm.take(stop, lat, lon)
                }
            } else {
                ActionTile(
                    if (inserting) SettingsIcons.AltRoute else RouteIcons.AddLocationAlt,
                    stringResource(if (inserting) R.string.route_insert_here else R.string.draw_add_here),
                    minHeight = TILE,
                    highlighted = true,
                    onClick = add,
                )
            }
            ActionTile(
                RouteIcons.OpenWith,
                stringResource(R.string.draw_move_here),
                ui.selected != null,
                minHeight = TILE,
            ) {
                val (lat, lon) = center()
                vm.moveSelectedTo(lat, lon)
            }
            ActionTile(
                RouteIcons.Delete,
                stringResource(R.string.route_delete_stop),
                ui.selected != null,
                minHeight = TILE,
                onClick = vm::deleteSelected,
            )
            ActionTile(
                RouteIcons.FormatListNumbered,
                stringResource(R.string.route_stops, route.plan.stops.size),
                minHeight = TILE,
                onClick = onStops,
            )
        }
    }
    ToolFooter(
        onUndo = vm::undo,
        canUndo = ui.canUndo,
        onRedo = vm::redo,
        canRedo = ui.canRedo,
        menu = {
            TextMenu(stringResource(R.string.draw_tools), enabled = !empty) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.route_tool_at_start)) },
                    leadingIcon = { Checkbox(checked = route.atStart, onCheckedChange = null) },
                    onClick = {
                        close()
                        vm.setAtStart(!route.atStart)
                    },
                )
                HorizontalDivider()
                RouteTool(R.string.route_tool_reverse, RouteIcons.SwapVert, ui.pointCount >= 2) {
                    close()
                    vm.reverse()
                }
                RouteTool(R.string.route_tool_loop, RouteIcons.Loop, ui.pointCount >= 2) {
                    close()
                    vm.loopToStart()
                }
                RouteTool(R.string.route_tool_out_back, RouteIcons.SyncAlt, ui.pointCount >= 2) {
                    close()
                    vm.outAndBack()
                }
                RouteTool(
                    R.string.route_tool_avoid_zone,
                    RouteIcons.DoNotDisturbOn,
                    enabled = true,
                    tint = MaterialTheme.colorScheme.error,
                ) {
                    close()
                    vm.startZone()
                }
                HorizontalDivider()
                RouteTool(R.string.route_tool_profile, RouteIcons.ShowChart, ui.pointCount >= 2) {
                    close()
                    onChart()
                }
                RouteTool(R.string.draw_tool_clear, RouteIcons.DeleteSweep, ui.pointCount > 0) {
                    close()
                    vm.clear()
                }
            }
        },
        main = { Button(onClick = onSave, enabled = ui.pointCount >= 2) { Text(stringResource(R.string.draw_save)) } },
    )
}

/** The height of the tiles of the route panel: their names may take two lines (« Supprimer l'étape »). */
private val TILE = 76.dp

/** A stop taken at the crosshair: « Poser ici » (the main action, twice as wide), « Annuler », « Supprimer l'étape ». */
@Composable
private fun TakenStopTiles(
    vm: DrawViewModel,
    center: () -> Pair<Double, Double>,
) {
    Text(
        stringResource(R.string.route_taken_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    ActionTiles {
        ActionTile(
            RouteIcons.PinDrop,
            stringResource(R.string.route_put_down),
            minHeight = TILE,
            highlighted = true,
            weight = 2f,
        ) {
            val (lat, lon) = center()
            vm.putDown(lat, lon)
        }
        ActionTile(RouteIcons.Undo, stringResource(R.string.action_cancel), minHeight = TILE, onClick = vm::cancelTake)
        ActionTile(
            RouteIcons.Delete,
            stringResource(R.string.route_delete_stop),
            minHeight = TILE,
            onClick = vm::deleteSelected,
        )
    }
}

/** A point of interest taken at the crosshair (« Déplacer », P30.1): « Poser ici », « Annuler », « Supprimer le point ». */
@Composable
private fun MovingPoiTiles(
    index: Int,
    vm: DrawViewModel,
    center: () -> Pair<Double, Double>,
) {
    Text(
        stringResource(R.string.route_taken_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    ActionTiles {
        ActionTile(
            RouteIcons.PinDrop,
            stringResource(R.string.route_put_down),
            minHeight = TILE,
            highlighted = true,
            weight = 2f,
        ) {
            val (lat, lon) = center()
            vm.putDownPoi(lat, lon)
        }
        ActionTile(
            RouteIcons.Undo,
            stringResource(R.string.action_cancel),
            minHeight = TILE,
            onClick = vm::cancelMovePoi,
        )
        ActionTile(RouteIcons.Delete, stringResource(R.string.route_poi_delete), minHeight = TILE) {
            vm.removePoi(index)
        }
    }
}

/**
 * « Poser : Étape | Point d'intérêt » (P30.1, variant B of `docs/previews/p30-itineraires.html`): on « Point d'intérêt »
 * the main tile is « Ajouter le point » and the crosshair a pin; the choice is kept while the app runs.
 */
@Composable
private fun PoseToggle(
    poi: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val choices = listOf(false to R.string.route_pose_stop, true to R.string.route_pose_poi)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET, vertical = 2.dp)) {
        choices.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == poi,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {
                    Icon(
                        if (value) RouteIcons.PushPin else RouteIcons.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** What « Quel point ? » is open on: a new point of interest at [lat], [lon] ([index] `null`), or point [index]. */
private data class PoiTarget(
    val index: Int?,
    val lat: Double,
    val lon: Double,
)

/** The radii offered for a zone to avoid (P19.8), in meters. */
private val ZONE_RADII = listOf(100, 250, 500, 1000)

/**
 * A zone to avoid being placed (variant A of `docs/previews/p19-nogos.html`): the hint, the four radii (the one picked
 * is kept for next time), « Éviter cette zone » (the main action, twice as wide) and « Annuler ».
 */
@Composable
private fun ZoneTiles(
    radius: Int,
    vm: DrawViewModel,
    center: () -> Pair<Double, Double>,
) {
    Text(
        stringResource(R.string.route_zone_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Row(Modifier.padding(horizontal = PANEL_INSET), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZONE_RADII.forEach { meters ->
            FilterChip(
                selected = meters == radius,
                onClick = { vm.setZoneRadius(meters) },
                label = { Text(LocalFormats.current.size(meters)) },
            )
        }
    }
    ActionTiles {
        ActionTile(
            RouteIcons.DoNotDisturbOn,
            stringResource(R.string.route_zone_place),
            minHeight = TILE,
            highlighted = true,
            weight = 2f,
            iconTint = MaterialTheme.colorScheme.error,
        ) {
            val (lat, lon) = center()
            vm.placeZone(lat, lon)
        }
        ActionTile(RouteIcons.Undo, stringResource(R.string.action_cancel), minHeight = TILE, onClick = vm::cancelZone)
    }
}

/** How the panel names stop [i]: its letter at the ends (as on the map), else its number. */
private fun stopLabel(
    plan: RoutePlan,
    i: Int,
): String =
    when {
        i == 0 || (i == plan.stops.lastIndex && plan.isLoop) -> RouteMarkers.letter(0)
        i == plan.stops.lastIndex -> RouteMarkers.letter(i)
        else -> i.toString()
    }

@Composable
private fun RouteTool(
    label: Int,
    icon: ImageVector,
    enabled: Boolean,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint ?: LocalContentColor.current) },
        onClick = onClick,
        enabled = enabled,
    )
}

/** « Profil altimétrique » of the tools: the profile of the tracks (P5.5) on the route being edited, tied to the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteProfileSheet(
    route: RouteUi,
    onCursor: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val profile = remember(route.plan) { ElevationProfile.build(TrackPath(listOf(route.plan.geometry))) }
    // The points of interest at their distance from the start, in the colour of their icon (P30.1).
    val marks =
        remember(route.plan) {
            route.plan.pois.indices.mapNotNull { i ->
                route.plan.placeOf(i)?.let { it.alongMeters to WaypointSymbols.colorOf(route.plan.pois[i].sym) }
            }
        }
    var cursor by remember { mutableStateOf<Double?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.tracks_profile_title), style = MaterialTheme.typography.titleMedium)
            if (profile == null) {
                Text(stringResource(R.string.route_profile_none))
            } else {
                ElevationProfileView(
                    profile = profile,
                    cursorMeters = cursor,
                    onCursor = { at ->
                        cursor = at
                        onCursor(at)
                    },
                    marks = marks,
                )
            }
        }
    }
}

@Composable
private fun ToolItem(
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, enabled = enabled)
}

/** Asks which segment of a multi-segment track to edit. */
@Composable
fun SegmentChooser(
    sizes: List<Int>,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.draw_segment_title)) },
        text = {
            Column {
                sizes.forEachIndexed { i, count ->
                    TextButton(onClick = { onPick(i) }) {
                        Text(stringResource(R.string.draw_segment_item, i + 1, count))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * A transparent layer over the map that turns a finger stroke into a line: the stroke is drawn as it goes, and its
 * screen positions are converted to map positions by [toPoint] when the finger lifts. While it is shown the map does
 * not pan: that is what makes free-hand drawing possible.
 */
@Composable
fun FreehandLayer(
    toPoint: (Float, Float) -> TrackPoint?,
    onStroke: (List<TrackPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stroke = remember { mutableStateListOf<Offset>() }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            stroke.clear()
                            stroke.add(start)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            stroke.add(change.position)
                        },
                        onDragEnd = {
                            onStroke(stroke.mapNotNull { toPoint(it.x, it.y) })
                            stroke.clear()
                        },
                        onDragCancel = { stroke.clear() },
                    )
                },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 1 until stroke.size) {
                drawLine(
                    color = Color(0xFFFF6F00),
                    start = stroke[i - 1],
                    end = stroke[i],
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
