import SwiftUI
import ToolMapCore

/// Dessin d'une trace ou d'un itinéraire (Android T1 `DrawBar`, T1b `RoutedPanel`, P29.10 « Équerre »): the petrol band
/// (« Nouvelle trace », « Itinéraire » or the name of the line, « Points : 12 · 3,4 km ») and ✕; the actions at the
/// crosshair as tiles, then undo, redo, « Outils ▾ » and « Enregistrer », the only filled button. A route has « Suivre
/// les chemins (BRouter) »: on, the panel of the computed routes (Insérer ici, Prendre l'étape, Étapes, zones to avoid,
/// points of interest) replaces the drawing by hand.
struct DrawPanel: View {
    @Environment(AppModel.self) private var model
    let kind: DrawKind

    @State private var askQuit = false
    @State private var askName = false
    @State private var nameText = ""
    @State private var askTolerance = false
    @State private var routeAfterSimplify = false
    @State private var askMany = false
    @State private var stopsOpen = false
    @State private var profileChoice: RouteProfileChoice?
    @State private var chartOpen = false
    @State private var place: RoutePlaceTarget?

    var body: some View {
        let draw = model.draw
        ToolPanel(title: title(draw), subtitle: subtitle(draw), onClose: { close(draw) }) {
            DrawPanelContent(
                stopsOpen: $stopsOpen,
                actions: actions
            )
        }
        .onAppear { model.draw.open(kind) }
        .onChange(of: kind) { _, newKind in
            model.draw.open(newKind)
        }
        .onChange(of: model.center) { _, center in
            model.draw.crosshairMoved(center)
        }
        .onDisappear {
            // Another tool took the place; a page in front of the map leaves the drawing as it is.
            if !DrawPanel.isDrawing(model.tool) {
                model.draw.finish()
            }
        }
        .modifier(
            DrawDialogs(
                askQuit: $askQuit,
                askName: $askName,
                nameText: $nameText,
                askTolerance: $askTolerance,
                routeAfterSimplify: $routeAfterSimplify,
                askMany: $askMany
            )
        )
        .modifier(DrawSheets(profileChoice: $profileChoice, chartOpen: $chartOpen, place: $place))
    }

    static func isDrawing(_ tool: Tool?) -> Bool {
        guard let current = tool, case .draw = current else { return false }
        return true
    }

    /// What the panel's buttons open, handed to its parts.
    private var actions: DrawPanelActions {
        DrawPanelActions(
            save: { save() },
            toggleRouting: { on in toggleRouting(on) },
            simplify: { askTolerance = true },
            profile: { profileChoice = RouteProfileChoice(leg: nil) },
            legProfile: { leg in profileChoice = RouteProfileChoice(leg: leg) },
            chart: { chartOpen = true },
            place: { target in place = target }
        )
    }

    private func title(_ draw: DrawController) -> String {
        if draw.zoneRadius != nil { return "Zone à éviter" }
        if let i = draw.taken, let current = draw.plan {
            return "Étape \(RouteText.stopLabel(current, i)) en main"
        }
        if draw.movingPoi != nil { return "Point en main" }
        switch draw.target {
        case let .track(_, name, _, _)?:
            return name ?? RouteText.unnamed
        case let .route(_, name, _, _)?:
            return name ?? RouteText.unnamed
        case .newRoute?:
            return "Itinéraire"
        default:
            return "Nouvelle trace"
        }
    }

    /// « Points : 12 · 3,4 km », « Point sélectionné : n° 3 · 1,2 km »; a route that follows the ways shows its
    /// figures in the pill at the top of the map instead.
    private func subtitle(_ draw: DrawController) -> String? {
        if draw.routed || !draw.active { return nil }
        let length = MapText.distance(draw.lengthMeters, model.settings)
        if let i = draw.selected {
            return "Point sélectionné : n° \(i + 1) · \(length)"
        }
        return "Points : \(draw.pointCount) · \(length)"
    }

    private func close(_ draw: DrawController) {
        if draw.zoneRadius != nil {
            draw.cancelZone()
        } else if draw.taken != nil {
            draw.cancelTake()
        } else if draw.movingPoi != nil {
            draw.cancelMovePoi()
        } else if stopsOpen {
            stopsOpen = false
        } else if draw.dirty {
            askQuit = true
        } else {
            draw.finish()
        }
    }

    private func save() {
        let draw = model.draw
        if draw.needsName {
            nameText = draw.defaultName()
            askName = true
        } else {
            draw.save(name: nil)
        }
    }

    /// Following the ways a route drawn by hand of many points asks first (Android screen 5).
    private func toggleRouting(_ on: Bool) {
        let draw = model.draw
        if on, let current = draw.plan, current.isManual, current.stops.count > DrawController.manyStops {
            askMany = true
        } else {
            draw.setRouting(on)
        }
    }
}

/// What the parts of the panel ask of it.
struct DrawPanelActions {
    let save: () -> Void
    let toggleRouting: (Bool) -> Void
    let simplify: () -> Void
    let profile: () -> Void
    let legProfile: (Int) -> Void
    let chart: () -> Void
    let place: (RoutePlaceTarget) -> Void
}

/// The profile sheet: of the route (`leg` nil) or of the leg after stop `leg`.
struct RouteProfileChoice: Identifiable {
    let id = UUID()
    let leg: Int?
}

/// What « Quel point ? » or « Nom de l'étape » is open on.
struct RoutePlaceTarget: Identifiable {
    enum Kind {
        /// A new point of interest at `position` (`index` nil), or point of interest `index`.
        case poi
        /// The name and icon of stop `index`.
        case stop
    }

    let id = UUID()
    let kind: Kind
    let index: Int?
    let position: LonLat
}

/// The content of the panel: the stops, the panel of the computed routes, or the drawing by hand.
private struct DrawPanelContent: View {
    @Environment(AppModel.self) private var model
    @Binding var stopsOpen: Bool
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        VStack(alignment: .leading, spacing: 8) {
            if draw.segmentChoices != nil || !draw.active {
                Text("Ouverture…")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            } else if stopsOpen, let current = draw.plan, !current.stops.isEmpty {
                RouteStopsList(plan: current, onClose: { stopsOpen = false }, actions: actions)
            } else if draw.routed {
                RoutedContent(actions: actions, onStops: { stopsOpen = true })
            } else {
                HandContent(actions: actions)
            }
        }
    }
}

// MARK: - Drawing by hand (T1)

private struct HandContent: View {
    @Environment(AppModel.self) private var model
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        VStack(alignment: .leading, spacing: 8) {
            if draw.isRoute, draw.movingPoi == nil {
                RoutingSwitchRow(onToggle: actions.toggleRouting, onProfile: actions.profile)
            }
            if draw.isRoute, draw.pointCount > 0, draw.movingPoi == nil {
                PoseToggle()
            }
            if draw.pointCount == 0 {
                Text("Touchez la carte pour ajouter un point, ou placez le viseur et touchez « Ajouter ici ».")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            if draw.isRoute, let index = draw.movingPoi {
                MovingPoiTiles(index: index)
            } else {
                HandTiles(actions: actions)
            }
            DrawFooter(tools: { HandTools(actions: actions) }, toolsEnabled: true, onSave: actions.save)
        }
    }
}

/// Ajouter ici (or the point of interest at the crosshair) · Déplacer ici · Supprimer le point · Main levée.
private struct HandTiles: View {
    @Environment(AppModel.self) private var model
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        let center = model.center
        let poi = draw.isRoute ? draw.poiUnder(center) : nil
        // Putting points of interest, or on one: the point tiles are greyed out (variant 1B).
        let pointTiles = draw.selected != nil && (!draw.isRoute || (poi == nil && !draw.poiMode))
        HStack(spacing: 8) {
            if let found = poi {
                DrawTile(systemImage: "pencil", title: "Modifier le point", style: .primary) {
                    actions.place(RoutePlaceTarget(kind: .poi, index: found, position: center))
                }
            } else if draw.isRoute, draw.poiMode, draw.pointCount > 0 {
                DrawTile(systemImage: "pin", title: "Ajouter le point", style: .primary) {
                    actions.place(RoutePlaceTarget(kind: .poi, index: nil, position: center))
                }
            } else {
                DrawTile(systemImage: "plus.circle", title: "Ajouter ici", style: .primary) {
                    draw.addHere()
                }
            }
            DrawTile(systemImage: "arrow.up.and.down.and.arrow.left.and.right", title: "Déplacer ici", enabled: pointTiles) {
                draw.moveSelectedHere()
            }
            DrawTile(systemImage: "trash", title: "Supprimer le point", enabled: pointTiles) {
                draw.deleteSelected()
            }
            DrawTile(systemImage: "scribble", title: "Main levée", style: draw.freehand ? .selected : .normal) {
                draw.setFreehand(!draw.freehand)
            }
        }
    }
}

/// Outils ▾ of the drawing by hand: Inverser le sens · Simplifier… · Rogner avant · Rogner après · Couper · Tout effacer.
private struct HandTools: View {
    @Environment(AppModel.self) private var model
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        Button {
            draw.reverse()
        } label: {
            Label("Inverser le sens", systemImage: "arrow.up.arrow.down")
        }
        .disabled(draw.pointCount < 2)
        Button {
            actions.simplify()
        } label: {
            Label("Simplifier…", systemImage: "wand.and.stars")
        }
        .disabled(draw.pointCount < 3)
        Button {
            draw.trimBeforeSelected()
        } label: {
            Label("Rogner avant le point", systemImage: "scissors")
        }
        .disabled(draw.selected == nil)
        Button {
            draw.trimAfterSelected()
        } label: {
            Label("Rogner après le point", systemImage: "scissors")
        }
        .disabled(draw.selected == nil)
        if draw.editsStoredTrack {
            Button {
                draw.splitAtSelected()
            } label: {
                Label("Couper en deux ici", systemImage: "square.split.2x1")
            }
            .disabled(draw.selected == nil)
        }
        Button(role: .destructive) {
            draw.clear()
        } label: {
            Label("Tout effacer", systemImage: "trash")
        }
        .disabled(draw.pointCount == 0)
    }
}

// MARK: - Computed routes (T1b)

private struct RoutedContent: View {
    @Environment(AppModel.self) private var model
    let actions: DrawPanelActions
    let onStops: () -> Void

    var body: some View {
        let draw = model.draw
        let empty = draw.pointCount == 0
        let inHand = draw.taken != nil || draw.zoneRadius != nil || draw.movingPoi != nil
        VStack(alignment: .leading, spacing: 8) {
            if empty, !inHand {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Aucune étape")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Crete.onSurface)
                    Text("Placez le réticule sur le départ, puis « Ajouter ici ».")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            RoutedHeader(empty: empty, inHand: inHand, actions: actions)
            RoutedTiles(empty: empty, onStops: onStops, actions: actions)
            DrawFooter(tools: { RoutedTools(actions: actions) }, toolsEnabled: !empty, onSave: actions.save)
        }
    }
}

/// The switch and the profile; the hint of the zone's handle when the crosshair is in a zone; « Étape | Point
/// d'intérêt ».
private struct RoutedHeader: View {
    @Environment(AppModel.self) private var model
    let empty: Bool
    let inHand: Bool
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        let center = model.center
        // The crosshair in a zone to avoid, on no stop: how to change its size takes the place of the switch (P30.4).
        let free = draw.stopUnder(center) == nil && draw.poiUnder(center) == nil
        let sizing = !empty && !inHand && free && draw.zoneUnder(center) != nil
        if sizing {
            Text("Tirez la poignée pour changer la taille (50 m à 2 km).")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
        } else if !inHand {
            RoutingSwitchRow(onToggle: actions.toggleRouting, onProfile: actions.profile)
        }
        if !empty, !inHand {
            PoseToggle()
        }
    }
}

/// The tiles of the computed route: what is in hand, or what the crosshair is on.
private struct RoutedTiles: View {
    @Environment(AppModel.self) private var model
    let empty: Bool
    let onStops: () -> Void
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        if let radius = draw.zoneRadius {
            ZoneTiles(radius: radius)
        } else if draw.taken != nil {
            TakenStopTiles()
        } else if let index = draw.movingPoi {
            MovingPoiTiles(index: index)
        } else if empty {
            EmptyRouteTiles()
        } else {
            StopTiles(onStops: onStops, actions: actions)
        }
    }
}

/// An empty route: « Depuis ma position » (when the position is known) and « Ajouter ici ».
private struct EmptyRouteTiles: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let draw = model.draw
        HStack(spacing: 8) {
            if model.location.location != nil {
                DrawTile(systemImage: "location.fill", title: "Depuis ma position") {
                    draw.addFromPosition()
                }
            }
            DrawTile(systemImage: "plus.circle", title: "Ajouter ici", style: .primary) {
                draw.addHere()
            }
        }
    }
}

/// « Insérer ici » / « Ajouter ici » / « Prendre l'étape N » / « Retirer la zone » / « Modifier le point », then
/// « Déplacer ici », « Supprimer l'étape », « Étapes (N) ».
private struct StopTiles: View {
    @Environment(AppModel.self) private var model
    let onStops: () -> Void
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        let center = model.center
        let stop = draw.stopUnder(center)
        let poi = draw.poiUnder(center)
        // Putting points of interest, or on one: the stop tiles are greyed out (variant 1B).
        let stopTiles = draw.selected != nil && !draw.poiMode && (poi == nil || stop != nil)
        HStack(spacing: 8) {
            StopMainTile(center: center, stop: stop, poi: poi, actions: actions)
            DrawTile(systemImage: "arrow.up.and.down.and.arrow.left.and.right", title: "Déplacer ici", enabled: stopTiles) {
                draw.moveSelectedHere()
            }
            DrawTile(systemImage: "trash", title: "Supprimer l'étape", enabled: stopTiles) {
                draw.deleteSelected()
            }
            DrawTile(systemImage: "list.number", title: "Étapes (\(draw.pointCount))") {
                onStops()
            }
        }
    }
}

/// The main tile, from what is under the crosshair: a stop first, then a point of interest, then a zone, then the route.
private struct StopMainTile: View {
    @Environment(AppModel.self) private var model
    let center: LonLat
    let stop: Int?
    let poi: Int?
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        if let found = poi, draw.poiMode || stop == nil {
            DrawTile(systemImage: "pencil", title: "Modifier le point", style: .primary) {
                actions.place(RoutePlaceTarget(kind: .poi, index: found, position: center))
            }
        } else if draw.poiMode {
            DrawTile(systemImage: "pin", title: "Ajouter le point", style: .primary) {
                actions.place(RoutePlaceTarget(kind: .poi, index: nil, position: center))
            }
        } else if stop == nil, let zone = draw.zoneUnder(center) {
            DrawTile(systemImage: "nosign", title: "Retirer la zone", style: .danger) {
                draw.removeZone(zone)
            }
        } else if let found = stop, let current = draw.plan {
            DrawTile(systemImage: "hand.raised", title: "Prendre l'étape \(RouteText.stopLabel(current, found))", style: .primary) {
                draw.take(found)
            }
        } else if let leg = draw.insertLegAt(center) {
            DrawTile(systemImage: "arrow.triangle.branch", title: "Insérer ici", style: .primary) {
                draw.insertHere(leg)
            }
        } else {
            DrawTile(systemImage: "plus.circle", title: "Ajouter ici", style: .primary) {
                draw.addHere()
            }
        }
    }
}

/// A stop taken at the crosshair: « Poser ici » (twice as wide), « Annuler », « Supprimer l'étape ».
private struct TakenStopTiles: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let draw = model.draw
        VStack(alignment: .leading, spacing: 8) {
            Text("Déplacez la carte, puis « Poser ici ».")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
            // « Poser ici » twice as wide as the others.
            HStack(spacing: 8) {
                DrawTile(systemImage: "mappin.and.ellipse", title: "Poser ici", style: .primary) {
                    draw.putDown()
                }
                .frame(maxWidth: .infinity)
                HStack(spacing: 8) {
                    DrawTile(systemImage: "arrow.uturn.backward", title: "Annuler") {
                        draw.cancelTake()
                    }
                    DrawTile(systemImage: "trash", title: "Supprimer l'étape") {
                        draw.deleteSelected()
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
    }
}

/// A point of interest taken at the crosshair: « Poser ici », « Annuler », « Supprimer le point ».
private struct MovingPoiTiles: View {
    @Environment(AppModel.self) private var model
    let index: Int

    var body: some View {
        let draw = model.draw
        VStack(alignment: .leading, spacing: 8) {
            Text("Déplacez la carte, puis « Poser ici ».")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
            HStack(spacing: 8) {
                DrawTile(systemImage: "mappin.and.ellipse", title: "Poser ici", style: .primary) {
                    draw.putDownPoi()
                }
                .frame(maxWidth: .infinity)
                HStack(spacing: 8) {
                    DrawTile(systemImage: "arrow.uturn.backward", title: "Annuler") {
                        draw.cancelMovePoi()
                    }
                    DrawTile(systemImage: "trash", title: "Supprimer le point") {
                        draw.removePoi(index)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
    }
}

/// A zone to avoid being placed: the hint, 100 m · 250 m · 500 m · 1 km, « Éviter cette zone » and « Annuler ».
private struct ZoneTiles: View {
    @Environment(AppModel.self) private var model
    let radius: Int

    var body: some View {
        let draw = model.draw
        VStack(alignment: .leading, spacing: 8) {
            Text("Placez le réticule au centre de la zone.")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
            HStack(spacing: 8) {
                ForEach(DrawController.zoneRadii, id: \.self) { meters in
                    RouteChip(title: RouteText.size(meters, model.settings), selected: meters == radius) {
                        draw.setZoneRadius(meters)
                    }
                }
            }
            HStack(spacing: 8) {
                DrawTile(systemImage: "minus.circle.fill", title: "Éviter cette zone", style: .primary) {
                    draw.placeZone()
                }
                DrawTile(systemImage: "arrow.uturn.backward", title: "Annuler") {
                    draw.cancelZone()
                }
            }
        }
    }
}

/// Outils ▾ of the computed route.
private struct RoutedTools: View {
    @Environment(AppModel.self) private var model
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        Toggle(isOn: Binding(get: { draw.atStart }, set: { draw.setAtStart($0) })) {
            Text("Ajouter au début")
        }
        Divider()
        Button {
            draw.reverse()
        } label: {
            Label("Inverser le sens", systemImage: "arrow.up.arrow.down")
        }
        .disabled(draw.pointCount < 2)
        Button {
            draw.loopToStart()
        } label: {
            Label("Boucler vers le départ", systemImage: "arrow.triangle.2.circlepath")
        }
        .disabled(draw.pointCount < 2)
        Button {
            draw.outAndBack()
        } label: {
            Label("Aller-retour", systemImage: "arrow.left.arrow.right")
        }
        .disabled(draw.pointCount < 2)
        Button {
            draw.startZone()
        } label: {
            Label("Éviter une zone", systemImage: "minus.circle")
        }
        Divider()
        Button {
            actions.chart()
        } label: {
            Label("Profil altimétrique", systemImage: "chart.xyaxis.line")
        }
        .disabled(draw.pointCount < 2)
        Button(role: .destructive) {
            draw.clear()
        } label: {
            Label("Tout effacer", systemImage: "trash")
        }
        .disabled(draw.pointCount == 0)
    }
}

// MARK: - Shared parts

/// « Suivre les chemins (BRouter) » and the profile chip (it opens the profiles while the route follows the ways).
private struct RoutingSwitchRow: View {
    @Environment(AppModel.self) private var model
    let onToggle: (Bool) -> Void
    let onProfile: () -> Void

    var body: some View {
        let draw = model.draw
        HStack(spacing: 8) {
            Toggle("Suivre les chemins (BRouter)", isOn: Binding(get: { draw.routing }, set: { onToggle($0) }))
                .labelsHidden()
                .tint(Crete.turquoise)
            Text("Suivre les chemins (BRouter)")
                .font(.subheadline)
                .foregroundStyle(Crete.onSurface)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            ProfileChip(profile: draw.profile, dropDown: draw.routing, onTap: onProfile)
        }
        .frame(minHeight: 44)
    }
}

/// The profile of the route on a chip: the outline and the corners of an assist chip.
private struct ProfileChip: View {
    let profile: String
    let dropDown: Bool
    let onTap: () -> Void

    var body: some View {
        let label = HStack(spacing: 4) {
            Image(systemName: RouteText.profileIcon(profile))
                .font(.system(size: 14, weight: .medium))
            Text(RouteText.profileLabel(profile))
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
            if dropDown {
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .bold))
            }
        }
        .foregroundStyle(Crete.petrol)
        .padding(.horizontal, 10)
        .frame(height: 32)
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Crete.outline, lineWidth: 1))
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        if dropDown {
            Button(action: onTap) { label }
                .buttonStyle(.plain)
                .accessibilityLabel("Profil : \(RouteText.profileLabel(profile))")
        } else {
            label
        }
    }
}

/// « Poser : Étape | Point d'intérêt » (P30.1): the choice is kept while the app runs.
private struct PoseToggle: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let draw = model.draw
        Picker("Poser", selection: Binding(get: { draw.poiMode }, set: { draw.setPoiMode($0) })) {
            Text("Étape").tag(false)
            Text("Point d'intérêt").tag(true)
        }
        .pickerStyle(.segmented)
    }
}

/// Undo, redo, « Outils ▾ » and « Enregistrer » (Android `ToolFooter`).
private struct DrawFooter<Tools: View>: View {
    @Environment(AppModel.self) private var model
    @ViewBuilder let tools: () -> Tools
    let toolsEnabled: Bool
    let onSave: () -> Void

    var body: some View {
        let draw = model.draw
        HStack(spacing: 4) {
            Button {
                draw.undo()
            } label: {
                Image(systemName: "arrow.uturn.backward")
                    .frame(width: 44, height: 40)
            }
            .disabled(!draw.canUndo)
            .accessibilityLabel("Annuler la dernière action")
            Button {
                draw.redo()
            } label: {
                Image(systemName: "arrow.uturn.forward")
                    .frame(width: 44, height: 40)
            }
            .disabled(!draw.canRedo)
            .accessibilityLabel("Rétablir")
            Menu {
                tools()
            } label: {
                HStack(spacing: 3) {
                    Text("Outils")
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                }
                .frame(minHeight: 40)
                .padding(.horizontal, 8)
            }
            .disabled(!toolsEnabled)
            Spacer(minLength: 0)
            Button(action: onSave) {
                Text("Enregistrer")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 18)
                    .frame(minHeight: 40)
                    .background(Crete.turquoise, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(draw.pointCount < 2)
            .opacity(draw.pointCount < 2 ? 0.4 : 1)
        }
        .font(.system(size: 16, weight: .medium))
        .foregroundStyle(Crete.petrol)
    }
}

/// A tile of the drawing: an icon above a short label; the main action filled, « Main levée » lit when on, « Retirer
/// la zone » in the colours of an error.
struct DrawTile: View {
    enum Style {
        case normal, primary, selected, danger
    }

    let systemImage: String
    let title: String
    var style: Style = .normal
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: 18, weight: .medium))
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.85)
            }
            .frame(maxWidth: .infinity, minHeight: 60)
            .padding(.vertical, 4)
            .padding(.horizontal, 2)
            .foregroundStyle(foreground)
            .background(background, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }

    private var foreground: Color {
        switch style {
        case .primary: Color.white
        case .selected: Crete.petrol
        case .danger: Crete.danger
        case .normal: Crete.petrol
        }
    }

    private var background: Color {
        switch style {
        case .primary: Crete.turquoise
        case .selected: Crete.pill
        case .danger: Color(hex: 0xFFDAD6)
        case .normal: Crete.tile
        }
    }
}

/// A chip to pick one value (radii, intervals, profiles).
struct RouteChip: View {
    let title: String
    var systemImage: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                } else if let icon = systemImage {
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .medium))
                }
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
            }
            .foregroundStyle(selected ? Crete.petrol : Crete.onSurface)
            .padding(.horizontal, 10)
            .frame(minHeight: 32)
            .background(selected ? Crete.pill : Color.clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? Color.clear : Crete.outline, lineWidth: 1))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Dialogs and sheets

/// Abandonner les modifications ? · the name of a new line · Simplifier · Suivre les chemins ? · Quel segment modifier ?
private struct DrawDialogs: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var askQuit: Bool
    @Binding var askName: Bool
    @Binding var nameText: String
    @Binding var askTolerance: Bool
    @Binding var routeAfterSimplify: Bool
    @Binding var askMany: Bool

    func body(content: Content) -> some View {
        content
            .background { quitHost }
            .background { nameHost }
            .background { toleranceHost }
            .background { manyHost }
            .background { segmentHost }
    }

    private var quitHost: some View {
        Color.clear.alert("Abandonner les modifications ?", isPresented: $askQuit) {
            Button("Annuler", role: .cancel) {}
            Button("Abandonner", role: .destructive) { model.draw.finish() }
        } message: {
            Text("Les changements non enregistrés seront perdus.")
        }
    }

    private var nameHost: some View {
        let title: String = model.draw.isRoute ? "Nom de l'itinéraire" : "Nom de la trace"
        return Color.clear.alert(title, isPresented: $askName) {
            TextField("Nom", text: $nameText)
            Button("Annuler", role: .cancel) {}
            Button("OK") {
                let name = nameText.trimmingCharacters(in: .whitespacesAndNewlines)
                model.draw.save(name: name.isEmpty ? model.draw.defaultName() : name)
            }
        }
    }

    private var toleranceHost: some View {
        Color.clear.confirmationDialog("Simplifier", isPresented: $askTolerance, titleVisibility: .visible) {
            ForEach(DrawController.simplifyTolerances, id: \.self) { meters in
                Button(MapText.distance(meters, model.settings)) {
                    model.draw.simplify(meters)
                    if routeAfterSimplify {
                        routeAfterSimplify = false
                        model.draw.routeAllNow()
                    }
                }
            }
            Button("Annuler", role: .cancel) {
                routeAfterSimplify = false
            }
        } message: {
            Text("Écart maximal toléré avec le tracé d'origine :")
        }
    }

    private var manyHost: some View {
        let count = model.draw.pointCount
        return Color.clear.alert("Suivre les chemins ?", isPresented: $askMany) {
            Button("Recalculer quand même") { model.draw.routeAllNow() }
            Button("Simplifier…") {
                routeAfterSimplify = true
                askTolerance = true
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Cet itinéraire a \(count) points. Chacun deviendrait une étape à recalculer. Le simplifier d'abord ?")
        }
    }

    private var segmentHost: some View {
        let sizes = model.draw.segmentChoices ?? []
        let shown = Binding(
            get: { model.draw.segmentChoices != nil },
            set: { open in
                if !open, model.draw.segmentChoices != nil {
                    model.draw.finish()
                }
            }
        )
        return Color.clear.confirmationDialog("Quel segment modifier ?", isPresented: shown, titleVisibility: .visible) {
            ForEach(Array(sizes.enumerated()), id: \.offset) { item in
                Button("Segment \(item.offset + 1) — nombre de points : \(item.element)") {
                    model.draw.pickSegment(item.offset)
                }
            }
            Button("Annuler", role: .cancel) {
                model.draw.finish()
            }
        }
    }
}

/// The profiles, « Quel point ? » / « Nom de l'étape » and the elevation profile.
private struct DrawSheets: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var profileChoice: RouteProfileChoice?
    @Binding var chartOpen: Bool
    @Binding var place: RoutePlaceTarget?

    func body(content: Content) -> some View {
        content
            .background { profileHost }
            .background { placeHost }
            .background { chartHost }
    }

    private var profileHost: some View {
        Color.clear.sheet(item: $profileChoice) { choice in
            RoutingProfileSheet(leg: choice.leg)
                .environment(model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    private var placeHost: some View {
        Color.clear.sheet(item: $place) { target in
            RoutePlaceSheetHost(target: target)
                .environment(model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    private var chartHost: some View {
        Color.clear.sheet(isPresented: $chartOpen, onDismiss: { model.draw.setCursor(nil) }) {
            RouteProfileSheet()
                .environment(model)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
                .presentationBackgroundInteraction(.enabled(upThrough: .medium))
        }
    }
}
