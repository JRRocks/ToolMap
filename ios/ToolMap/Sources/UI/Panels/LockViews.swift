import SwiftUI

// « Masquer » and « Verrouiller » of the main menu (Android M2, M3, P29.2). While the controls are hidden, a white disc
// with the logo, like the zoom buttons, brings them back. While the screen is locked, the gestures on the map are off,
// the controls hidden, and a pill in the shape of the information pills says so; a long press on the screen unlocks it,
// so that a pocket does not.

/// What the map screen shows while its controls are hidden or the screen is locked; it fills the screen and places
/// itself (put it above `MapOverlayHost`).
struct LockOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.locked {
            ZStack(alignment: .bottom) {
                // Catches every touch: nothing under it is reachable while the screen is locked.
                Color.clear
                    .contentShape(Rectangle())
                    .onLongPressGesture(minimumDuration: 0.8) { unlock() }
                    .accessibilityHidden(true)
                LockedPill(onUnlock: unlock)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 28)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if model.controlsHidden {
            MenuLogoButton {
                model.controlsHidden = false
            }
            .padding(.trailing, 12)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        }
    }

    private func unlock() {
        model.locked = false
    }
}

/// The bar folded into its logo while the controls are hidden: the way back to them (P29.1, P29.2).
struct MenuLogoButton: View {
    let action: () -> Void

    var body: some View {
        RoundMapButton(systemImage: "mountain.2.fill", label: "Menu", action: action)
    }
}

/// Shown in place of the bar while the screen is locked, in the shape of the information pills: a long press unlocks.
struct LockedPill: View {
    let onUnlock: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Crete.turquoise)
            VStack(alignment: .leading, spacing: 2) {
                Text("Écran verrouillé")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.onSurface)
                Text("Maintenez l'écran pour le déverrouiller")
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
        .onLongPressGesture(minimumDuration: 0.8, perform: onUnlock)
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Déverrouiller l'écran", onUnlock)
    }
}
