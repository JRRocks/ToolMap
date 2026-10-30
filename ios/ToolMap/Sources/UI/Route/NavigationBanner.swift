import SwiftUI
import ToolMapCore

/// What the routing draws over the map in SwiftUI (Android `TargetHud` G8, `FollowHud` G9, `RouteStatsCard`,
/// `FreehandLayer`, `ZoneSizeHandle`): at the top, under the information pills, the figures of the route being computed,
/// the banner « Vers <nom> » and the following of a track with its alerts; on the map, the stroke of « Main levée » and
/// the handle that resizes the zone under the crosshair; and the start sheet « Aller vers <nom> ».
struct NavigationBanner: View {
    var body: some View {
        ZStack {
            DrawMapOverlay()
            RouteTopStack()
            GoToSheetHost()
        }
    }
}

// MARK: - Top of the map

/// The banners, one under the other, below the information pills.
private struct RouteTopStack: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let draw = model.draw
        let routing = model.routing
        let statsShown = draw.active && draw.routed && (draw.plan?.stops.count ?? 0) >= 2
        let targetShown = routing.target != nil && !routing.previewing
        let followShown = routing.following != nil || routing.alertBanner != nil
        GeometryReader { geo in
            VStack(spacing: 6) {
                if statsShown {
                    RouteStatsPill()
                }
                if targetShown {
                    TargetHud()
                }
                if followShown {
                    FollowHud()
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, topInset(geo.frame(in: .global).minY))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
    }

    /// Under the pills when they are shown.
    private func topInset(_ origin: CGFloat) -> CGFloat {
        guard model.showInfo else { return 8 }
        let bottom = model.grid.pillsBottom
        return max(8, bottom - origin + 8)
    }
}

/// The figures of the route that follows the ways (variant A of P19): `12,4 km · ↑ 830 m · ↓ 610 m · 4 h 10`, then
/// the stops, the points of interest, the zones avoided and the legs being computed.
private struct RouteStatsPill: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let draw = model.draw
        let settings = model.settings
        let s = draw.summary
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 0) {
                Text(MapText.distance(s.lengthMeters, settings))
                    .font(.subheadline.weight(.bold))
                Text(rest(s, settings))
                    .font(.subheadline)
            }
            .lineLimit(1)
            .foregroundStyle(Crete.onSurface)
            Text(sub(draw))
                .font(.caption)
                .foregroundStyle(Crete.onSurfaceVariant)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
    }

    private func rest(_ s: RouteSummary, _ settings: AppSettings) -> String {
        var parts: [String] = []
        if s.hasElevation {
            parts.append("↑ \(MapText.elevation(s.ascentMeters, settings))")
            parts.append("↓ \(MapText.elevation(s.descentMeters, settings))")
        }
        if let millis = s.durationMillis {
            let prefix = s.durationIsMinimum ? "≥ " : ""
            parts.append("\(prefix)\(TrackFormat.duration(millis))")
        }
        if parts.isEmpty { return "" }
        let joined = parts.joined(separator: " · ")
        return " · \(joined)"
    }

    private func sub(_ draw: DrawController) -> String {
        guard let current = draw.plan else { return "" }
        var parts: [String] = [RouteText.count(current.stops.count, "étape", "étapes")]
        if !current.pois.isEmpty {
            parts.append(RouteText.count(current.pois.count, "point d'intérêt", "points d'intérêt"))
        }
        if !current.zones.isEmpty {
            parts.append(RouteText.count(current.zones.count, "zone évitée", "zones évitées"))
        }
        if draw.summary.pendingLegs > 0 {
            parts.append(RouteText.count(draw.summary.pendingLegs, "tronçon en calcul", "tronçons en calcul"))
        }
        if draw.atStart {
            parts.append("Ajout au début")
        }
        return parts.joined(separator: " · ")
    }
}

/// « Vers Refuge du Pré » (P30.3, variants A and C): an arrow along the way to follow (or to the target in a straight
/// line), « 3,8 km par les chemins · ↑ 92 m · 1 h 15 », « arrivée 15:20 · recalcul dans 1 min 40 » with the profile,
/// and « Arrêter »; under it, in red, the walker off the way while a new one is computed.
private struct TargetHud: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        VStack(spacing: 4) {
            HStack(spacing: 10) {
                if let heading = arrowHeading(routing), !routing.arrived {
                    NavArrow()
                        .fill(Crete.turquoise)
                        .frame(width: 30, height: 30)
                        .rotationEffect(.degrees(heading - model.direction))
                        .accessibilityHidden(true)
                }
                TargetHudLines()
                Spacer(minLength: 0)
                Button("Arrêter") {
                    routing.clearTarget()
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.turquoise)
                .frame(minWidth: 44, minHeight: 44)
            }
            .padding(.leading, 12)
            .padding(.trailing, 6)
            .padding(.vertical, 6)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
            if routing.rerouting, let off = routing.rerouteMeters {
                RedPill(text: rerouteText(routing, off))
            }
        }
    }

    private func arrowHeading(_ routing: RoutingController) -> Double? {
        if routing.goRouted, let p = routing.progress {
            return p.headingDegrees
        }
        return routing.guidance?.bearingDegrees
    }

    private func rerouteText(_ routing: RoutingController, _ off: Double) -> String {
        let distance = MapText.distance(off, model.settings)
        if routing.reroutedAtMillis == nil {
            return "Hors du chemin (\(distance)) : calcul d'un nouveau chemin…"
        }
        return "Hors du chemin (\(distance)) : nouveau chemin calculé"
    }
}

/// The lines of the banner: its name, what remains, the state of the way and the profile.
private struct TargetHudLines: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        let name = routing.target?.name ?? RouteText.unnamedTarget
        VStack(alignment: .leading, spacing: 1) {
            Text("Vers \(name)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.onSurface)
                .lineLimit(1)
            Text(GoToTexts.mainLine(routing, model.settings))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(Crete.onSurface)
            if routing.goRouted, !routing.arrived, routing.fix != nil {
                HStack(spacing: 4) {
                    Text(GoToTexts.statusLine(routing))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .lineLimit(2)
                    GoToProfileMenu()
                }
            }
        }
    }
}

/// The icon of the profile, which opens Randonnée · Vélo · VTT · Voiture.
private struct GoToProfileMenu: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        Menu {
            ForEach(GoToRules.profiles, id: \.self) { profile in
                Button {
                    routing.choose(routed: true, profile: profile, intervalMinutes: routing.goIntervalMinutes)
                } label: {
                    Label(RouteText.profileLabel(profile), systemImage: RouteText.profileIcon(profile))
                }
            }
        } label: {
            Image(systemName: RouteText.profileIcon(routing.goProfile))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Crete.turquoise)
                .frame(width: 32, height: 32)
        }
        .accessibilityLabel("Profil : \(RouteText.profileLabel(routing.goProfile))")
    }
}

/// The texts of « Aller vers un point » (Android `mainLine`, `statusLine`, `arrivalOf`).
enum GoToTexts {
    /// « 3,8 km par les chemins · ↑ 92 m · 1 h 15 », else « 2,90 km · cap 44° NE », « Arrivé »…
    static func mainLine(_ routing: RoutingController, _ settings: AppSettings) -> String {
        if routing.arrived { return "Arrivé" }
        if routing.goRouted, let p = routing.progress {
            var parts = ["\(MapText.distance(p.remainingMeters, settings)) par les chemins"]
            if let ascent = p.remainingAscentMeters {
                parts.append("↑ \(MapText.elevation(ascent, settings))")
            }
            if let millis = p.remainingMillis {
                parts.append(TrackFormat.duration(millis))
            }
            return parts.joined(separator: " · ")
        }
        if let line = routing.guidance {
            let distance = MapText.distance(line.distanceMeters, settings)
            return "\(distance) · cap \(RouteText.bearing(line.bearingDegrees, settings))"
        }
        if routing.gpsFailed {
            return "Le GPS n'a pas pu démarrer : autorisez la position pour voir la distance."
        }
        return "En attente de la position GPS…"
    }

    /// « arrivée 15:20 · recalcul dans 1 min 40 », « Pas de données BRouter ici · nouvel essai dans 2 min »…
    static func statusLine(_ routing: RoutingController) -> String {
        var parts: [String] = []
        if let arrival = arrival(routing) {
            parts.append("arrivée \(arrival)")
        }
        var next: Int64?
        if let at = routing.nextAtMillis {
            next = max(at - routing.nowMillis, 0)
        }
        if routing.computingWay, routing.way == nil {
            parts.append("calcul du chemin…")
        } else if let error = routing.failure {
            parts.append(RouteText.goToFailure(error, offline: routing.failureOffline))
            if let wait = next {
                parts.append("nouvel essai dans \(GoTo.countdown(wait))")
            }
        } else if let answered = routing.way?.computedAtMillis, !routing.computingWay,
                  routing.nowMillis - answered < RoutingController.justNowMillis
        {
            parts.append("recalculé à l'instant")
        } else if let wait = next {
            parts.append("recalcul dans \(GoTo.countdown(wait))")
        }
        return parts.joined(separator: " · ")
    }

    /// « 15:20 »: now and the time left along the way; nil while it cannot be told.
    static func arrival(_ routing: RoutingController) -> String? {
        guard routing.goRouted, !routing.arrived, let left = routing.progress?.remainingMillis else { return nil }
        return RouteText.clock(routing.nowMillis + left)
    }
}

/// The arrow of the banner, pointing up before it is turned.
private struct NavArrow: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let w = rect.width
        let h = rect.height
        path.move(to: CGPoint(x: rect.minX + w / 2, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + w * 0.85, y: rect.minY + h * 0.85))
        path.addLine(to: CGPoint(x: rect.minX + w / 2, y: rect.minY + h * 0.65))
        path.addLine(to: CGPoint(x: rect.minX + w * 0.15, y: rect.minY + h * 0.85))
        path.closeSubpath()
        return path
    }
}

/// A red pill of the map: an urgent message.
private struct RedPill: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(Color(hex: 0x410002))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(hex: 0xFFDAD6), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
            .accessibilityAddTraits(.updatesFrequently)
    }
}

/// The followed line: its name, the progress, what remains, how far off, « Sens inverse » and « Arrêter »; above it
/// the last alert until « Compris ».
private struct FollowHud: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        VStack(spacing: 4) {
            if let event = routing.alertBanner {
                AlertPill(event: event)
            }
            if routing.following != nil, routing.gpsFailed {
                RedPill(text: "Le GPS n'a pas pu être démarré : aucune alerte ne peut être donnée.")
            }
            if routing.following != nil {
                FollowPill()
            }
        }
    }
}

/// The last alert, in its own colour, with « Compris ».
private struct AlertPill: View {
    @Environment(AppModel.self) private var model
    let event: AlertEvent

    var body: some View {
        let text = RouteText.alert(event, model.settings)
        let urgent = isOffTrack
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(text.title)
                    .font(.subheadline.weight(.semibold))
                Text(text.body)
                    .font(.caption)
            }
            .foregroundStyle(urgent ? Color(hex: 0x410002) : Crete.onSurface)
            Spacer(minLength: 0)
            Button("Compris") {
                model.routing.dismissAlert()
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(urgent ? Crete.danger : Crete.petrol)
            .frame(minHeight: 44)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .background(urgent ? Color(hex: 0xFFDAD6) : Crete.pill.opacity(0.9), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
    }

    private var isOffTrack: Bool {
        if case .offTrack = event { return true }
        return false
    }
}

/// « Suivi : Tour du Granier », the bar of the progress, « 1,20 km parcourus · 3,40 km restants », « Écart : 12 m ».
private struct FollowPill: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        let settings = model.settings
        VStack(alignment: .leading, spacing: 4) {
            Text("Suivi : \(routing.followName ?? RouteText.unnamed)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.onSurface)
                .lineLimit(1)
            if let status = routing.followStatus {
                ProgressView(value: status.fraction)
                    .tint(Crete.turquoise)
                Text(progressText(status, settings))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Crete.onSurface)
                Text(offText(status, settings))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(status.offTrack ? Crete.danger : Crete.onSurface)
            } else {
                Text("En attente d'une position…")
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            HStack(spacing: 16) {
                Button("Sens inverse") { routing.reverseFollow() }
                Button("Arrêter") { routing.stopFollowing() }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Crete.turquoise)
            .frame(minHeight: 36)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
    }

    private func progressText(_ status: FollowStatus, _ settings: AppSettings) -> String {
        if status.arrived { return "Arrivé" }
        let done = MapText.distance(status.alongMeters, settings)
        let left = MapText.distance(status.remainingMeters, settings)
        return "\(done) parcourus · \(left) restants"
    }

    private func offText(_ status: FollowStatus, _ settings: AppSettings) -> String {
        let off = MapText.distance(status.offMeters, settings)
        return status.offTrack ? "Hors de la trace : \(off)" : "Écart : \(off)"
    }
}

// MARK: - On the map

/// The stroke of « Main levée » and the handle of the zone under the crosshair, in the coordinates of the map view.
private struct DrawMapOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let draw = model.draw
        let inHand = draw.taken != nil || draw.zoneRadius != nil || draw.movingPoi != nil
        ZStack {
            if draw.active, draw.freehand {
                FreehandCapture()
            }
            if draw.active, !inHand, let index = draw.hoveredZone, let zone = draw.zone(index) {
                ZoneSizeHandle(index: index, zone: zone)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
    }
}

/// A transparent layer that turns a finger stroke into a line: the stroke is drawn as it goes and handed to the
/// drawing when the finger lifts. While it is shown the map does not move.
private struct FreehandCapture: View {
    @Environment(AppModel.self) private var model
    @State private var stroke: [CGPoint] = []

    var body: some View {
        Canvas { context, _ in
            guard stroke.count > 1 else { return }
            var path = Path()
            path.addLines(stroke)
            context.stroke(path, with: .color(Color(hex: 0xFF6F00)), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
        }
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged { value in
                    stroke.append(value.location)
                }
                .onEnded { _ in
                    let points = stroke.compactMap { model.mapProxy.position($0) }
                    stroke = []
                    let line = points.map { TrackPoint(lat: $0.lat, lon: $0.lon) }
                    model.draw.addStroke(line)
                }
        )
        .accessibilityLabel("Main levée")
    }
}

/// The handle that changes the size of the zone under the crosshair (P30.4, variant C): a white disc ringed with red
/// due east of the centre, joined to it by a dashed line, the size under it. Pulled, the size follows (50 m to 2 km in
/// steps of 50 m) and the circle is drawn at that size; let go, the zone takes it.
private struct ZoneSizeHandle: View {
    @Environment(AppModel.self) private var model
    let index: Int
    let zone: AvoidZone
    @State private var live: Int?

    var body: some View {
        // Read so that the handle is placed again when the map moves.
        let _ = model.center
        let _ = model.zoom
        let radius = live ?? zone.radiusMeters
        let east = zone.east(radius)
        let centre = model.mapProxy.point(LonLat(lon: zone.lon, lat: zone.lat))
        let edge = model.mapProxy.point(LonLat(lon: east.lon, lat: east.lat))
        if let from = centre, let at = edge {
            ZStack {
                Path { path in
                    path.move(to: from)
                    path.addLine(to: at)
                }
                .stroke(Crete.danger, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                .allowsHitTesting(false)
                ZoneHandleDot()
                    .position(at)
                    .gesture(drag)
                    .accessibilityElement()
                    .accessibilityLabel("Poignée de taille de la zone")
                    .accessibilityValue(RouteText.size(radius, model.settings))
                    .accessibilityAdjustableAction { direction in
                        adjust(direction)
                    }
                Text(RouteText.size(radius, model.settings))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Crete.petrol, in: Capsule())
                    .position(x: at.x, y: at.y + 27)
                    .allowsHitTesting(false)
            }
        }
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 1, coordinateSpace: .global)
            .onChanged { value in
                guard let at = model.mapProxy.position(value.location) else { return }
                let meters = Geodesy.distance(zone.lat, zone.lon, at.lat, at.lon)
                let r = AvoidZone.resized(meters)
                if r != live {
                    live = r
                    model.draw.previewZoneSize(index, r)
                }
            }
            .onEnded { _ in
                let r = live ?? zone.radiusMeters
                live = nil
                model.draw.resizeZone(index, r)
            }
    }

    /// VoiceOver cannot pull the handle: a step of 50 m either way.
    private func adjust(_ direction: AccessibilityAdjustmentDirection) {
        let step = Double(AvoidZone.resizeStep)
        switch direction {
        case .increment:
            model.draw.resizeZone(index, AvoidZone.resized(Double(zone.radiusMeters) + step))
        case .decrement:
            model.draw.resizeZone(index, AvoidZone.resized(Double(zone.radiusMeters) - step))
        @unknown default:
            break
        }
    }
}

/// The dot of the handle, 22 points wide in a 48-point target.
private struct ZoneHandleDot: View {
    var body: some View {
        Circle()
            .fill(Color.white)
            .overlay(Circle().stroke(Crete.danger, lineWidth: 3))
            .frame(width: 22, height: 22)
            .frame(width: 48, height: 48)
            .contentShape(Circle())
    }
}

// MARK: - Start sheet host

/// The start sheet « Aller vers <nom> », shown by `RoutingController.askTarget`.
private struct GoToSheetHost: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        let shown = Binding(
            get: { routing.startSheetShown },
            set: { open in
                if !open {
                    routing.startSheetClosed()
                }
            }
        )
        Color.clear
            .frame(width: 1, height: 1)
            .allowsHitTesting(false)
            .sheet(isPresented: shown) {
                GoToStartSheet()
                    .environment(model)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
    }
}
