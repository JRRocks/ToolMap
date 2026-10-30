import SwiftUI
import ToolMapCore

/// The full-screen page shown above the map (`AppModel.screen`).
struct ScreenHost: View {
    let screen: Screen

    var body: some View {
        switch screen {
        case .library: LibraryScreen()
        case let .trackDetail(id): TrackDetailScreen(trackId: id)
        case .search: SearchScreen()
        case .share: ShareScreen()
        case .downloads: DownloadsScreen()
        case .storage: StorageScreen()
        case .availableMaps: AvailableMapsScreen()
        case .settings: SettingsScreen()
        case .backup: BackupScreen()
        case let .about(section): AboutScreen(section: section)
        case .whatsNew: WhatsNewScreen()
        }
    }
}

/// The tool panel above the bottom bar (`AppModel.tool`).
struct ToolPanelHost: View {
    let tool: Tool

    var body: some View {
        switch tool {
        case .measure: MeasurePanel()
        case let .draw(kind): DrawPanel(kind: kind)
        case let .print(alongTrack): PrintPanel(alongTrack: alongTrack)
        case let .zone(around): ZonePanel(around: around)
        }
    }
}

/// What the features draw above the map in SwiftUI (chips, banners, frames), over the whole map.
struct MapOverlayHost: View {
    var body: some View {
        ZStack {
            GridFiguresOverlay()
            PrintFrameOverlay()
            ZoneFrameOverlay()
            NavigationBanner()
            RecordingChip()
            ShareMapOverlay()
            LibraryMapOverlay()
            CenterQuickMenu()
        }
    }
}

/// A tool panel (Android `ToolPanel`, P29.10 « Équerre »): white, rounded, a petrol band with the tool's name, its
/// figures and ✕, then the tool's own content.
struct ToolPanel<Content: View>: View {
    @Environment(AppModel.self) private var model
    let title: String
    var subtitle: String?
    /// What ✕ does; by default it closes the tool.
    var onClose: (() -> Void)?
    @ViewBuilder let content: () -> Content

    init(
        title: String,
        subtitle: String? = nil,
        onClose: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.subtitle = subtitle
        self.onClose = onClose
        self.content = content
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(Color.white)
                    if let subtitle {
                        Text(subtitle)
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(Crete.barText)
                    }
                }
                Spacer()
                Button {
                    if let onClose {
                        onClose()
                    } else {
                        model.tool = nil
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Quitter")
            }
            .padding(.leading, 16)
            .padding(.trailing, 4)
            .padding(.vertical, 4)
            .background(Crete.petrol)
            content()
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color.white, in: RoundedRectangle(cornerRadius: 18))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: .black.opacity(0.18), radius: 8, y: 2)
    }
}

/// A tile of a tool panel (Android « tuile »): an icon above a short label; the main action is filled.
struct ToolTile: View {
    let systemImage: String
    let title: String
    var primary = false
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
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .padding(.vertical, 4)
            .foregroundStyle(primary ? Color.white : Crete.petrol)
            .background(primary ? Crete.turquoise : Crete.tile, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

/// A short message at the bottom of the map (Android snackbar); it goes after 4 s.
struct SnackBar: View {
    @Environment(AppModel.self) private var model
    let snack: Snack

    var body: some View {
        HStack {
            Text(snack.text)
                .font(.subheadline)
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let action = snack.action {
                Button(action) {
                    snack.perform?()
                    model.snack = nil
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.pill)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10))
        .task(id: snack.id) {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if model.snack?.id == snack.id {
                model.snack = nil
            }
        }
    }
}

/// The ✕ of a full-screen page: back to the map.
struct ScreenCloseButton: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Button {
            model.screen = nil
        } label: {
            Image(systemName: "xmark")
        }
        .accessibilityLabel("Fermer")
    }
}

/// A page that is not written yet on iPhone.
struct PlaceholderScreen: View {
    let title: String

    var body: some View {
        NavigationStack {
            ContentUnavailableView(title, systemImage: "hammer", description: Text("Cette page arrive bientôt sur iPhone."))
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { ScreenCloseButton() }
                }
        }
    }
}
