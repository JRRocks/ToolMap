import SwiftUI
import ToolMapCore
import UIKit

// The rows of the settings (Android settings/SettingsRows.kt, P29.9): a grey icon, the name, its second line, and the
// control of the setting: a switch, a segmented control under the name, a slider in the row, a page of options, a
// colour. In the results of a search the second line starts with the category and the words found are in bold.

/// Where a row is shown: on the page of its category, or among the results of a search.
struct SettingsRowPlace {
    /// The category, at the start of the second line (search results only).
    var category: String?
    /// The words of the search, in bold.
    var terms: [String] = []

    static let page = SettingsRowPlace()
}

/// The text of a row with the words of the search in bold (`SettingsSearch.highlights`).
enum SettingsHighlight {
    static func text(_ string: String, _ terms: [String]) -> Text {
        if terms.isEmpty { return Text(string) }
        let ranges = SettingsSearch.highlights(string, terms)
        if ranges.isEmpty { return Text(string) }
        let units = Array(string.utf16)
        var result = Text("")
        var cursor = 0
        for range in ranges {
            let lower = max(range.lowerBound, cursor)
            let upper = min(range.upperBound + 1, units.count)
            if lower >= upper { continue }
            if lower > cursor {
                result = result + Text(piece(units, cursor, lower))
            }
            result = result + Text(piece(units, lower, upper)).bold()
            cursor = upper
        }
        if cursor < units.count {
            result = result + Text(piece(units, cursor, units.count))
        }
        return result
    }

    private static func piece(_ units: [UInt16], _ from: Int, _ to: Int) -> String {
        String(decoding: units[from ..< to], as: UTF16.self)
    }

    /// The second line in a search: « Affichage · Boutons plus grands… », or the category alone.
    static func secondLine(_ subtitle: String?, _ place: SettingsRowPlace) -> String? {
        guard let category = place.category else { return subtitle }
        guard let subtitle, !subtitle.isEmpty else { return category }
        return "\(category) · \(subtitle)"
    }
}

/// The icon, the name and the second line of a row.
struct SettingsEntryLabel: View {
    let icon: String
    let title: String
    let subtitle: String?
    var place: SettingsRowPlace = .page
    var iconColor: Color = Crete.onSurfaceVariant

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(iconColor)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                SettingsHighlight.text(title, place.terms)
                    .foregroundStyle(Crete.onSurface)
                if let line = SettingsHighlight.secondLine(subtitle, place) {
                    SettingsHighlight.text(line, place.terms)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        }
    }
}

/// One setting, with the control its kind asks for.
struct SettingsEntryRow: View {
    let item: SettingsEntry
    var place: SettingsRowPlace = .page

    var body: some View {
        switch item.kind {
        case let .toggle(read, write):
            SettingsEntryToggle(item: item, place: place, read: read, write: write)
        case let .segmented(options, read, write):
            SettingsEntrySegmented(item: item, place: place, options: options, read: read, write: write)
        case let .choice(options, question, read, write):
            SettingsEntryChoice(item: item, place: place, options: options, question: question, read: read, write: write)
        case let .slider(range, step, label, read, write):
            SettingsEntrySlider(item: item, place: place, range: range, step: step, label: label, read: read, write: write)
        case let .color(read, write):
            SettingsEntryColor(item: item, place: place, read: read, write: write)
        case .backupLink:
            NavigationLink {
                BackupPage()
            } label: {
                SettingsEntryLabel(icon: item.icon, title: item.title, subtitle: nil, place: place)
            }
        }
    }
}

struct SettingsEntryToggle: View {
    @Environment(AppModel.self) private var model
    let item: SettingsEntry
    let place: SettingsRowPlace
    let read: (AppSettings) -> Bool
    let write: (inout AppSettings, Bool) -> Void

    var body: some View {
        let binding = Binding<Bool>(
            get: { read(model.settings) },
            set: { value in model.settingsStore.update { write(&$0, value) } }
        )
        Toggle(isOn: binding) {
            SettingsEntryLabel(icon: item.icon, title: item.title, subtitle: item.subtitle(model.settings), place: place)
        }
        .tint(Crete.turquoise)
    }
}

struct SettingsEntrySegmented: View {
    @Environment(AppModel.self) private var model
    let item: SettingsEntry
    let place: SettingsRowPlace
    let options: [SettingsOption]
    let read: (AppSettings) -> String
    let write: (inout AppSettings, String) -> Void

    var body: some View {
        let binding = Binding<String>(
            get: { read(model.settings) },
            set: { value in model.settingsStore.update { write(&$0, value) } }
        )
        VStack(alignment: .leading, spacing: 10) {
            SettingsEntryLabel(icon: item.icon, title: item.title, subtitle: item.subtitle(model.settings), place: place)
            Picker(item.title, selection: binding) {
                ForEach(options) { option in
                    Text(option.short).tag(option.id)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
        .padding(.vertical, 4)
    }
}

struct SettingsEntryChoice: View {
    @Environment(AppModel.self) private var model
    let item: SettingsEntry
    let place: SettingsRowPlace
    let options: [SettingsOption]
    let question: String?
    let read: (AppSettings) -> String
    let write: (inout AppSettings, String) -> Void

    var body: some View {
        NavigationLink {
            SettingsChoicePage(title: item.title, options: options, question: question, read: read, write: write)
        } label: {
            SettingsEntryLabel(icon: item.icon, title: item.title, subtitle: item.subtitle(model.settings), place: place)
        }
    }
}

/// The options of a choice on a page of their own (Android: the dialog of the setting); a tap chooses and goes back.
struct SettingsChoicePage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let title: String
    let options: [SettingsOption]
    let question: String?
    let read: (AppSettings) -> String
    let write: (inout AppSettings, String) -> Void

    var body: some View {
        let chosen = read(model.settings)
        List {
            Section {
                ForEach(options) { option in
                    Button {
                        model.settingsStore.update { write(&$0, option.id) }
                        dismiss()
                    } label: {
                        ChoiceOptionLabel(option: option, chosen: option.id == chosen)
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                if let question {
                    Text(question)
                        .textCase(nil)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .settingsNavigationBar()
    }
}

private struct ChoiceOptionLabel: View {
    let option: SettingsOption
    let chosen: Bool

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(option.full)
                    .foregroundStyle(Crete.onSurface)
                if let detail = option.detail {
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            Spacer(minLength: 8)
            if chosen {
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Crete.turquoise)
            }
        }
        .contentShape(Rectangle())
        .accessibilityAddTraits(chosen ? [.isSelected] : [])
    }
}

/// A value on a slider in the row (Mode nuit, Zooms manquants, the radii of the alerts): written when the finger
/// leaves the slider, so that the settings file is not written at every step.
struct SettingsEntrySlider: View {
    @Environment(AppModel.self) private var model
    let item: SettingsEntry
    let place: SettingsRowPlace
    let range: ClosedRange<Int>
    let step: Int
    let label: (Int, AppSettings) -> String
    let read: (AppSettings) -> Int
    let write: (inout AppSettings, Int) -> Void

    @State private var draft: Double?
    @State private var editing = false

    var body: some View {
        let settings = model.settings
        let current = draft ?? Double(read(settings))
        let value = Int(current.rounded())
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                SettingsEntryLabel(icon: item.icon, title: item.title, subtitle: nil, place: place)
                Spacer(minLength: 8)
                Text(label(value, settings))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Crete.petrol)
            }
            Slider(
                value: Binding<Double>(
                    get: { current },
                    set: { newValue in
                        draft = newValue
                        if !editing { commit(newValue) }
                    }
                ),
                in: Double(range.lowerBound) ... Double(range.upperBound),
                step: Double(step),
                onEditingChanged: { isEditing in
                    editing = isEditing
                    if !isEditing, let last = draft { commit(last) }
                }
            )
            .tint(Crete.turquoise)
            .accessibilityLabel(item.title)
            .accessibilityValue(label(value, settings))
            if let line = SettingsHighlight.secondLine(item.hint, place) {
                SettingsHighlight.text(line, place.terms)
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .padding(.vertical, 4)
    }

    private func commit(_ newValue: Double) {
        let rounded = Int(newValue.rounded())
        model.settingsStore.update { write(&$0, rounded) }
        draft = nil
    }
}

struct SettingsEntryColor: View {
    @Environment(AppModel.self) private var model
    let item: SettingsEntry
    let place: SettingsRowPlace
    let read: (AppSettings) -> Int32
    let write: (inout AppSettings, Int32) -> Void

    var body: some View {
        let binding = Binding<Color>(
            get: { SettingsColor.color(read(model.settings)) },
            set: { color in
                let argb = SettingsColor.argb(color)
                model.settingsStore.update { write(&$0, argb) }
            }
        )
        ColorPicker(selection: binding, supportsOpacity: false) {
            SettingsEntryLabel(icon: item.icon, title: item.title, subtitle: item.subtitle(model.settings), place: place)
        }
    }
}

/// ARGB integers of Android (`PathStyle`, `PlacemarkSettings`) and SwiftUI colours.
enum SettingsColor {
    static func color(_ argb: Int32) -> Color {
        let value = UInt32(bitPattern: argb)
        let alpha = Double((value >> 24) & 0xFF) / 255
        let red = Double((value >> 16) & 0xFF) / 255
        let green = Double((value >> 8) & 0xFF) / 255
        let blue = Double(value & 0xFF) / 255
        return Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha)
    }

    static func argb(_ color: Color) -> Int32 {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let a = byte(alpha) << 24
        let r = byte(red) << 16
        let g = byte(green) << 8
        let b = byte(blue)
        return Int32(bitPattern: a | r | g | b)
    }

    private static func byte(_ component: CGFloat) -> UInt32 {
        let scaled = (Double(component) * 255).rounded()
        return UInt32(min(max(scaled, 0), 255))
    }
}

extension View {
    /// The petrol bar of the settings pages (ADR-074, « Crête »): white title and buttons.
    func settingsNavigationBar() -> some View {
        toolbarBackground(Crete.petrol, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
    }
}
