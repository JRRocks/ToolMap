import CoreImage
import SwiftUI
import ToolMapCore
import UIKit

/// The words of the sharing screens, as the Android strings say them (`share_*`, `group_alert_*`).
enum ShareText {
    static let french = Locale(identifier: "fr_FR")

    /// The help of the sharing (Android `share_intro`, its last sentence said for the iPhone).
    static let intro = "Partagez votre position avec votre groupe. Un serveur de coordination (Firebase) fait passer les messages entre les téléphones, mais il ne voit que des données chiffrées : la clé du groupe reste dans les téléphones. On rejoint un groupe avec un code (QR code ou lien). Votre position part aussi écran éteint, tant que le partage est actif."

    /// The names of the tokens of `MemberLook.icons`, in the same order.
    static let tokenNames = [
        "Initiales", "Cavalier", "Loup", "Renard", "Aigle", "Pieuvre", "T-rex", "Licorne", "Dragon", "Fantôme",
        "Extraterrestre", "Robot", "Magicien", "Ninja", "Viking",
    ]

    /// Where an alert is from here, by eighth of the compass (Android `group_alert_directions`).
    static let directions = [
        "au nord", "au nord-est", "à l'est", "au sud-est", "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest",
    ]

    private static let relative: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = french
        formatter.unitsStyle = .full
        formatter.dateTimeStyle = .named
        return formatter
    }()

    private static let clockFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = french
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    /// « il y a 2 minutes ».
    static func age(_ timeMs: Int64, now: Int64 = EventLog.currentTimeMillis()) -> String {
        let date = Date(timeIntervalSince1970: Double(timeMs) / 1000)
        let reference = Date(timeIntervalSince1970: Double(max(now, timeMs)) / 1000)
        return relative.localizedString(for: date, relativeTo: reference)
    }

    /// « 10:41 ».
    static func clock(_ timeMs: Int64) -> String {
        clockFormatter.string(from: Date(timeIntervalSince1970: Double(timeMs) / 1000))
    }

    /// « 30 s », « 5 min ».
    static func seconds(_ value: Int) -> String {
        value < 60 ? "\(value) s" : "\(value / 60) min"
    }

    /// « Toujours », « 10 m ».
    static func move(_ meters: Int) -> String {
        meters == 0 ? "Toujours" : "\(meters) m"
    }

    /// « 1 membre », « 4 membres ».
    static func members(_ count: Int) -> String {
        count <= 1 ? "\(count) membre" : "\(count) membres"
    }

    static func unread(_ count: Int) -> String {
        count <= 1 ? "\(count) message non lu" : "\(count) messages non lus"
    }

    static func peerState(_ state: PeerState) -> String {
        switch state {
        case .online: "En ligne"
        case .paired: "Appairé, pas encore de nouvelles"
        case .lostSync: "Synchronisation perdue"
        case .offline: "Hors ligne"
        case .unpaired: "Non appairé"
        }
    }

    static func serverState(_ state: ServerState) -> String {
        switch state {
        case .none: "Liaison directe entre téléphones"
        case .connecting: "Connexion au serveur…"
        case .online: "Serveur joignable"
        case .unreachable: "Serveur injoignable (réseau coupé ?)"
        case .refused: "Serveur refusé : adresse ou clé invalide, ou accès anonyme désactivé"
        }
    }

    /// The eighth of the compass a bearing points to: 0 for north, 1 for north-east… 7 for north-west.
    static func octant(_ bearing: Double) -> Int {
        guard bearing.isFinite else { return 0 }
        let k = Int((bearing / 45).rounded())
        return ((k % 8) + 8) % 8
    }
}

/// The colours of the sharing (Android `ALERT_RED`, `LIFTED_GREEN`, error container) and the ARGB colours of the core.
enum ShareColors {
    static let alertRed = Color(hex: 0xD32F2F)
    static let liftedGreen = Color(hex: 0x2E7D32)
    static let errorContainer = Color(hex: 0xFFDAD6)
    static let onErrorContainer = Color(hex: 0x410002)
    static let amber = Color(hex: 0xFFDDB3)
    static let onAmber = Color(hex: 0x5F3B00)

    static func color(_ argb: Int32) -> Color {
        Color(hex: UInt32(bitPattern: argb) & 0xFF_FFFF)
    }

    static func uiColor(_ argb: Int32) -> UIColor {
        let value = UInt32(bitPattern: argb)
        let red = CGFloat((value >> 16) & 0xFF) / 255
        let green = CGFloat((value >> 8) & 0xFF) / 255
        let blue = CGFloat(value & 0xFF) / 255
        return UIColor(red: red, green: green, blue: blue, alpha: 1)
    }

    /// The opaque ARGB value of a colour chosen in the colour picker.
    static func argb(_ color: Color) -> Int32 {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        _ = UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let r = channel(red)
        let g = channel(green)
        let b = channel(blue)
        let value: UInt32 = 0xFF00_0000 | (r << 16) | (g << 8) | b
        return Int32(bitPattern: value)
    }

    private static func channel(_ value: CGFloat) -> UInt32 {
        UInt32(min(max((value * 255).rounded(), 0), 255))
    }
}

/// The member's own picture (Android `MemberPicture`): a square JPEG of `MemberLook.picturePx` pixels, at most
/// `MemberLook.maxPictureBytes`.
enum SharePicture {
    static func decode(_ bytes: [UInt8]?) -> UIImage? {
        guard let bytes, !bytes.isEmpty else { return nil }
        return UIImage(data: Data(bytes))
    }

    /// The picture made from the bytes of a photo: cut to its centred square, reduced, compressed until it fits; nil
    /// when the photo cannot be read.
    static func make(_ data: Data) -> [UInt8]? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let side = CGFloat(MemberLook.picturePx)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format)
        let factor = max(side / image.size.width, side / image.size.height)
        let drawn = CGSize(width: image.size.width * factor, height: image.size.height * factor)
        let origin = CGPoint(x: (side - drawn.width) / 2, y: (side - drawn.height) / 2)
        let square = renderer.image { _ in
            image.draw(in: CGRect(origin: origin, size: drawn))
        }
        var quality: CGFloat = 0.85
        while quality > 0.1 {
            if let jpeg = square.jpegData(compressionQuality: quality), jpeg.count <= MemberLook.maxPictureBytes {
                return [UInt8](jpeg)
            }
            quality -= 0.15
        }
        return nil
    }
}

/// The silhouettes of the tokens (`MemberLook.icons`, game-icons.net). They come from the core's `Glyphs` once it is
/// in the build; until then there is none, and the initials stand in.
enum ShareTokenArt {
    /// The silhouette of token `icon` (game-icons.net, ADR-050) tinted with `argb`, `side` points wide; nil for the
    /// initials, a picture or an unknown token.
    static func image(_ icon: Int, _ argb: Int32, side: CGFloat) -> UIImage? {
        guard let key = Glyphs.tokenKey(icon) else { return nil }
        let scale = Int(UIScreen.main.scale.rounded())
        let color = ShareColors.uiColor(argb).cgColor
        guard let image = Glyphs.image(key, size: Int(side.rounded()), scale: scale, color: color) else { return nil }
        return UIImage(cgImage: image, scale: CGFloat(scale), orientation: .up)
    }
}

/// A QR code image (CoreImage `CIQRCodeGenerator`), kept for the last text asked.
enum ShareQr {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var lastText: String?
    nonisolated(unsafe) private static var lastImage: UIImage?

    static func image(_ text: String) -> UIImage? {
        lock.lock()
        defer { lock.unlock() }
        if text == lastText, let lastImage { return lastImage }
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        let made = UIImage(cgImage: cgImage)
        lastText = text
        lastImage = made
        return made
    }
}

/// A member as the others see it, like a game token (Android `MemberBadge`): its token or initials in its colour on a
/// contrasting disc ringed with that colour, or its own picture in the ring.
struct ShareMemberBadge: View {
    let name: String
    let look: Int32
    var size: CGFloat = 36
    var picture: [UInt8]?

    var body: some View {
        let argb = MemberLook.argb(look)
        let color = ShareColors.color(argb)
        ZStack {
            Circle()
                .fill(ShareColors.color(MemberLook.backdrop(argb)))
            inside(argb: argb, color: color)
            Circle()
                .strokeBorder(color, lineWidth: max(size * 0.08, 2))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func inside(argb: Int32, color: Color) -> some View {
        let icon = MemberLook.icon(look)
        if icon == MemberLook.picture, let image = SharePicture.decode(picture) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size * 0.84, height: size * 0.84)
                .clipShape(Circle())
        } else if let token = ShareTokenArt.image(icon, argb, side: size * 0.6) {
            Image(uiImage: token)
                .resizable()
                .frame(width: size * 0.6, height: size * 0.6)
        } else {
            Text(MemberPalette.initials(name))
                .font(.system(size: size * 0.36, weight: .bold))
                .foregroundStyle(color)
        }
    }
}

/// One icon per state of a member, told apart by shape as well as colour (Android `PeerStatusIcon`): a tick (online),
/// two rings (paired), an exclamation mark (sync lost), a dash (offline), a cross (not paired).
struct SharePeerStatusIcon: View {
    let state: PeerState
    var size: CGFloat = 22

    var body: some View {
        let current = state
        Canvas { context, canvasSize in
            let d = min(canvasSize.width, canvasSize.height)
            let disc = Path(ellipseIn: CGRect(x: 0, y: 0, width: d, height: d))
            context.fill(disc, with: .color(SharePeerStatusMarks.color(current)))
            let style = StrokeStyle(lineWidth: d * 0.11, lineCap: .round, lineJoin: .round)
            context.stroke(SharePeerStatusMarks.mark(current, d), with: .color(.white), style: style)
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(ShareText.peerState(state))
    }
}

/// The drawings of `SharePeerStatusIcon`.
enum SharePeerStatusMarks {
    static func color(_ state: PeerState) -> Color {
        switch state {
        case .online: Color(hex: 0x2E7D32)
        case .paired: Color(hex: 0x1565C0)
        case .lostSync: Color(hex: 0xE08A00)
        case .offline: Color(hex: 0x757575)
        case .unpaired: Color(hex: 0xC62828)
        }
    }

    static func mark(_ state: PeerState, _ d: CGFloat) -> Path {
        var path = Path()
        switch state {
        case .online:
            path.move(to: CGPoint(x: d * 0.28, y: d * 0.52))
            path.addLine(to: CGPoint(x: d * 0.44, y: d * 0.68))
            path.addLine(to: CGPoint(x: d * 0.73, y: d * 0.34))
        case .paired:
            path.addEllipse(in: CGRect(x: d * 0.24, y: d * 0.36, width: d * 0.28, height: d * 0.28))
            path.addEllipse(in: CGRect(x: d * 0.48, y: d * 0.36, width: d * 0.28, height: d * 0.28))
        case .lostSync:
            path.move(to: CGPoint(x: d * 0.5, y: d * 0.24))
            path.addLine(to: CGPoint(x: d * 0.5, y: d * 0.56))
            path.move(to: CGPoint(x: d * 0.5, y: d * 0.735))
            path.addLine(to: CGPoint(x: d * 0.5, y: d * 0.745))
        case .offline:
            path.move(to: CGPoint(x: d * 0.28, y: d * 0.5))
            path.addLine(to: CGPoint(x: d * 0.72, y: d * 0.5))
        case .unpaired:
            path.move(to: CGPoint(x: d * 0.32, y: d * 0.32))
            path.addLine(to: CGPoint(x: d * 0.68, y: d * 0.68))
            path.move(to: CGPoint(x: d * 0.68, y: d * 0.32))
            path.addLine(to: CGPoint(x: d * 0.32, y: d * 0.68))
        }
        return path
    }
}

/// A member of the group in the list (Android `MemberLine`): token, name, state and age, its state icon, and a button
/// that shows it on the map.
struct ShareMemberRow: View {
    @Environment(AppModel.self) private var model
    let member: MemberView
    let now: Int64
    let up: Bool
    let onOpen: () -> Void

    var body: some View {
        let state = PeerState.of(member, now, serverUp: up)
        // Online without sharing: « À l'écoute », with the icon of « En ligne » and no age of position (P22.3).
        let listening = member.sharing == false && state == .online
        let fix: PeerFix? = member.sharing == false ? nil : member.fix
        HStack(spacing: 4) {
            Button(action: onOpen) {
                HStack(spacing: 14) {
                    ShareMemberBadge(name: member.name, look: member.color, size: 40, picture: member.picture)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(member.name)
                            .font(.body)
                            .foregroundStyle(Crete.onSurface)
                        Text(subtitle(state, listening))
                            .font(.footnote)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                    Spacer(minLength: 4)
                    SharePeerStatusIcon(state: state)
                }
                .frame(minHeight: 52)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button {
                if let fix {
                    model.sharing.showOnMap(fix)
                }
            } label: {
                Image(systemName: "map")
                    .font(.system(size: 18))
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(Crete.onSurfaceVariant)
            .disabled(fix == nil)
            .accessibilityLabel("Voir \(member.name) sur la carte")
        }
    }

    private func subtitle(_ state: PeerState, _ listening: Bool) -> String {
        if listening { return "À l'écoute" }
        let label = ShareText.peerState(state)
        guard let fix = member.fix else { return label }
        let age = ShareText.age(fix.timeSec * 1000, now: now)
        return "\(label) · \(age)"
    }
}

/// A figure of the sheet of a member: « 1 874 m » over « Altitude ».
struct ShareFigure: Hashable {
    let value: String
    let label: String
}
