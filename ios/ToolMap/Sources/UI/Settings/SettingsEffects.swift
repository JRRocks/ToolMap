import SwiftUI
import ToolMapCore
import UIKit

/// What three settings of « Affichage » change on the map screen (Android `MainActivity`): the theme, the status bar
/// hidden on the map, the screen kept on while the map is shown. Attached once to `MapScreen` (`.settingsEffects()`).
struct SettingsEffects: ViewModifier {
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        let settings = model.settings
        content
            .preferredColorScheme(SettingsEffects.scheme(settings.theme))
            .statusBarHidden(settings.hideStatusBar)
            .onAppear {
                UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
            }
            .onChange(of: settings.keepScreenOn) { _, on in
                UIApplication.shared.isIdleTimerDisabled = on
            }
    }

    static func scheme(_ theme: ThemeMode) -> ColorScheme? {
        switch theme {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

extension View {
    /// The theme, the status bar and the screen kept on, from the settings (`SettingsEffects`).
    func settingsEffects() -> some View {
        modifier(SettingsEffects())
    }
}
