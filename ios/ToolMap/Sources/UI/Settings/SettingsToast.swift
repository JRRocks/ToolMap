import SwiftUI

/// A short message at the bottom of a page (Android snackbar), with an optional action; it goes after 4 s. The map's
/// own snackbar (`AppModel.say`) is under the full-screen pages, so the settings and the backup have this one.
struct SettingsToast: View {
    let text: String
    let action: String?
    /// A new id starts the 4 s again.
    let id: UUID
    let onAction: () -> Void
    let onTimeout: () -> Void

    init(
        text: String,
        action: String? = nil,
        id: UUID,
        onAction: @escaping () -> Void = {},
        onTimeout: @escaping () -> Void
    ) {
        self.text = text
        self.action = action
        self.id = id
        self.onAction = onAction
        self.onTimeout = onTimeout
    }

    var body: some View {
        HStack(spacing: 12) {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let action {
                Button(action, action: onAction)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.pill)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .shadow(color: .black.opacity(0.2), radius: 6, y: 2)
        .task(id: id) {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled {
                onTimeout()
            }
        }
    }
}
