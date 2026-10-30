import SwiftUI
import ToolMapCore

/// « Enregistrer une trace » (Android L4a, `record/RecordStartDialog.kt`, P29.5 « Sentier »): one sheet where there
/// were two. « Activité » (when « Demander l'activité » is on) in chips: the four last activities, the newest chosen in
/// advance, « Toutes… » unfolding the 33 of AlpineQuest; « Nom » shows the final name of the track (« Randonnée ·
/// Sortie du 27 oct. 2026 à 09:41 »); « Précision des points » says how often a point is kept, the last one used
/// chosen. Annuler · Démarrer.
struct RecordStartSheet: View {
    @Environment(\.dismiss) private var dismiss

    private let askActivity: Bool
    private let recent: [TrackActivity]
    private let date: String
    private let onStart: (String, RecorderProfile, TrackActivity?) -> Void

    @State private var name = ""
    @State private var profile: RecorderProfile
    @State private var activity: TrackActivity?
    @State private var allActivities = false

    /// `onStart` gets the name typed (the date one when empty), the precision and the activity (nil when not asked).
    init(
        askActivity: Bool,
        recent: [TrackActivity],
        initialActivity: TrackActivity?,
        initialProfile: RecorderProfile,
        onStart: @escaping (String, RecorderProfile, TrackActivity?) -> Void
    ) {
        self.askActivity = askActivity
        self.recent = recent
        self.onStart = onStart
        date = NameDates.format(Int64(Date().timeIntervalSince1970 * 1000))
        _profile = State(initialValue: initialProfile)
        _activity = State(initialValue: askActivity ? initialActivity : nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                if askActivity {
                    activitySection
                }
                nameSection
                profileSection
            }
            .navigationTitle("Enregistrer une trace")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Démarrer") {
                        start()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }

    // MARK: Sections

    private var activitySection: some View {
        let flow = RecordChipFlow(spacing: 8, lineSpacing: 8)
        return Section {
            flow {
                ForEach(chips, id: \.self) { option in
                    RecordActivityChip(activity: option, selected: option == activity) {
                        activity = activity == option ? nil : option
                    }
                }
                RecordMoreChip(unfolded: allActivities) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        allActivities.toggle()
                    }
                }
            }
            .padding(.vertical, 4)
            if allActivities {
                RecordActivityGrid(selected: activity) { picked in
                    activity = picked
                    withAnimation(.easeInOut(duration: 0.2)) {
                        allActivities = false
                    }
                }
            }
        } header: {
            Text("Activité")
        }
    }

    private var nameSection: some View {
        Section {
            TextField("Nom", text: $name, prompt: Text(finalName))
                .submitLabel(.done)
        } header: {
            Text("Nom")
        }
    }

    private var profileSection: some View {
        Section {
            ForEach(RecordText.profiles, id: \.self) { option in
                RecordProfileRow(profile: option, selected: option == profile) {
                    profile = option
                }
            }
        } header: {
            Text("Précision des points")
        }
    }

    // MARK: Values

    /// The chosen activity stays among the chips, even when it came from the full list.
    private var chips: [TrackActivity] {
        var list: [TrackActivity] = []
        if let activity {
            list.append(activity)
        }
        for item in recent where !list.contains(item) {
            list.append(item)
        }
        return Array(list.prefix(RecorderController.recentActivityCount))
    }

    /// The name typed, or « Sortie du … ».
    private var baseName: String {
        let typed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return typed.isEmpty ? "Sortie du \(date)" : typed
    }

    /// The name the track will get, activity included.
    private var finalName: String {
        guard let activity else { return baseName }
        return RecordText.named(activity, baseName)
    }

    private func start() {
        let chosenName = baseName
        let chosenProfile = profile
        let chosenActivity = activity
        dismiss()
        onStart(chosenName, chosenProfile, chosenActivity)
    }
}

/// An activity as a filter chip: its coloured icon, which a tick replaces once chosen.
private struct RecordActivityChip: View {
    let activity: TrackActivity
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .frame(width: 18, height: 18)
                } else {
                    RecordActivityIcon(activity: activity, size: 18)
                }
                Text(RecordText.activityName(activity))
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
            }
            .foregroundStyle(Crete.onSurface)
            .padding(.leading, 8)
            .padding(.trailing, 12)
            .frame(height: 32)
            .background(selected ? Crete.pill.opacity(0.45) : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay {
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(selected ? Color.clear : Crete.outline, lineWidth: 1)
            }
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// « Toutes… », or « Moins d'activités » once the grid is unfolded.
private struct RecordMoreChip: View {
    let unfolded: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "square.grid.2x2")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 18, height: 18)
                Text(unfolded ? "Moins d'activités" : "Toutes…")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
            }
            .foregroundStyle(Crete.onSurface)
            .padding(.leading, 8)
            .padding(.trailing, 12)
            .frame(height: 32)
            .background(unfolded ? Crete.pill.opacity(0.45) : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay {
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(unfolded ? Color.clear : Crete.outline, lineWidth: 1)
            }
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
    }
}

/// The 33 activities of AlpineQuest, four a row, in their order and their colours; names in 12 points.
private struct RecordActivityGrid: View {
    let selected: TrackActivity?
    let onSelect: (TrackActivity) -> Void

    private var columns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 4), count: 4)
    }

    var body: some View {
        LazyVGrid(columns: columns, spacing: 4) {
            ForEach(TrackActivity.allCases, id: \.self) { activity in
                RecordActivityCell(activity: activity, selected: activity == selected) {
                    onSelect(activity)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct RecordActivityCell: View {
    let activity: TrackActivity
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                RecordActivityIcon(activity: activity, size: 34)
                Text(RecordText.activityName(activity))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Crete.onSurface)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, minHeight: 30, alignment: .top)
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 2)
            .background(selected ? Crete.pill.opacity(0.45) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A precision of the points: its name, how often a point is kept, a tick when chosen.
private struct RecordProfileRow: View {
    let profile: RecorderProfile
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(RecordText.profileName(profile))
                        .foregroundStyle(Crete.onSurface)
                    Text(RecordText.profileDetail(profile))
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Crete.turquoise)
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Chips laid out in lines, wrapping at the width (Compose's `FlowRow`).
struct RecordChipFlow: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var lineHeight: CGFloat = 0
        var widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                y += lineHeight + lineSpacing
                x = 0
                lineHeight = 0
            }
            x += size.width
            widest = Swift.max(widest, x)
            x += spacing
            lineHeight = Swift.max(lineHeight, size.height)
        }
        let total = y + lineHeight
        return CGSize(width: proposal.width ?? widest, height: total)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                y += lineHeight + lineSpacing
                x = bounds.minX
                lineHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = Swift.max(lineHeight, size.height)
        }
    }
}
