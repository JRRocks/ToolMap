import Foundation
import Observation
import ToolMapCore

/// The settings of the app (Android `SettingsRepository`): one JSON file in Application Support, written with the
/// keys of Android (`AppSettingsCodec`), so that a backup can go from one app to the other.
@Observable
final class SettingsStore {
    var settings: AppSettings {
        didSet {
            if settings != oldValue { save() }
        }
    }

    @ObservationIgnored private let file: URL

    init(file: URL = AppFiles.support.appendingPathComponent("settings.json")) {
        self.file = file
        let text = try? String(contentsOf: file, encoding: .utf8)
        settings = AppSettingsCodec.decodeOrDefault(text)
    }

    /// Changes several settings at once (one save).
    func update(_ change: (inout AppSettings) -> Void) {
        var copy = settings
        change(&copy)
        settings = copy
    }

    /// « Rétablir les réglages par défaut »; returns the settings before, for « Annuler ».
    @discardableResult
    func reset() -> AppSettings {
        let before = settings
        settings = AppSettings()
        return before
    }

    private func save() {
        guard let text = try? AppSettingsCodec.encode(settings) else { return }
        try? text.write(to: file, atomically: true, encoding: .utf8)
    }
}
