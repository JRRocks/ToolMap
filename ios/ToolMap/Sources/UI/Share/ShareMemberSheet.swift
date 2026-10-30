import SwiftUI
import ToolMapCore

/// The sheet of a member (Android `MemberSheet`, P29.8 « Cordée »), from the list and from the map: its token, name
/// and state, four figures (Altitude · Vitesse · Batterie · Précision), its position in the chosen format, then the
/// tiles « Voir sur la carte » · « Aller vers ce membre »; « Retirer du groupe » is in the ⋮ of the head, with a
/// confirmation. Your own sheet has neither « Aller vers ce membre » nor ⋮.
struct ShareMemberSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let memberId: Int64

    var body: some View {
        let found = model.sharing.members.first { $0.id == memberId }
        Group {
            if let member = found {
                ShareMemberCard(member: member)
            } else {
                // The member left or was removed meanwhile.
                Color.clear
                    .onAppear { dismiss() }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

/// The content of the sheet of a member.
struct ShareMemberCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let member: MemberView
    @State private var confirmRemove = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ShareMemberHead(member: member, onRemove: { confirmRemove = true })
            ShareMemberFigures(member: member)
            ShareMemberPosition(member: member)
            ShareMemberTiles(member: member)
            Spacer(minLength: 0)
        }
        .padding(.top, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .alert("Retirer « \(member.name) » du groupe ?", isPresented: $confirmRemove) {
            Button("Annuler", role: .cancel) {}
            Button("Retirer du groupe", role: .destructive) {
                model.sharing.removeMember(member.id)
                dismiss()
            }
        } message: {
            Text("Il ne reçoit plus rien ; pour revenir, il lui faudra une nouvelle invitation.")
        }
    }
}

/// Token, name, state and age, state icon and ⋮.
struct ShareMemberHead: View {
    @Environment(AppModel.self) private var model
    let member: MemberView
    let onRemove: () -> Void

    var body: some View {
        let sharing = model.sharing
        let up = sharing.serverUp
        HStack(spacing: 14) {
            ShareMemberBadge(name: member.name, look: member.color, size: 48, picture: member.picture)
            VStack(alignment: .leading, spacing: 2) {
                Text(member.isSelf ? "\(member.name) (vous)" : member.name)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Crete.onSurface)
                TimelineView(.periodic(from: .now, by: 15)) { context in
                    let now = Int64(context.date.timeIntervalSince1970 * 1000)
                    Text(ShareMemberHead.stateLine(member, now: now, up: up))
                        .font(.subheadline)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            Spacer(minLength: 4)
            TimelineView(.periodic(from: .now, by: 15)) { context in
                let now = Int64(context.date.timeIntervalSince1970 * 1000)
                SharePeerStatusIcon(state: PeerState.of(member, now, serverUp: up))
            }
            .frame(width: 22, height: 22)
            if !member.isSelf {
                Menu {
                    Button(role: .destructive, action: onRemove) {
                        Label("Retirer du groupe", systemImage: "person.badge.minus")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 20))
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Actions sur \(member.name)")
            }
        }
        .padding(.horizontal, 16)
    }

    static func stateLine(_ member: MemberView, now: Int64, up: Bool) -> String {
        let state = ShareText.peerState(PeerState.of(member, now, serverUp: up))
        guard let fix = member.fix else { return state }
        return "\(state) · \(ShareText.age(fix.timeSec * 1000, now: now))"
    }
}

/// Altitude · Vitesse · Batterie · Précision, those that are known.
struct ShareMemberFigures: View {
    @Environment(AppModel.self) private var model
    let member: MemberView

    var body: some View {
        let figures = ShareMemberFigures.figures(member, model.settings.units)
        if !figures.isEmpty {
            HStack(alignment: .top, spacing: 8) {
                ForEach(figures, id: \.self) { figure in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(figure.value)
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(Crete.onSurface)
                        Text(figure.label)
                            .font(.caption)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    static func figures(_ member: MemberView, _ units: UnitSystem) -> [ShareFigure] {
        guard let fix = member.fix else { return [] }
        var list: [ShareFigure] = []
        if let altitude = fix.altM {
            list.append(ShareFigure(value: "\(altitude) m", label: "Altitude"))
        }
        if let speed = fix.speedMs {
            list.append(ShareFigure(value: TrackFormat.speed(speed, locale: ShareText.french, units: units), label: "Vitesse"))
        }
        if let battery = fix.battery {
            list.append(ShareFigure(value: "\(battery)\u{00A0}%", label: "Batterie"))
        }
        if let accuracy = fix.accuracyM {
            list.append(ShareFigure(value: "±\(accuracy) m", label: "Précision"))
        }
        return list
    }
}

/// The position in the format chosen in the settings, or « Pas encore de position reçue ».
struct ShareMemberPosition: View {
    @Environment(AppModel.self) private var model
    let member: MemberView

    var body: some View {
        HStack(spacing: 20) {
            Image(systemName: "mappin.and.ellipse")
                .foregroundStyle(Crete.onSurfaceVariant)
            Text(positionText)
                .font(.body.monospacedDigit())
                .foregroundStyle(Crete.onSurface)
                .textSelection(.enabled)
        }
        .frame(minHeight: 40)
        .padding(.horizontal, 16)
    }

    private var positionText: String {
        guard let fix = member.fix else { return "Pas encore de position reçue" }
        return CoordinateFormat.format(model.settings.coordinates, fix.lat, fix.lon).text
    }
}

/// « Voir sur la carte » · « Aller vers ce membre ».
struct ShareMemberTiles: View {
    @Environment(AppModel.self) private var model
    let member: MemberView

    var body: some View {
        let sharing = model.sharing
        let fix = member.fix
        HStack(spacing: 8) {
            ToolTile(systemImage: "map", title: "Voir sur la carte", enabled: fix != nil) {
                if let fix {
                    sharing.showOnMap(fix)
                }
            }
            if !member.isSelf {
                ToolTile(systemImage: "location.north.line", title: "Aller vers ce membre", enabled: fix != nil) {
                    sharing.goTo(member)
                }
            }
        }
        .padding(.horizontal, 16)
    }
}
