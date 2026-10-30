import SwiftUI
import ToolMapCore

/// Colours of the print panels that the "Crête" palette does not name (Android Material roles of the light theme).
enum PrintColors {
    /// The chosen sheet and chip (Android `secondaryContainer`).
    static let secondaryContainer = Color(hex: 0xD3EEEC)
    static let onSecondaryContainer = Color(hex: 0x0B5B57)
    /// The box of a heavy PDF (Android `tertiaryContainer`).
    static let tertiaryContainer = Color(hex: 0xFBDCC2)
    static let onTertiaryContainer = Color(hex: 0x3A1E05)
    /// Edges of the fields and of the outlined buttons (Android `outline`).
    static let outline = Color(hex: 0xB7C6CC)
}

/// The panel of the print (Android `ToolPanel` with its footer): the petrol band with its title, ← to come back to the
/// main settings and ✕ when the step has them, the content, then the actions, which stay at the foot while the
/// settings scroll. White and rounded like the other tool panels (`ToolPanel`).
struct PrintChrome<Content: View, Footer: View>: View {
    let title: String
    let subtitle: String?
    let onBack: (() -> Void)?
    let onClose: (() -> Void)?
    let content: () -> Content
    let footer: () -> Footer

    init(
        title: String,
        subtitle: String? = nil,
        onBack: (() -> Void)? = nil,
        onClose: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content,
        @ViewBuilder footer: @escaping () -> Footer
    ) {
        self.title = title
        self.subtitle = subtitle
        self.onBack = onBack
        self.onClose = onClose
        self.content = content
        self.footer = footer
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            content()
            footer()
        }
        .background(Color.white, in: RoundedRectangle(cornerRadius: 18))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: .black.opacity(0.18), radius: 8, y: 2)
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 0) {
            if let onBack {
                Button(action: onBack) {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Retour aux réglages")
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(Color.white)
                if let subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(Crete.barText)
                }
            }
            .padding(.leading, onBack == nil ? 16 : 0)
            Spacer(minLength: 8)
            if let onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Fermer")
            }
        }
        .frame(minHeight: 52)
        .padding(.trailing, 4)
        .padding(.vertical, 2)
        .background(Crete.petrol)
    }
}

/// A scroll view as tall as its content, up to `maxHeight`: a short panel stays short, a long one scrolls.
struct PrintFittedScroll<Content: View>: View {
    let maxHeight: CGFloat
    let content: () -> Content
    @State private var height: CGFloat = 0

    init(maxHeight: CGFloat, @ViewBuilder content: @escaping () -> Content) {
        self.maxHeight = maxHeight
        self.content = content
    }

    var body: some View {
        ScrollView(.vertical) {
            content()
                .background(
                    GeometryReader { proxy in
                        Color.clear
                            .onAppear { height = proxy.size.height }
                            .onChange(of: proxy.size.height) { _, value in height = value }
                    }
                )
        }
        .frame(height: Swift.min(Swift.max(height, 1), maxHeight))
        .scrollBounceBehavior(.basedOnSize)
    }
}

/// « Annuler » in outline and the main action filled, side by side (Android `CancelNext`).
struct PrintButtonRow: View {
    let cancelTitle: String
    let mainTitle: String
    var mainEnabled = true
    let onCancel: () -> Void
    let onMain: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            PrintButton(title: cancelTitle, action: onCancel)
            PrintButton(title: mainTitle, filled: true, enabled: mainEnabled, action: onMain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }
}

/// A button of the panels: filled petrol for the main action, outlined for the other.
struct PrintButton: View {
    let title: String
    var filled = false
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundStyle(filled ? Color.white : Crete.petrol)
                .background(filled ? Crete.petrol : Color.clear, in: Capsule())
                .overlay { Capsule().strokeBorder(filled ? Color.clear : PrintColors.outline, lineWidth: 1) }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

/// A choice among a few (scales, resolutions): checked and tinted when chosen (Android `FilterChip`).
struct PrintChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.caption.weight(.bold))
                }
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .frame(height: 32)
            .foregroundStyle(selected ? PrintColors.onSecondaryContainer : Crete.onSurface)
            .background(selected ? PrintColors.secondaryContainer : Color.clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(selected ? Color.clear : PrintColors.outline, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
    }
}

/// A field that opens its menu when touched (« Grille », « Pas de la grille »).
struct PrintMenuField<Items: View>: View {
    let label: String
    let value: String
    let items: () -> Items

    init(label: String, value: String, @ViewBuilder items: @escaping () -> Items) {
        self.label = label
        self.value = value
        self.items = items
    }

    var body: some View {
        Menu {
            items()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                    Text(value)
                        .font(.body)
                        .foregroundStyle(Crete.onSurface)
                }
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .overlay { RoundedRectangle(cornerRadius: 8).strokeBorder(PrintColors.outline, lineWidth: 1) }
            .contentShape(Rectangle())
        }
    }
}

/// A text field with its name above it (Android `OutlinedTextField`); red edge when `valid` is false.
struct PrintTextField: View {
    let label: String
    @Binding var text: String
    var suffix: String?
    var valid = true
    var keyboard: UIKeyboardType = .default

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(valid ? Crete.onSurfaceVariant : Crete.danger)
            HStack(spacing: 6) {
                TextField(label, text: $text)
                    .keyboardType(keyboard)
                    .autocorrectionDisabled()
                if let suffix {
                    Text(suffix)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(valid ? PrintColors.outline : Crete.danger, lineWidth: valid ? 1 : 2)
            }
        }
    }
}

/// A switch with its name and an optional hint under it.
struct PrintSwitchRow: View {
    let title: String
    var hint: String?
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(Crete.onSurface)
                if let hint {
                    Text(hint)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        }
        .tint(Crete.turquoise)
        .frame(minHeight: 44)
    }
}

/// A line that opens a page of the settings: its name, what it holds now, the chevron.
struct PrintNavRow: View {
    let title: String
    let summary: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(Crete.onSurface)
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// « Sur la carte », « Feuille »: the name of a group of settings.
struct PrintGroupLabel: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.footnote.weight(.medium))
            .foregroundStyle(Crete.onSurfaceVariant)
            .padding(.top, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// « Échelle », « Résolution ».
struct PrintSectionTitle: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Crete.petrol)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The orange box of a heavy PDF (P17.9, form Q2 of docs/previews/p17-impression-qualite.html).
struct PrintHeavyBox: View {
    let title: String
    let text: String
    var actionTitle: String?
    var onAction: () -> Void = {}

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(text)
                    .font(.footnote)
                    .fixedSize(horizontal: false, vertical: true)
                if let actionTitle {
                    Button(actionTitle, action: onAction)
                        .font(.subheadline.weight(.semibold))
                        .buttonStyle(.plain)
                        .padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(PrintColors.onTertiaryContainer)
        .padding(12)
        .background(PrintColors.tertiaryContainer, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// « Autre échelle… »: the denominator typed, from 1:500 to 1:10 000 000.
struct PrintScaleAlert: ViewModifier {
    @Binding var isPresented: Bool
    let printer: PrintController
    @State private var text = ""

    func body(content: Content) -> some View {
        content
            .alert("Autre échelle…", isPresented: $isPresented) {
                TextField("Échelle 1 :", text: $text)
                    .keyboardType(.numberPad)
                Button("Annuler", role: .cancel) {}
                Button("OK") {
                    if let value = PrintWords.scaleValue(text) {
                        printer.update { $0.scale = value }
                    }
                }
            }
            .onChange(of: isPresented) { _, shown in
                if shown {
                    text = String(Int(printer.template.scale.rounded()))
                }
            }
    }
}

/// « Autre… » of the format pill: width and height of the sheet, 50 mm at least, no largest.
struct PrintPaperAlert: ViewModifier {
    @Binding var isPresented: Bool
    let printer: PrintController
    @State private var width = ""
    @State private var height = ""

    func body(content: Content) -> some View {
        content
            .alert("Format personnalisé", isPresented: $isPresented) {
                TextField("Largeur (mm)", text: $width)
                    .keyboardType(.decimalPad)
                TextField("Hauteur (mm)", text: $height)
                    .keyboardType(.decimalPad)
                Button("Annuler", role: .cancel) {}
                Button("OK") { apply() }
            } message: {
                Text("Largeur et hauteur de la feuille, en millimètres (A2 : 420 × 594, Legal : 216 × 356).")
            }
            .onChange(of: isPresented) { _, shown in
                if shown {
                    width = String(Int(printer.template.page.customWidthMm.rounded()))
                    height = String(Int(printer.template.page.customHeightMm.rounded()))
                }
            }
    }

    private func apply() {
        guard let w = PrintWords.millimetres(width), let h = PrintWords.millimetres(height),
              PageSetup.isCustomSide(w), PageSetup.isCustomSide(h)
        else { return }
        printer.update {
            $0.page.paper = .custom
            $0.page.customWidthMm = w
            $0.page.customHeightMm = h
        }
    }
}

extension View {
    func printScaleAlert(isPresented: Binding<Bool>, printer: PrintController) -> some View {
        modifier(PrintScaleAlert(isPresented: isPresented, printer: printer))
    }

    func printPaperAlert(isPresented: Binding<Bool>, printer: PrintController) -> some View {
        modifier(PrintPaperAlert(isPresented: isPresented, printer: printer))
    }
}

extension PrintController {
    /// A binding to one setting of the template; a change goes through `update` (new plan, new fit).
    func binding<Value>(_ keyPath: WritableKeyPath<PrintTemplate, Value>) -> Binding<Value> {
        Binding(
            get: { self.template[keyPath: keyPath] },
            set: { value in self.update { $0[keyPath: keyPath] = value } }
        )
    }
}
