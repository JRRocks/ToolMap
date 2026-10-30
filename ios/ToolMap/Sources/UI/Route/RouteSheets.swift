import SwiftUI
import ToolMapCore

// MARK: - Étapes (N)

/// The list of stops (Android `StopsSheet`, screen 3 of `p19-routing.html`), in place of the tiles: one line per stop
/// with its pin, its name or « Passage », its distance from the start and what differs on the next leg; a touch
/// centres the map on it and selects it, ⋮ opens its menu. Under them the points of interest (P30.1).
struct RouteStopsList: View {
    @Environment(AppModel.self) private var model
    let plan: RoutePlan
    let onClose: () -> Void
    let actions: DrawPanelActions
    @State private var askStart: Int?

    var body: some View {
        let distances = plan.stopDistances()
        VStack(alignment: .leading, spacing: 4) {
            Button(action: onClose) {
                HStack(spacing: 8) {
                    Image(systemName: "list.number")
                    Text("Étapes (\(plan.stops.count))")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(Crete.petrol)
                .frame(minHeight: 40)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(plan.stops.indices, id: \.self) { i in
                        let along = distances.indices.contains(i) ? distances[i] : 0
                        Divider()
                        StopRow(plan: plan, index: i, distance: along, actions: actions) {
                            if model.draw.stopsDroppedBy(i) > 0 {
                                askStart = i
                            } else {
                                model.draw.startFrom(i)
                            }
                        }
                    }
                    if !plan.pois.isEmpty {
                        Divider()
                        Text("Points d'intérêt (\(plan.pois.count))")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Crete.onSurface)
                            .padding(.top, 10)
                            .padding(.bottom, 6)
                        ForEach(plan.pois.indices, id: \.self) { k in
                            Divider()
                            PoiRow(plan: plan, index: k, actions: actions)
                        }
                    }
                }
            }
            .frame(maxHeight: 300)
        }
        .alert("Partir d'ici", isPresented: Binding(get: { askStart != nil }, set: { if !$0 { askStart = nil } })) {
            Button("Annuler", role: .cancel) { askStart = nil }
            Button("Supprimer", role: .destructive) {
                if let i = askStart {
                    model.draw.startFrom(i)
                }
                askStart = nil
            }
        } message: {
            Text(startQuestion)
        }
    }

    private var startQuestion: String {
        let n = askStart.map { model.draw.stopsDroppedBy($0) } ?? 0
        return n == 1 ? "Supprimer l'étape avant ?" : "Supprimer les \(n) étapes avant ?"
    }
}

/// One stop of the list.
private struct StopRow: View {
    @Environment(AppModel.self) private var model
    let plan: RoutePlan
    let index: Int
    let distance: Double
    let actions: DrawPanelActions
    let onStartHere: () -> Void

    var body: some View {
        let draw = model.draw
        let stop = plan.stops[index]
        let selected = draw.selected == index
        HStack(spacing: 10) {
            StopPin(plan: plan, index: index)
            VStack(alignment: .leading, spacing: 1) {
                Text(title(stop))
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                    .lineLimit(1)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            Spacer(minLength: 0)
            StopMenu(plan: plan, index: index, actions: actions, onStartHere: onStartHere)
        }
        .padding(.leading, 4)
        .background(selected ? Crete.pill.opacity(0.45) : Color.clear)
        .contentShape(Rectangle())
        .onTapGesture {
            draw.selectStop(index)
            model.show(LonLat(lon: stop.lon, lat: stop.lat))
        }
    }

    private func title(_ stop: RouteStop) -> String {
        if stop.kind == .named, let name = stop.name {
            return name
        }
        return "Passage"
    }

    /// « Départ », « 2,10 km », « Arrivée · 3,78 km », then what differs on the next leg.
    private var detail: String {
        let settings = model.settings
        let last = plan.stops.count - 1
        let text = MapText.distance(distance, settings)
        let place: String
        if index == 0 {
            place = "Départ"
        } else if index == last {
            place = "Arrivée · \(text)"
        } else {
            place = text
        }
        guard plan.legs.indices.contains(index) else { return place }
        let next = plan.legs[index]
        if next.isByHand {
            return "\(place) · tronçon suivant à la main"
        }
        if next.profile != model.draw.profile {
            return "\(place) · \(RouteText.profileLabel(next.profile))"
        }
        return place
    }
}

/// The pin of a stop: A in green, the last letter in red, a white ring for a passage, the icon if named.
private struct StopPin: View {
    let plan: RoutePlan
    let index: Int

    var body: some View {
        let last = plan.stops.count - 1
        let stop = plan.stops[index]
        if index == 0 || index == last {
            let color: Int32 = index == 0 ? RouteMarkers.startColor : RouteMarkers.endColor
            Text(RouteMarkers.letter(index))
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(Color.white)
                .frame(width: 22, height: 22)
                .background(Color(routeArgb: color), in: Circle())
                .overlay(Circle().stroke(Color.white, lineWidth: 2))
        } else if stop.kind == .named {
            RouteIconBadge(icon: WaypointSymbols.iconOf(stop.sym), size: 22)
        } else {
            Circle()
                .fill(Color.white)
                .overlay(Circle().stroke(Color(hex: 0xF57C00), lineWidth: 3))
                .frame(width: 14, height: 14)
                .frame(width: 22)
        }
    }
}

/// The ⋮ menu of a stop.
private struct StopMenu: View {
    @Environment(AppModel.self) private var model
    let plan: RoutePlan
    let index: Int
    let actions: DrawPanelActions
    let onStartHere: () -> Void

    var body: some View {
        let draw = model.draw
        let stop = plan.stops[index]
        let named = stop.kind == .named
        Menu {
            Button {
                actions.place(RoutePlaceTarget(kind: .stop, index: index, position: LonLat(lon: stop.lon, lat: stop.lat)))
            } label: {
                Label(named ? "Modifier…" : "Nommer…", systemImage: "pencil")
            }
            if named {
                Button("Rendre discrète") { draw.makeDiscreet(index) }
            }
            Button {
                draw.saveStopAsWaypoint(index)
            } label: {
                Label("Enregistrer comme waypoint", systemImage: "mappin.and.ellipse")
            }
            if plan.legs.indices.contains(index) {
                Divider()
                StopLegItems(leg: plan.legs[index], index: index, actions: actions)
            }
            Divider()
            if index > 0 {
                Button(action: onStartHere) {
                    Label("Partir d'ici", systemImage: "flag")
                }
            }
            Button(role: .destructive) {
                draw.deleteStop(index)
            } label: {
                Label("Supprimer", systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis")
                .rotationEffect(.degrees(90))
                .foregroundStyle(Crete.onSurfaceVariant)
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel("Actions de l'étape")
    }
}

/// The items of the leg after a stop: its profile, by hand or routed, « Recalculer ».
private struct StopLegItems: View {
    @Environment(AppModel.self) private var model
    let leg: RouteLeg
    let index: Int
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        Button {
            actions.legProfile(index)
        } label: {
            Label("Profil du tronçon suivant", systemImage: "figure.hiking")
        }
        if leg.mode == .straight {
            Button {
                draw.setLegMode(index, .routed)
            } label: {
                Label("Tronçon suivant routé", systemImage: "arrow.triangle.branch")
            }
        } else {
            Button {
                draw.setLegMode(index, .straight)
            } label: {
                Label("Tronçon suivant en ligne droite", systemImage: "line.diagonal")
            }
        }
        if leg.mode == .routed, leg.state == .noData || leg.state == .failed {
            Button {
                draw.recompute(index)
            } label: {
                Label("Recalculer", systemImage: "arrow.clockwise")
            }
        }
    }
}

/// One point of interest of the list: its icon, its name, « 2,10 km · à 60 m du tracé ».
private struct PoiRow: View {
    @Environment(AppModel.self) private var model
    let plan: RoutePlan
    let index: Int
    let actions: DrawPanelActions

    var body: some View {
        let draw = model.draw
        let poi = plan.pois[index]
        HStack(spacing: 10) {
            RouteIconBadge(icon: WaypointSymbols.iconOf(poi.sym), size: 24)
            VStack(alignment: .leading, spacing: 1) {
                Text(poi.name ?? "Point d'intérêt")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                    .lineLimit(1)
                if let text = detail {
                    Text(text)
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            Spacer(minLength: 0)
            Menu {
                Button {
                    actions.place(RoutePlaceTarget(kind: .poi, index: index, position: LonLat(lon: poi.lon, lat: poi.lat)))
                } label: {
                    Label("Modifier…", systemImage: "pencil")
                }
                Button {
                    draw.savePoiAsWaypoint(index)
                } label: {
                    Label("Enregistrer comme waypoint", systemImage: "mappin.and.ellipse")
                }
                Divider()
                Button(role: .destructive) {
                    draw.removePoi(index)
                } label: {
                    Label("Supprimer", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .rotationEffect(.degrees(90))
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 44, height: 44)
            }
        }
        .padding(.leading, 4)
        .contentShape(Rectangle())
        .onTapGesture {
            model.show(LonLat(lon: poi.lon, lat: poi.lat))
        }
    }

    private var detail: String? {
        guard let place = plan.placeOf(index) else { return nil }
        let settings = model.settings
        let along = MapText.distance(place.alongMeters, settings)
        if place.offMeters < DrawController.onRouteMeters {
            return "\(along) · sur le tracé"
        }
        return "\(along) · à \(MapText.distance(place.offMeters, settings)) du tracé"
    }
}

// MARK: - Profil

/// The profiles of the route (Android `ProfileDialog`): a touch picks one, « Autre… » types a profile of one's own,
/// « Recalculer tout l'itinéraire avec ce profil » (on: every leg takes it; off: only the new ones). For the leg after
/// stop `leg`, only that leg changes.
struct RoutingProfileSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let leg: Int?
    @State private var allLegs = true
    @State private var typing = false
    @State private var typed = ""

    var body: some View {
        let draw = model.draw
        let current = currentProfile
        NavigationStack {
            List {
                Section {
                    ForEach(draw.profiles, id: \.self) { profile in
                        Button {
                            pick(profile)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: RouteText.profileIcon(profile))
                                    .frame(width: 24)
                                Text(RouteText.profileLabel(profile))
                                    .foregroundStyle(Crete.onSurface)
                                Spacer()
                                if profile == current {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Crete.turquoise)
                                }
                            }
                        }
                    }
                    Button {
                        typed = ""
                        typing = true
                    } label: {
                        HStack(spacing: 4) {
                            Text("Autre…")
                                .foregroundStyle(Crete.onSurface)
                            Text("(profil perso de BRouter)")
                                .foregroundStyle(Crete.onSurfaceVariant)
                        }
                    }
                }
                if leg == nil {
                    Section {
                        Toggle("Recalculer tout l'itinéraire avec ce profil", isOn: $allLegs)
                            .tint(Crete.turquoise)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Profil")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
            }
            .alert("Profil de BRouter", isPresented: $typing) {
                TextField("Nom du profil (fichier .brf de BRouter)", text: $typed)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Annuler", role: .cancel) {}
                Button("OK") {
                    var name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
                    if name.hasSuffix(".brf") {
                        name = String(name.dropLast(4))
                    }
                    if !name.isEmpty {
                        pick(name)
                    }
                }
            }
        }
    }

    private var currentProfile: String {
        let draw = model.draw
        if let index = leg, let current = draw.plan, current.legs.indices.contains(index) {
            return current.legs[index].profile
        }
        return draw.profile
    }

    private func pick(_ profile: String) {
        if let index = leg {
            model.draw.setLegProfile(index, profile)
        } else {
            model.draw.setProfile(profile, allLegs: allLegs)
        }
        dismiss()
    }
}

// MARK: - Quel point ? / Nom de l'étape

/// « Quel point ? » of a point of interest (new or placed) or « Nom de l'étape » of a stop, filled from the plan.
struct RoutePlaceSheetHost: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let target: RoutePlaceTarget

    var body: some View {
        let draw = model.draw
        switch target.kind {
        case .poi:
            let existing = poi(draw)
            RoutePlaceSheet(
                title: "Quel point ?",
                initialName: existing?.name ?? "",
                initialSym: existing?.sym,
                confirm: existing == nil ? "Ajouter" : "OK",
                onDone: { name, sym in
                    if let index = target.index {
                        draw.editPoi(index, name: name, sym: sym)
                    } else {
                        draw.addPoi(target.position, name: name, sym: sym)
                    }
                    dismiss()
                },
                onMove: moveAction(draw),
                onDelete: deleteAction(draw)
            )
        case .stop:
            let stop = stopOf(draw)
            RoutePlaceSheet(
                title: "Nom de l'étape",
                initialName: stop?.name ?? "",
                initialSym: stop?.sym,
                confirm: "OK",
                onDone: { name, sym in
                    if let index = target.index {
                        draw.nameStop(index, name, sym)
                    }
                    dismiss()
                },
                onMove: nil,
                onDelete: nil
            )
        }
    }

    private func poi(_ draw: DrawController) -> RoutePoi? {
        guard let index = target.index, let current = draw.plan, current.pois.indices.contains(index) else { return nil }
        return current.pois[index]
    }

    private func stopOf(_ draw: DrawController) -> RouteStop? {
        guard let index = target.index, let current = draw.plan, current.stops.indices.contains(index) else { return nil }
        let stop = current.stops[index]
        return stop.kind == .named ? stop : nil
    }

    private func moveAction(_ draw: DrawController) -> (() -> Void)? {
        guard let index = target.index else { return nil }
        return {
            dismiss()
            draw.takePoi(index)
        }
    }

    private func deleteAction(_ draw: DrawController) -> (() -> Void)? {
        guard let index = target.index else { return nil }
        return {
            dismiss()
            draw.removePoi(index)
        }
    }
}

/// The seven common icons and « Autre… » (the whole grid of the waypoints), the name under them (a touch on an icon
/// proposes its name while the user has typed none), then « Supprimer le point », « Déplacer », « Annuler » and the
/// confirmation (Android `PoiSheet`, variant B of P30.1).
struct RoutePlaceSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let initialName: String
    let initialSym: String?
    let confirm: String
    let onDone: (String, String?) -> Void
    let onMove: (() -> Void)?
    let onDelete: (() -> Void)?

    @State private var name = ""
    @State private var sym: String?
    @State private var proposed: String?
    @State private var picking = false
    @State private var ready = false

    /// The GPX symbol and the name it proposes.
    static let quick: [(sym: String, label: String)] = [
        (sym: "Spring", label: "Source"),
        (sym: "Alpinehut", label: "Refuge"),
        (sym: "View point", label: "Point de vue"),
        (sym: "Parking", label: "Parking"),
        (sym: "Danger area", label: "Danger"),
        (sym: "Peak", label: "Sommet"),
        (sym: "Picture", label: "Photo"),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    RoutePlaceIconGrid(sym: sym, onPick: { key, label in choose(key, label) }, onOther: { picking = true })
                    TextField("Nom", text: $name)
                        .textFieldStyle(.roundedBorder)
                    RoutePlaceSheetButtons(confirm: confirm, onMove: onMove, onDelete: onDelete) {
                        onDone(name, sym)
                    }
                }
                .padding(16)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Annuler")
                }
            }
            .sheet(isPresented: $picking) {
                RouteIconPicker(selected: WaypointSymbols.iconOf(sym).id) { icon in
                    picking = false
                    choose(icon.key, nil)
                }
                .presentationDetents([.medium, .large])
            }
        }
        .onAppear {
            if !ready {
                ready = true
                name = initialName
                sym = initialSym
            }
        }
    }

    /// An icon picked: its name is proposed while the user typed none.
    private func choose(_ key: String, _ label: String?) {
        sym = key
        guard let label else { return }
        let typed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if typed.isEmpty || name == proposed {
            name = label
            proposed = label
        }
    }

    /// Two symbol names for the same icon (files spell them differently).
    static func same(_ a: String, _ b: String?) -> Bool {
        guard let other = b else { return false }
        return WaypointSymbols.canonical(a) == WaypointSymbols.canonical(other)
    }
}

/// The seven icons and « Autre… », four to a row.
private struct RoutePlaceIconGrid: View {
    let sym: String?
    let onPick: (String, String?) -> Void
    let onOther: () -> Void

    var body: some View {
        let other = sym != nil && !RoutePlaceSheet.quick.contains { RoutePlaceSheet.same($0.sym, sym) }
        let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 4)
        LazyVGrid(columns: columns, spacing: 6) {
            ForEach(RoutePlaceSheet.quick, id: \.sym) { item in
                RoutePlaceIconTile(label: item.label, selected: RoutePlaceSheet.same(item.sym, sym)) {
                    onPick(item.sym, item.label)
                } icon: {
                    RouteIconBadge(icon: WaypointSymbols.iconOf(item.sym), size: 28)
                }
            }
            RoutePlaceIconTile(label: "Autre…", selected: other, action: onOther) {
                if other {
                    RouteIconBadge(icon: WaypointSymbols.iconOf(sym), size: 28)
                } else {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 20, weight: .semibold))
                        .frame(width: 28, height: 28)
                        .foregroundStyle(Crete.petrol)
                }
            }
        }
    }
}

/// One of the icons of « Quel point ? »: the icon over its name, the chosen one highlighted.
private struct RoutePlaceIconTile<Icon: View>: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                icon()
                Text(label)
                    .font(.caption.weight(.medium))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .foregroundStyle(Crete.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: 64)
            .padding(.vertical, 6)
            .background(selected ? Crete.pill : Crete.tile, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Supprimer le point · Déplacer · Annuler · Ajouter / OK.
private struct RoutePlaceSheetButtons: View {
    @Environment(\.dismiss) private var dismiss
    let confirm: String
    let onMove: (() -> Void)?
    let onDelete: (() -> Void)?
    let onConfirm: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            if let delete = onDelete {
                Button("Supprimer le point", role: .destructive, action: delete)
                    .font(.subheadline)
            }
            if let move = onMove {
                Button("Déplacer", action: move)
                    .font(.subheadline)
            }
            Spacer(minLength: 0)
            Button("Annuler") { dismiss() }
                .font(.subheadline)
            Button(action: onConfirm) {
                Text(confirm)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 36)
                    .background(Crete.turquoise, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .foregroundStyle(Crete.petrol)
    }
}

/// The whole grid of the waypoint icons, by category (Android `RouteIconPicker`).
struct RouteIconPicker: View {
    @Environment(\.dismiss) private var dismiss
    let selected: String
    let onSelect: (WaypointCatalog.Icon) -> Void

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 6)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(WaypointCatalog.categories, id: \.id) { category in
                        LazyVGrid(columns: columns, spacing: 8) {
                            ForEach(category.icons, id: \.id) { icon in
                                Button {
                                    onSelect(icon)
                                } label: {
                                    RouteIconBadge(icon: icon, size: 36)
                                        .padding(4)
                                        .overlay(
                                            Circle().stroke(icon.id == selected ? Crete.turquoise : Color.clear, lineWidth: 3)
                                        )
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(icon.key)
                            }
                        }
                        Divider()
                    }
                }
                .padding(16)
            }
            .navigationTitle("Icône")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Profil altimétrique

/// « Profil altimétrique » of the tools (Android `RouteProfileSheet`): the profile of the route being edited, tied to
/// the map by a cursor; the points of interest at their distance from the start, in the colour of their icon.
struct RouteProfileSheet: View {
    @Environment(AppModel.self) private var model
    @State private var cursorMeters: Double?

    var body: some View {
        let draw = model.draw
        let geometry = draw.plan?.geometry ?? []
        let profile = ElevationProfile.build(TrackPath([geometry]))
        VStack(alignment: .leading, spacing: 10) {
            Text("Profil d'altitude")
                .font(.headline)
                .foregroundStyle(Crete.onSurface)
            if let found = profile, found.samples.count >= 2 {
                ProfileReadout(profile: found, cursorMeters: cursorMeters)
                ProfileChart(profile: found, marks: marks(draw), cursorMeters: $cursorMeters)
                    .frame(height: 170)
            } else {
                Text("Pas encore d'altitudes sur cet itinéraire.")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .onChange(of: cursorMeters) { _, meters in
            model.draw.setCursor(meters)
        }
    }

    private func marks(_ draw: DrawController) -> [RouteProfileMark] {
        guard let current = draw.plan else { return [] }
        var out: [RouteProfileMark] = []
        for i in current.pois.indices {
            if let place = current.placeOf(i) {
                out.append(RouteProfileMark(meters: place.alongMeters, color: Color(routeArgb: WaypointSymbols.colorOf(current.pois[i].sym))))
            }
        }
        return out
    }
}

/// A point of interest on the profile.
struct RouteProfileMark {
    let meters: Double
    let color: Color
}

/// « 1,20 km · 1 234 m » at the cursor, else the lowest and the highest points.
private struct ProfileReadout: View {
    @Environment(AppModel.self) private var model
    let profile: ElevationProfile
    let cursorMeters: Double?

    var body: some View {
        let settings = model.settings
        Text(text(settings))
            .font(.subheadline.monospacedDigit())
            .foregroundStyle(Crete.onSurfaceVariant)
    }

    private func text(_ settings: AppSettings) -> String {
        if let meters = cursorMeters, let sample = profile.sampleAt(meters) {
            let distance = MapText.distance(sample.distanceMeters, settings)
            let elevation = MapText.elevation(sample.elevationMeters, settings)
            return "\(distance) · \(elevation)"
        }
        let low = profile.minElevation.map { MapText.elevation($0, settings) } ?? "–"
        let high = profile.maxElevation.map { MapText.elevation($0, settings) } ?? "–"
        let length = MapText.distance(profile.totalDistanceMeters, settings)
        return "\(length) · ↓ \(low) · ↑ \(high)"
    }
}

/// The elevation along the route, filled under its line; a finger moves the cursor.
private struct ProfileChart: View {
    let profile: ElevationProfile
    let marks: [RouteProfileMark]
    @Binding var cursorMeters: Double?

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            ZStack(alignment: .topLeading) {
                ProfileShape(profile: profile, closed: true)
                    .fill(Crete.turquoise.opacity(0.18))
                ProfileShape(profile: profile, closed: false)
                    .stroke(Crete.turquoise, style: StrokeStyle(lineWidth: 2, lineJoin: .round))
                ForEach(marks.indices, id: \.self) { i in
                    Circle()
                        .fill(marks[i].color)
                        .frame(width: 10, height: 10)
                        .position(x: x(marks[i].meters, size.width), y: size.height - 6)
                }
                if let meters = cursorMeters {
                    Rectangle()
                        .fill(Color(hex: 0xFF6F00))
                        .frame(width: 2, height: size.height)
                        .position(x: x(meters, size.width), y: size.height / 2)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let total = profile.totalDistanceMeters
                        guard size.width > 0, total > 0 else { return }
                        let fraction = min(max(value.location.x / size.width, 0), 1)
                        cursorMeters = Double(fraction) * total
                    }
            )
        }
    }

    private func x(_ meters: Double, _ width: CGFloat) -> CGFloat {
        let total = profile.totalDistanceMeters
        guard total > 0 else { return 0 }
        return CGFloat(meters / total) * width
    }
}

/// The line of the profile, or the area under it.
private struct ProfileShape: Shape {
    let profile: ElevationProfile
    let closed: Bool

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let samples = profile.samples
        guard samples.count >= 2,
              let low = profile.minElevation,
              let high = profile.maxElevation,
              profile.totalDistanceMeters > 0
        else { return path }
        let span = max(high - low, 10)
        let bottom = low - span * 0.1
        let top = high + span * 0.1
        let total = profile.totalDistanceMeters
        var points: [CGPoint] = []
        points.reserveCapacity(samples.count)
        for s in samples {
            let px = rect.minX + CGFloat(s.distanceMeters / total) * rect.width
            let py = rect.maxY - CGFloat((s.elevationMeters - bottom) / (top - bottom)) * rect.height
            points.append(CGPoint(x: px, y: py))
        }
        path.addLines(points)
        if closed, let first = points.first, let last = points.last {
            path.addLine(to: CGPoint(x: last.x, y: rect.maxY))
            path.addLine(to: CGPoint(x: first.x, y: rect.maxY))
            path.closeSubpath()
        }
        return path
    }
}
