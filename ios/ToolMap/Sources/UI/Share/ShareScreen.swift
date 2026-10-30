import SwiftUI
import ToolMapCore
import UIKit

/// The pages opened from the main page of the sharing screen (Android `SharePage`).
enum SharePage: Hashable {
    case profile
    case settings
    case messages
}

/// The sheets of the sharing screen.
enum ShareSheetKind: Identifiable {
    case invitation
    case member(Int64)
    case sendAlert

    var id: String {
        switch self {
        case .invitation: "invitation"
        case let .member(memberId): "member-\(memberId)"
        case .sendAlert: "send-alert"
        }
    }
}

/// What the main page asks the screen to do.
struct ShareMainActions {
    let invite: () -> Void
    let scan: () -> Void
    let paste: () -> Void
    let settings: () -> Void
    let help: () -> Void
    let leave: () -> Void
    let openMember: (Int64) -> Void
    let sendAlert: () -> Void
}

/// Partage de position in « Cordée » (Android SHARE, P29.8, `docs/previews/design-08-partage.html` variant B). Out of
/// a group: your line, « Aucun groupe », « Créer un groupe et inviter », « Rejoindre un groupe » (Scanner un code ·
/// Coller un lien). In a group: your line with « Partager ma position », the red line « Envoyer une alerte au groupe »
/// (or your alert in progress), the members and the state of the server, the messages, « Inviter » floating; the ⋮
/// holds scanning, pasting, the settings, the help and leaving. The invitation, the sheet of a member and the sending
/// of an alert are sheets.
struct ShareScreen: View {
    @Environment(AppModel.self) private var model
    @State private var path: [SharePage] = []
    @State private var sheet: ShareSheetKind?
    @State private var scanning = false
    @State private var pasting = false
    @State private var pasteText = ""
    @State private var showHelp = false
    @State private var confirmLeave = false
    @State private var now = EventLog.currentTimeMillis()

    var body: some View {
        NavigationStack(path: $path) {
            ShareMainPage(now: now, actions: actions)
                .navigationDestination(for: SharePage.self) { page in
                    ShareSubPage(page: page)
                }
        }
        .sheet(item: $sheet, onDismiss: { model.sharing.invitation = nil }) { kind in
            ShareSheetHost(kind: kind)
                .environment(model)
        }
        .fullScreenCover(isPresented: $scanning) {
            ShareScannerView(
                onCode: { code in
                    scanning = false
                    model.sharing.handleCode(code)
                },
                onClose: { scanning = false }
            )
        }
        .modifier(ShareScreenDialogs(
            pasting: $pasting,
            pasteText: $pasteText,
            showHelp: $showHelp,
            confirmLeave: $confirmLeave,
            openSettings: { path.append(.settings) }
        ))
        .task {
            // The ages and the states of the members are read again every 15 s.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                now = EventLog.currentTimeMillis()
            }
        }
    }

    private var actions: ShareMainActions {
        ShareMainActions(
            invite: {
                model.sharing.invite()
                if model.sharing.invitation != nil {
                    sheet = .invitation
                }
            },
            scan: { scanning = true },
            paste: { pasting = true },
            settings: { path.append(.settings) },
            help: { showHelp = true },
            leave: { confirmLeave = true },
            openMember: { memberId in sheet = .member(memberId) },
            sendAlert: { sheet = .sendAlert }
        )
    }
}

/// The page under the bar: out of a group or in one.
struct ShareMainPage: View {
    @Environment(AppModel.self) private var model
    let now: Int64
    let actions: ShareMainActions

    var body: some View {
        let sharing = model.sharing
        Group {
            if sharing.inGroup {
                ShareGroupList(now: now, actions: actions)
            } else {
                ShareNoGroupPage(actions: actions)
            }
        }
        .navigationTitle("Partage de position")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                ScreenCloseButton()
            }
            ToolbarItem(placement: .principal) {
                ShareTitle()
            }
            ToolbarItem(placement: .topBarTrailing) {
                ShareMoreMenu(actions: actions)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if sharing.inGroup {
                ShareInviteButton(action: actions.invite)
                    .padding(16)
            }
        }
        .overlay(alignment: .bottom) {
            ShareToast()
                .padding(.bottom, 90)
        }
    }
}

/// « Partage de position » and, in a group, the words of the chip of the map.
struct ShareTitle: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        VStack(spacing: 0) {
            Text("Partage de position")
                .font(.headline)
            if sharing.inGroup {
                Text(sharing.hudText)
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
    }
}

/// The ⋮ of the bar.
struct ShareMoreMenu: View {
    @Environment(AppModel.self) private var model
    let actions: ShareMainActions

    var body: some View {
        let inGroup = model.sharing.inGroup
        Menu {
            if inGroup {
                Button(action: actions.scan) {
                    Label("Scanner un code", systemImage: "qrcode.viewfinder")
                }
                Button(action: actions.paste) {
                    Label("Coller un lien", systemImage: "link")
                }
            }
            Button(action: actions.settings) {
                Label("Réglages du partage", systemImage: "slider.horizontal.3")
            }
            Button(action: actions.help) {
                Label("Aide", systemImage: "questionmark.circle")
            }
            if inGroup {
                Button(role: .destructive, action: actions.leave) {
                    Label("Quitter le groupe", systemImage: "rectangle.portrait.and.arrow.right")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Plus d'options du partage")
    }
}

/// « Inviter », the one filled action of the page in a group.
struct ShareInviteButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("Inviter", systemImage: "person.badge.plus")
                .font(.headline)
                .foregroundStyle(Color.white)
                .padding(.horizontal, 20)
                .frame(height: 56)
                .background(Crete.petrol, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .shadow(color: .black.opacity(0.2), radius: 6, y: 3)
        }
        .buttonStyle(.plain)
    }
}

/// A short message at the bottom of the screen (Android snackbar of the sharing screen).
struct ShareToast: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        if let text = sharing.toast {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Color.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 16)
                .task(id: text) {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    if sharing.toast == text {
                        sharing.toast = nil
                    }
                }
        }
    }
}

/// The page of a group.
struct ShareGroupList: View {
    @Environment(AppModel.self) private var model
    let now: Int64
    let actions: ShareMainActions

    var body: some View {
        let sharing = model.sharing
        List {
            Section {
                ShareSelfSendLine(now: now) {
                    actions.openMember(sharing.selfId)
                }
            }
            Section {
                if sharing.ownSos {
                    ShareOwnAlertCard()
                } else {
                    ShareSendAlertLine(action: actions.sendAlert)
                }
            }
            if sharing.sharing && sharing.locationDenied {
                Section {
                    ShareLocationCard()
                }
            }
            ShareMembersSection(now: now, actions: actions)
            Section {
                NavigationLink(value: SharePage.messages) {
                    ShareMessagesLine()
                }
            }
        }
        .listStyle(.insetGrouped)
        .contentMargins(.bottom, 88, for: .scrollContent)
    }
}

/// In a group: your token, « Partager ma position » and « iPhone (vous) · En ligne »; the text opens your sheet, the
/// switch apart sends or not.
struct ShareSelfSendLine: View {
    @Environment(AppModel.self) private var model
    let now: Int64
    let onOpen: () -> Void

    var body: some View {
        let sharing = model.sharing
        HStack(spacing: 12) {
            Button(action: onOpen) {
                HStack(spacing: 14) {
                    ShareMemberBadge(
                        name: sharing.displayName,
                        look: sharing.settings.look(),
                        size: 48,
                        picture: sharing.settings.pictureBytes()
                    )
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Partager ma position")
                            .font(.body)
                            .foregroundStyle(Crete.onSurface)
                        Text(subtitle(sharing))
                            .font(.footnote)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(sharing.me == nil)
            Rectangle()
                .fill(Crete.outline)
                .frame(width: 1, height: 32)
            Toggle("Partager ma position", isOn: Binding(get: { sharing.sharing }, set: { sharing.setSharing($0) }))
                .labelsHidden()
                .tint(Crete.turquoise)
        }
    }

    private func subtitle(_ sharing: ShareController) -> String {
        let name = "\(sharing.displayName) (vous)"
        guard sharing.sharing, let me = sharing.me else { return "\(name) · À l'écoute" }
        let state = PeerState.of(me, now, serverUp: sharing.serverUp)
        return "\(name) · \(ShareText.peerState(state))"
    }
}

/// « Localisation coupée »: the phone does not let ToolMap read the position (Android alert card, P21.5 o).
struct ShareLocationCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Localisation coupée", systemImage: "location.slash.fill")
                .font(.headline)
                .foregroundStyle(Crete.danger)
            Text("Aucune position ne part tant qu'elle est coupée.")
                .font(.subheadline)
                .foregroundStyle(Crete.onSurface)
            Button("Activer la localisation") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(Crete.danger)
        }
        .padding(.vertical, 4)
    }
}

/// « Membres », the state of the server, and each other member.
struct ShareMembersSection: View {
    @Environment(AppModel.self) private var model
    let now: Int64
    let actions: ShareMainActions

    var body: some View {
        let sharing = model.sharing
        let up = sharing.serverUp
        Section {
            if sharing.others.isEmpty {
                Text("Personne d'autre pour l'instant. Invitez quelqu'un avec un code.")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
            } else {
                ForEach(sharing.others, id: \.id) { member in
                    ShareMemberRow(member: member, now: now, up: up) {
                        actions.openMember(member.id)
                    }
                }
            }
        } header: {
            HStack(alignment: .bottom, spacing: 8) {
                Text("Membres")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.onSurface)
                Spacer()
                if sharing.onServer {
                    Text(ShareText.serverState(sharing.server))
                        .font(.caption)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(up ? Crete.onSurfaceVariant : Crete.danger)
                }
            }
            .textCase(nil)
        }
    }
}

/// « Messages »: the last one on one line and the count of the unread, opening their page.
struct ShareMessagesLine: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        HStack(spacing: 16) {
            Image(systemName: "bubble.left.and.bubble.right")
                .foregroundStyle(Crete.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text("Messages")
                    .font(.body)
                Text(lastLine(sharing))
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if sharing.unread > 0 {
                Text("\(sharing.unread)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .frame(minWidth: 22)
                    .background(Crete.turquoise, in: Capsule())
                    .accessibilityLabel(ShareText.unread(sharing.unread))
            }
        }
        .frame(minHeight: 48)
    }

    private func lastLine(_ sharing: ShareController) -> String {
        guard let last = sharing.messages.last else { return "Aucun message pour l'instant." }
        let author = last.mine ? "Moi" : last.name
        return "\(author) : \(last.text)"
    }
}

/// Out of a group: your line, « Aucun groupe », create, join.
struct ShareNoGroupPage: View {
    let actions: ShareMainActions

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                NavigationLink(value: SharePage.profile) {
                    ShareSelfLookLine()
                }
                .buttonStyle(.plain)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
                ShareNoGroupState()
                Button(action: actions.invite) {
                    Label("Créer un groupe et inviter", systemImage: "person.badge.plus")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(Crete.turquoise)
                ShareJoinButtons(actions: actions)
            }
            .padding(16)
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }
}

/// Out of a group: your token and name as the others will see them; the line opens « Ce que les autres voient ».
struct ShareSelfLookLine: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        HStack(spacing: 14) {
            ShareMemberBadge(
                name: sharing.displayName,
                look: sharing.settings.look(),
                size: 48,
                picture: sharing.settings.pictureBytes()
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(sharing.displayName)
                    .font(.body)
                    .foregroundStyle(Crete.onSurface)
                Text("Ce que les autres voient")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            Spacer(minLength: 4)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Crete.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 72)
        .contentShape(Rectangle())
    }
}

/// « Aucun groupe »: an empty state that says what a group is for.
struct ShareNoGroupState: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.3.fill")
                .font(.system(size: 28))
                .foregroundStyle(Crete.petrol)
                .frame(width: 64, height: 64)
                .background(Crete.tile, in: Circle())
            Text("Aucun groupe")
                .font(.title3.weight(.semibold))
                .padding(.top, 8)
            Text("Créez un groupe et invitez les autres par un code, ou rejoignez celui d'un autre.")
                .font(.subheadline)
                .foregroundStyle(Crete.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 20)
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }
}

/// « Rejoindre un groupe »: Scanner un code · Coller un lien, side by side.
struct ShareJoinButtons: View {
    let actions: ShareMainActions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Rejoindre un groupe")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Crete.onSurfaceVariant)
                .padding(.leading, 4)
                .padding(.top, 4)
            HStack(spacing: 8) {
                Button(action: actions.scan) {
                    Label("Scanner un code", systemImage: "qrcode.viewfinder")
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.bordered)
                Button(action: actions.paste) {
                    Label("Coller un lien", systemImage: "link")
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.bordered)
            }
            .tint(Crete.petrol)
        }
    }
}

/// The pages pushed from the main page.
struct ShareSubPage: View {
    let page: SharePage

    var body: some View {
        switch page {
        case .profile: ShareProfileView()
        case .settings: ShareSettingsView()
        case .messages: ShareMessagesView()
        }
    }
}

/// The content of a sheet of the sharing screen.
struct ShareSheetHost: View {
    @Environment(AppModel.self) private var model
    let kind: ShareSheetKind

    var body: some View {
        switch kind {
        case .invitation:
            if let code = model.sharing.invitation {
                ShareInviteSheet(code: code)
            }
        case let .member(memberId):
            ShareMemberSheet(memberId: memberId)
        case .sendAlert:
            ShareSendAlertSheet()
        }
    }
}

/// The dialogs of the sharing screen: a refused code, removed from the group, pasting a link, the help, leaving, and
/// the alerts of the group.
struct ShareScreenDialogs: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var pasting: Bool
    @Binding var pasteText: String
    @Binding var showHelp: Bool
    @Binding var confirmLeave: Bool
    let openSettings: () -> Void

    func body(content: Content) -> some View {
        let sharing = model.sharing
        let problem = sharing.problem
        content
            .alert(
                problem?.title ?? "Code refusé",
                isPresented: Binding(get: { sharing.problem != nil }, set: { if !$0 { sharing.problem = nil } })
            ) {
                if problem == .noServer {
                    Button("Réglages du partage") {
                        sharing.problem = nil
                        openSettings()
                    }
                }
                Button("OK", role: .cancel) {
                    sharing.problem = nil
                }
            } message: {
                Text(problem?.text ?? "")
            }
            .alert(
                "Retiré du groupe",
                isPresented: Binding(get: { sharing.excluded }, set: { if !$0 { sharing.excluded = false } })
            ) {
                Button("OK", role: .cancel) {
                    sharing.excluded = false
                }
            } message: {
                Text("Les autres membres vous ont retiré du groupe : vous ne recevez plus rien. Il faudra une nouvelle invitation pour revenir.")
            }
            .modifier(SharePasteDialog(pasting: $pasting, pasteText: $pasteText))
            .alert("Partage de position", isPresented: $showHelp) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(ShareText.intro)
            }
            .alert("Quitter le groupe ?", isPresented: $confirmLeave) {
                Button("Annuler", role: .cancel) {}
                Button("Quitter le groupe", role: .destructive) {
                    sharing.leaveGroup()
                }
            } message: {
                Text("Les autres sont prévenus et vous n'apparaissez plus sur leur carte. Pour revenir, il faudra une nouvelle invitation.")
            }
            .modifier(ShareAlertDialogs(enabled: true))
    }
}

/// « Coller un lien »: the field and « Valider », greyed while it is empty.
struct SharePasteDialog: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var pasting: Bool
    @Binding var pasteText: String

    func body(content: Content) -> some View {
        let sharing = model.sharing
        let blank = pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        content
            .alert("Coller un lien", isPresented: $pasting) {
                TextField("toolmap://…", text: $pasteText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Annuler", role: .cancel) {
                    pasteText = ""
                }
                Button("Valider") {
                    let text = pasteText
                    pasteText = ""
                    sharing.handleCode(text)
                }
                .disabled(blank)
            } message: {
                Text("Collez le lien toolmap://… (ou le texte du code) reçu par message.")
            }
    }
}
