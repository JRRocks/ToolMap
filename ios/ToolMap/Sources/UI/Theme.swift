import SwiftUI

/// The "Crête" palette of the Android app (ADR-074): petrol bars, turquoise accents, white pills and round buttons.
enum Crete {
    static let petrol = Color(hex: 0x163A4A)
    static let turquoise = Color(hex: 0x14918C)
    static let pill = Color(hex: 0x7FD6D1)
    static let surface = Color(hex: 0xF2F5F6)
    static let surfaceVariant = Color(hex: 0xE2ECEF)
    static let onSurface = Color(hex: 0x122027)
    static let onSurfaceVariant = Color(hex: 0x506169)
    static let outline = Color(hex: 0xD5DEE2)
    static let warning = Color(hex: 0x9A5B1E)
    static let barText = Color(hex: 0xC9D6DC)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// A white pill with a light shadow, the shape of the information boxes on the map (P29.1, variant E "Pastilles").
struct PillBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
    }
}

extension View {
    func pill() -> some View { modifier(PillBackground()) }
}

/// The round white map buttons (zoom, compass): 44 points, petrol sign.
struct RoundMapButton: View {
    let systemImage: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Crete.petrol)
                .frame(width: 44, height: 44)
                .background(Color.white, in: Circle())
                .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
        }
        .accessibilityLabel(label)
    }
}
