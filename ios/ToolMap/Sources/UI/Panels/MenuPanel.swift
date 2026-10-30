import SwiftUI
import ToolMapCore

/// Main menu (Android §3, P29.2 « Portée », docs/previews/design-02-menu.html variant B): the petrol band with the
/// logo, the name and the version of the app and its « ? »; at the top, far from the thumb, the « Plus » row that
/// unfolds in place (Nouveautés, Signaler une erreur); then « Réglages… », the information on screen and the night
/// mode; at the foot, fixed, the four tiles Masquer, Verrouiller, Imprimer, Mesurer. No « Quitter » (iOS apps are not
/// closed by themselves) and no « Mises à jour » (the App Store or TestFlight update the app).
struct MenuPanel: View {
    @Environment(AppModel.self) private var model
    @State private var moreOpen = false
    @State private var reportShown = false

    var body: some View {
        VStack(spacing: 0) {
            MenuHeader(version: model.version) { open(.about(nil)) }
            List {
                Section {
                    MenuMoreRow(open: moreOpen) {
                        withAnimation(.easeInOut(duration: 0.2)) { moreOpen.toggle() }
                    }
                    if moreOpen {
                        MenuRowButton(
                            icon: "megaphone",
                            title: "Nouveautés",
                            subtitle: "Les 5 dernières mises à jour",
                            indented: true
                        ) { open(.whatsNew) }
                        MenuRowButton(
                            icon: "ladybug",
                            title: "Signaler une erreur",
                            subtitle: "Vous choisissez à qui l'envoyer",
                            indented: true
                        ) { reportShown = true }
                    }
                }
                Section {
                    MenuRowButton(icon: "gearshape", title: "Réglages…", chevron: true) { open(.settings) }
                    MenuSwitches()
                }
            }
            .listStyle(.insetGrouped)
            MenuTiles()
        }
        .sheet(isPresented: $reportShown) {
            MenuReportSheet()
                .environment(model)
        }
    }

    /// A full-screen page: the menu sheet goes first, then the page comes (one presentation at a time on iOS).
    private func open(_ screen: Screen) {
        let app = model
        app.panel = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            app.screen = screen
        }
    }
}

/// The head of the menu, on the petrol band: the logo, the name and version of the app, the « ? » of the help.
struct MenuHeader: View {
    let version: String
    let onHelp: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "mountain.2.fill")
                .font(.system(size: 24))
                .foregroundStyle(Color.white)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("ToolMap")
                    .font(.headline)
                    .foregroundStyle(Color.white)
                Text("Version \(version) pour iPhone")
                    .font(.subheadline)
                    .foregroundStyle(Crete.barText)
            }
            Spacer()
            Button(action: onHelp) {
                Image(systemName: "questionmark.circle")
                    .font(.system(size: 22))
                    .foregroundStyle(Color.white)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Aide et à propos")
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .padding(.top, 20)
        .padding(.bottom, 8)
        .background(Crete.petrol)
    }
}

/// A row of the menu: a grey icon, the name, its second line, a chevron when it opens a page.
struct MenuRowButton: View {
    let icon: String
    let title: String
    var subtitle: String?
    var indented = false
    var chevron = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(Crete.onSurface)
                    if let subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 8)
                if chevron {
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .padding(.leading, indented ? 22 : 0)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// « Plus »: folded, its line says what it holds; unfolded, the rows come under it.
struct MenuMoreRow: View {
    let open: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18))
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Plus")
                        .foregroundStyle(Crete.onSurface)
                    if !open {
                        Text("Nouveautés, signaler une erreur")
                            .font(.footnote)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: open ? "chevron.up" : "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(open ? "dépliée" : "repliée")
        .accessibilityHint(open ? "replier" : "déplier")
    }
}

/// « Informations à l'écran » and « Mode nuit », switches of the menu (the intensity is in Réglages ▸ Affichage).
struct MenuSwitches: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        let percent = min(
            max(model.settings.maps.nightPercent, MapDisplaySettings.minNightPercent),
            MapDisplaySettings.maxNightPercent
        )
        Toggle(isOn: $model.showInfo) {
            MenuSwitchLabel(icon: "rectangle.3.group", title: "Informations à l'écran", subtitle: "Position, échelle, GPS")
        }
        .tint(Crete.turquoise)
        Toggle(isOn: $model.nightMode) {
            MenuSwitchLabel(
                icon: "moon",
                title: "Mode nuit",
                subtitle: model.nightMode ? "Carte assombrie à \(percent) %" : "Assombrit la carte à \(percent) %"
            )
        }
        .tint(Crete.turquoise)
    }
}

private struct MenuSwitchLabel: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(Crete.onSurfaceVariant)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(Crete.onSurface)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
    }
}

/// The foot of the menu, which does not scroll: the four tiles, always at the same place over the bar. Imprimer and
/// Mesurer are greyed while a tool is open.
struct MenuTiles: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let free = model.tool == nil
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 8) {
                ToolTile(systemImage: "arrow.up.left.and.arrow.down.right", title: "Masquer") {
                    model.panel = nil
                    model.controlsHidden = true
                }
                ToolTile(systemImage: "lock.fill", title: "Verrouiller") {
                    model.panel = nil
                    model.locked = true
                }
                ToolTile(systemImage: "printer", title: "Imprimer", enabled: free) {
                    model.panel = nil
                    model.tool = .print(alongTrack: nil)
                }
                ToolTile(systemImage: "ruler", title: "Mesurer", enabled: free) {
                    model.panel = nil
                    model.tool = .measure
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            .padding(.bottom, 10)
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }
}
