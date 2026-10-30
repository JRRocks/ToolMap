import SwiftUI
import ToolMapCore

/// The map screen: the map, the information pills (top left), the round buttons (right) and the bottom bar, like the
/// Android screen in the "Crête" style (P29.0, P29.1). Panels open as iOS sheets above the bar.
struct MapScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        // Android M2 / M3: the controls go while hidden or locked; T3: while the sheet and frame of a print are chosen.
        let framing = model.printer.hidesMapChrome
        let controlsShown = !model.controlsHidden && !model.locked
        ZStack {
            MapLibreView(
                model: model,
                layers: model.maps.layerSpecs,
                nightMode: model.nightMode,
                cameraRequest: model.cameraRequest,
                followMode: model.followMode,
                features: model.mapFeatures,
                locked: model.locked
            )
            .ignoresSafeArea()

            MapOverlayHost()

            if controlsShown && !framing {
                // At the centre of the map view, which goes under the bars (safe areas ignored).
                CenterRing()
                    .contentShape(Circle().inset(by: -12))
                    .onTapGesture { model.centerMenuOpen.toggle() }
                    .accessibilityLabel("Menu du point central")
                    .accessibilityAddTraits(.isButton)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .ignoresSafeArea()
            }

            if controlsShown {
                MapChrome(framing: framing)
            }

            LockOverlay()
        }
        .settingsEffects()
        .fullScreenCover(item: $model.screen) { screen in
            ScreenHost(screen: screen)
                .environment(model)
        }
        .sheet(item: $model.panel) { panel in
            PanelSheet(panel: panel)
                .environment(model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
                .presentationBackgroundInteraction(.enabled(upThrough: .medium))
        }
        .onAppear {
            // Back on, unless the user turned the position off (L3).
            model.location.resume()
        }
        .alert(
            "ToolMap",
            isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.notice ?? "")
        }
    }
}

/// The pills, the credits, the round buttons, the snack bar, the tool panel and the bottom bar.
struct MapChrome: View {
    @Environment(AppModel.self) private var model
    let framing: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                if model.showInfo && !framing {
                    InfoPills()
                }
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            Spacer()
            if !framing {
                HStack(alignment: .bottom) {
                    Text(model.maps.credits)
                        .font(.system(size: 10))
                        .foregroundStyle(Crete.onSurface)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.white.opacity(0.7), in: RoundedRectangle(cornerRadius: 4))
                        .lineLimit(1)
                    Spacer()
                    MapButtons()
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 10)
            }
            if let snack = model.snack {
                SnackBar(snack: snack)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            }
            if let tool = model.tool {
                ToolPanelHost(tool: tool)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 6)
            }
            // Hidden while a print or a zone is being framed, as on Android.
            if !framing && !model.downloads.hidesBottomBar {
                BottomBar()
            }
        }
    }
}

/// The small ring in the middle of the map (Android G12): turquoise circled with white.
struct CenterRing: View {
    var body: some View {
        Circle()
            .strokeBorder(Crete.turquoise, lineWidth: 2.5)
            .background(Circle().strokeBorder(Color.white, lineWidth: 5))
            .frame(width: 18, height: 18)
    }
}

/// Compass (hidden when north is up) and zoom buttons, on the right above the bar.
struct MapButtons: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 10) {
            if abs(model.direction) > 0.5 {
                RoundMapButton(systemImage: "location.north.line.fill", label: "Remettre le nord en haut") {
                    model.resetNorth()
                }
                .rotationEffect(.degrees(-model.direction))
            }
            RoundMapButton(systemImage: "plus", label: "Zoom avant") { model.zoomIn() }
            RoundMapButton(systemImage: "minus", label: "Zoom arrière") { model.zoomOut() }
        }
    }
}

/// The full-width petrol bar with the four panels (Android B1 to B4): Menu, Cartes, Traces, Position.
struct BottomBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack(spacing: 0) {
            item(.menu, "mountain.2.fill", "Menu")
            item(.maps, "map", "Cartes")
            item(.tracks, "flag", "Traces", accessibility: "Traces et waypoints")
            item(.position, "location", "Position", accessibility: "Ma position")
        }
        .padding(.top, 6)
        .padding(.bottom, 2)
        .background(Crete.petrol.ignoresSafeArea(edges: .bottom))
    }

    private func item(_ panel: Panel, _ icon: String, _ title: String, accessibility: String? = nil) -> some View {
        let selected = model.panel == panel
        return Button {
            model.panel = selected ? nil : panel
        } label: {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 19, weight: .medium))
                    .foregroundStyle(selected ? Crete.petrol : Color.white)
                    .frame(width: 56, height: 30)
                    .background(selected ? Crete.pill : Color.clear, in: Capsule())
                Text(title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(selected ? Color.white : Crete.barText)
            }
            .frame(maxWidth: .infinity)
        }
        .accessibilityLabel(accessibility ?? title)
    }
}

/// The content of the sheet opened by a bar item.
struct PanelSheet: View {
    let panel: Panel

    var body: some View {
        switch panel {
        case .menu: MenuPanel()
        case .maps: MapsPanel()
        case .tracks: TracksPanel()
        case .position: PositionPanel()
        }
    }
}

/// The petrol band at the top of every panel (ADR-060), with its title and an optional subtitle.
struct PanelHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.headline)
                .foregroundStyle(Color.white)
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(Crete.barText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 22)
        .padding(.bottom, 12)
        .background(Crete.petrol)
    }
}
