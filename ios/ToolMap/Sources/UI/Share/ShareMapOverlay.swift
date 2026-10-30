import SwiftUI
import ToolMapCore

/// What the sharing puts on the map (Android `ShareHud`, P22, G3): the alerts of the group and their lifts, then the
/// chip « Partage actif · N connecté(s) » or « Groupe · N membres » (amber with a warning sign while the position is
/// refused to ToolMap), which opens the sharing; the sheet of a member touched on the map; the dialog of an alert of
/// the group when the map is on screen.
struct ShareMapOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        // The dialog of an alert shows from here only when nothing covers the map.
        let onScreen = model.screen == nil && model.panel == nil && sharing.mapMember == nil
        VStack(alignment: .trailing, spacing: 6) {
            if sharing.inGroup {
                ForEach(sharing.sos) { alert in
                    ShareAlertBanner(alert: alert) {
                        if let fix = alert.fix {
                            model.show(LonLat(lon: fix.lon, lat: fix.lat), zoom: max(model.zoom, 15))
                        }
                    }
                }
                ForEach(sharing.sosLifted) { lifted in
                    ShareLiftedBanner(lifted: lifted)
                }
                if sharing.sharing || !sharing.others.isEmpty {
                    ShareChip()
                }
            }
        }
        .frame(maxWidth: 210, alignment: .trailing)
        .padding(.top, 8)
        .padding(.trailing, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        .sheet(item: Binding(get: { sharing.mapMember }, set: { sharing.mapMember = $0 })) { ref in
            ShareMemberSheet(memberId: ref.id)
                .environment(model)
        }
        .modifier(ShareAlertDialogs(enabled: onScreen))
    }
}

/// « Partage actif · 1 connecté(s) » or « Groupe · 4 membres »: a pill that opens the sharing screen.
struct ShareChip: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        let warn = sharing.sharing && sharing.locationDenied
        Button {
            model.screen = .share
        } label: {
            HStack(spacing: 4) {
                if warn {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 12))
                        .accessibilityLabel("Un réglage du téléphone peut couper le partage")
                }
                Text(sharing.hudText)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(warn ? ShareColors.onAmber : Crete.onSurface)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(warn ? ShareColors.amber : Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
        }
        .buttonStyle(.plain)
    }
}
