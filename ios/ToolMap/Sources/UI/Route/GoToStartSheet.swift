import SwiftUI
import ToolMapCore

/// « Aller vers Refuge du Pré » (Android `GoToStartSheet`, P30.3 variant C), opened by « Aller vers ce point » of a
/// waypoint (`RoutingController.askTarget`): what the way weighs (« 3,8 km · ↑ 92 m · 1 h 15 · arrivée 15:20 ») as soon
/// as BRouter gave it, the way drawn behind; « Par les chemins | À vol d'oiseau », the profile, « Recalculer toutes
/// les 1 · 2 · 5 · 10 min », then « Partir ». The choices are kept for the next time.
struct GoToStartSheet: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                GoToSheetHeader()
                if routing.goRouted, let error = routing.failure {
                    Text(RouteText.goToFailure(error, offline: routing.failureOffline))
                        .font(.subheadline)
                        .foregroundStyle(Crete.danger)
                }
                Picker(
                    "Chemin",
                    selection: Binding(
                        get: { routing.goRouted },
                        set: { routed in
                            routing.choose(routed: routed, profile: routing.goProfile, intervalMinutes: routing.goIntervalMinutes)
                        }
                    )
                ) {
                    Text("Par les chemins").tag(true)
                    Text("À vol d'oiseau").tag(false)
                }
                .pickerStyle(.segmented)
                if routing.goRouted {
                    GoToProfileChips()
                    GoToIntervalChips()
                }
                Button {
                    routing.go()
                } label: {
                    Label("Partir", systemImage: "location.north.fill")
                        .font(.headline)
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Crete.turquoise, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 24)
            .padding(.bottom, 12)
        }
    }
}

/// The icon of the target, « Aller vers <nom> » and the figures of the way.
private struct GoToSheetHeader: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        let target = routing.target
        HStack(spacing: 12) {
            RouteIconBadge(icon: WaypointSymbols.iconOf(target?.sym), size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text("Aller vers \(target?.name ?? RouteText.unnamedTarget)")
                    .font(.headline)
                    .foregroundStyle(Crete.onSurface)
                    .lineLimit(2)
                Text(figures(routing))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
    }

    /// « 3,8 km · ↑ 92 m · 1 h 15 · arrivée 15:20 », « Calcul du chemin… », or the straight line.
    private func figures(_ routing: RoutingController) -> String {
        let settings = model.settings
        if routing.goRouted, let p = routing.progress, !routing.arrived {
            var parts = [MapText.distance(p.remainingMeters, settings)]
            if let ascent = p.remainingAscentMeters {
                parts.append("↑ \(MapText.elevation(ascent, settings))")
            }
            if let millis = p.remainingMillis {
                parts.append(TrackFormat.duration(millis))
            }
            if let arrival = GoToTexts.arrival(routing) {
                parts.append("arrivée \(arrival)")
            }
            return parts.joined(separator: " · ")
        }
        if routing.goRouted, routing.computingWay, routing.fix != nil {
            return "Calcul du chemin…"
        }
        return GoToTexts.mainLine(routing, settings)
    }
}

/// Randonnée · Vélo · VTT · Voiture.
private struct GoToProfileChips: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(GoToRules.profiles, id: \.self) { profile in
                    RouteChip(
                        title: RouteText.profileLabel(profile),
                        systemImage: RouteText.profileIcon(profile),
                        selected: profile == routing.goProfile
                    ) {
                        routing.choose(routed: true, profile: profile, intervalMinutes: routing.goIntervalMinutes)
                    }
                }
            }
        }
    }
}

/// « Recalculer toutes les 1 · 2 · 5 · 10 min ».
private struct GoToIntervalChips: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let routing = model.routing
        let offered = GoToRules.intervalMinutesOffered
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Text("Recalculer toutes les")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                ForEach(offered.indices, id: \.self) { i in
                    let minutes = offered[i]
                    RouteChip(
                        title: i == offered.count - 1 ? "\(minutes) min" : "\(minutes)",
                        selected: minutes == routing.goIntervalMinutes
                    ) {
                        routing.choose(routed: true, profile: routing.goProfile, intervalMinutes: minutes)
                    }
                }
            }
        }
    }
}

/// « Alerte de proximité » of a waypoint (Android `WatchDialog`): the radius of the settings or one of 25 m to 1 km,
/// « Activer », and « Désactiver » when it is watched. For the waypoint's card: `ProximityAlertSheet(waypointId:name:)`.
struct ProximityAlertSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let waypointId: Int64
    let name: String?
    @State private var choice: Int?
    @State private var ready = false

    static let radii = [25, 50, 100, 200, 500, 1000]

    var body: some View {
        let routing = model.routing
        let watched = routing.watching(waypointId) != nil
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Prévenir en arrivant à moins de :")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                ProximityRadii(choice: $choice)
                Text("L'alerte reste active écran éteint tant que ToolMap suit votre position ; « Désactiver » l'enlève.")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                HStack(spacing: 12) {
                    if watched {
                        Button("Désactiver", role: .destructive) {
                            routing.unwatch(waypointId: waypointId)
                            dismiss()
                        }
                    }
                    Spacer(minLength: 0)
                    Button("Annuler") { dismiss() }
                    Button {
                        routing.watch(waypointId: waypointId, radiusMeters: choice)
                        dismiss()
                    } label: {
                        Text("Activer")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 16)
                            .frame(minHeight: 36)
                            .background(Crete.turquoise, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                .font(.subheadline)
                .foregroundStyle(Crete.petrol)
                Spacer(minLength: 0)
            }
            .padding(16)
            .navigationTitle(trimmed.isEmpty ? "Alerte de proximité" : trimmed)
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear {
            if !ready {
                ready = true
                choice = routing.watching(waypointId)?.radiusMeters
            }
        }
    }
}

/// « Rayon des réglages (100 m) », 25 m · 50 m · 100 m · 200 m · 500 m · 1 km.
private struct ProximityRadii: View {
    @Environment(AppModel.self) private var model
    @Binding var choice: Int?

    var body: some View {
        let settings = model.settings
        let defaultText = MapText.distance(Double(settings.alerts.proximityRadiusMeters), settings)
        let columns = [GridItem(.adaptive(minimum: 84), spacing: 8)]
        VStack(alignment: .leading, spacing: 4) {
            RouteChip(title: "Rayon des réglages (\(defaultText))", selected: choice == nil) {
                choice = nil
            }
            LazyVGrid(columns: columns, alignment: .leading, spacing: 4) {
                ForEach(ProximityAlertSheet.radii, id: \.self) { radius in
                    RouteChip(title: MapText.distance(Double(radius), settings), selected: choice == radius) {
                        choice = radius
                    }
                }
            }
        }
    }
}
