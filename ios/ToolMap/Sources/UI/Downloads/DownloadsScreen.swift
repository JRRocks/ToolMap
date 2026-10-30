import SwiftUI
import ToolMapCore
import UIKit

/// « Téléchargements » (Android DOWNLOADS, P29.7 « Poids », ADR-080): the job going first, in large on the colour of the
/// tiles (its first map, « 51 % », Pause, Retirer), the other jobs waiting or interrupted just under it, then « En
/// pause » (with the failures and their message in red) and « Terminés ». « Nouvelle zone » opens the selection on the
/// map; « Retirer » offers « Annuler ».
struct DownloadsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var removed: ZoneJob?

    var body: some View {
        NavigationStack {
            DownloadsList(onRemove: remove)
                .navigationTitle("Téléchargements")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        ScreenCloseButton()
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    bottom
                }
        }
        .onAppear {
            model.downloads.start()
        }
    }

    private var bottom: some View {
        VStack(alignment: .trailing, spacing: 8) {
            if let removed {
                DownloadsMessageBar(
                    text: "« \(removed.request.name) » retirée de la liste. Les tuiles restent sur le téléphone.",
                    action: "Annuler",
                    perform: { model.downloads.restore(removed) },
                    onDismiss: { self.removed = nil }
                )
            }
            Button {
                model.downloads.askZone(only: nil)
            } label: {
                Label("Nouvelle zone", systemImage: "viewfinder")
                    .font(.headline)
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
                    .background(Crete.petrol, in: Capsule())
                    .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func remove(_ job: ZoneJob) {
        model.downloads.remove(job.request.id)
        removed = job
    }
}

/// The jobs in groups (Android `DownloadScreen`).
private struct DownloadGroups {
    let going: ZoneJob?
    let waiting: [ZoneJob]
    let stopped: [ZoneJob]
    let done: [ZoneJob]

    init(_ jobs: [ZoneJob]) {
        let running = jobs.first(where: { $0.status == .running })
        let first = running ?? jobs.first(where: { $0.status == .queued || $0.status == .interrupted })
        going = first
        let goingId = first?.request.id
        waiting = jobs.filter { job in
            job.request.id != goingId
                && (job.status == .queued || job.status == .interrupted || job.status == .running)
        }
        stopped = jobs.filter { $0.status == .paused || $0.status == .failed }
        done = jobs.filter { $0.status == .done }
    }
}

private struct DownloadsList: View {
    @Environment(AppModel.self) private var model
    let onRemove: (ZoneJob) -> Void

    var body: some View {
        let jobs = model.downloads.jobs
        let groups = DownloadGroups(jobs)
        List {
            if model.offline {
                DownloadsOfflineSection()
            }
            if jobs.isEmpty {
                DownloadsEmptySection()
            }
            if let going = groups.going {
                Section {
                    DownloadGoingJob(job: going, onRemove: { onRemove(going) })
                        .listRowBackground(Crete.tile)
                }
            }
            if !groups.waiting.isEmpty {
                Section {
                    ForEach(groups.waiting, id: \.request.id) { job in
                        DownloadJobRow(job: job, onRemove: { onRemove(job) })
                    }
                }
            }
            if !groups.stopped.isEmpty {
                Section {
                    ForEach(groups.stopped, id: \.request.id) { job in
                        DownloadJobRow(job: job, onRemove: { onRemove(job) })
                    }
                } header: {
                    Text("En pause").textCase(nil)
                }
            }
            if !groups.done.isEmpty {
                Section {
                    ForEach(groups.done, id: \.request.id) { job in
                        DownloadJobRow(job: job, onRemove: { onRemove(job) })
                    }
                } header: {
                    Text("Terminés").textCase(nil)
                }
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// « Le mode hors ligne est actif » and « Repasser en ligne ».
private struct DownloadsOfflineSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(ZoneText.offlineWarning)
                    .foregroundStyle(Crete.onSurface)
                Button("Repasser en ligne") {
                    model.offline = false
                }
                .buttonStyle(.bordered)
                .tint(Crete.petrol)
            }
            .padding(.vertical, 4)
        }
    }
}

/// Nothing yet: how a zone is saved, and the way there.
private struct DownloadsEmptySection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text("Aucun téléchargement pour l'instant.")
                    .font(.headline)
                    .foregroundStyle(Crete.onSurface)
                Text(ZoneText.emptyHint)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
                Button("Sélectionner et enregistrer une zone") {
                    model.downloads.askZone(only: nil)
                }
                .buttonStyle(.borderedProminent)
                .tint(Crete.turquoise)
            }
            .padding(.vertical, 6)
        }
    }
}

/// The job going, in large: its map, its percentage, its bar, Pause and Retirer.
private struct DownloadGoingJob: View {
    @Environment(AppModel.self) private var model
    let job: ZoneJob
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                if let first = job.request.sourceIds.first {
                    ZoneThumbnail(sourceId: first, request: job.request)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(job.request.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Crete.onSurface)
                    Text(DownloadText.mapsAndZooms(job.request))
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Spacer(minLength: 4)
                Text(DownloadText.percent(job.progress.fraction))
                    .font(.title2.weight(.medium))
                    .foregroundStyle(Crete.onSurface)
            }
            ProgressView(value: Double(job.progress.fraction))
                .tint(Crete.turquoise)
            Text(DownloadText.status(job.status) + " · " + DownloadText.progressLine(job))
                .font(.subheadline)
                .foregroundStyle(Crete.onSurface)
            if let message = job.message {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Crete.danger)
            }
            HStack(spacing: 8) {
                Button {
                    model.downloads.pause(job.request.id)
                } label: {
                    Label("Pause", systemImage: "pause.fill")
                }
                .buttonStyle(.bordered)
                .tint(Crete.petrol)
                Button("Retirer", action: onRemove)
                    .buttonStyle(.borderless)
                    .tint(Crete.petrol)
            }
        }
        .padding(.vertical, 6)
    }
}

/// A job as a row of a block: the icon of its state, its name, play or pause, the ⋮ with « Retirer de la liste »; under
/// it the counts and the maps, a thin bar while it is not done, the message of a failure in red.
private struct DownloadJobRow: View {
    @Environment(AppModel.self) private var model
    let job: ZoneJob
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) {
                stateIcon
                Text(job.request.name)
                    .lineLimit(1)
                    .foregroundStyle(Crete.onSurface)
                Spacer(minLength: 4)
                playButton
                Menu {
                    Button(role: .destructive, action: onRemove) {
                        Label("Retirer de la liste", systemImage: "trash")
                        Text("Les tuiles restent sur le téléphone")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                }
                .accessibilityLabel("Actions sur « \(job.request.name) »")
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(DownloadText.detail(job))
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                if job.status != .done, job.status != .failed {
                    ProgressView(value: Double(job.progress.fraction))
                        .tint(Crete.onSurfaceVariant)
                }
                if let message = job.message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(Crete.danger)
                }
            }
            .padding(.leading, 36)
        }
        .padding(.vertical, 2)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive, action: onRemove) {
                Label("Retirer", systemImage: "trash")
            }
        }
    }

    private var stateIcon: some View {
        let icon = DownloadText.icon(job.status)
        return Image(systemName: icon.name)
            .font(.title3)
            .foregroundStyle(icon.color)
            .frame(width: 24)
            .accessibilityLabel(DownloadText.status(job.status))
    }

    @ViewBuilder
    private var playButton: some View {
        switch job.status {
        case .paused, .failed:
            Button {
                model.downloads.resume(job.request.id)
            } label: {
                Image(systemName: "play.fill")
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Reprendre")
        case .queued, .running, .interrupted:
            Button {
                model.downloads.pause(job.request.id)
            } label: {
                Image(systemName: "pause.fill")
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Pause")
        case .done:
            EmptyView()
        }
    }
}

/// A tile of the first map of a job, at the middle of its zone (Android `MapThumbnail`): from the phone when it is
/// stored, else from the server (nothing offline).
struct ZoneThumbnail: View {
    let sourceId: String
    let request: ZoneRequest
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10)
                .fill(Crete.surfaceVariant)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "map")
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .frame(width: 48, height: 48)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .accessibilityHidden(true)
        .task(id: request.id) {
            await load()
        }
    }

    private func load() async {
        guard let source = TileService.shared.source(sourceId) else { return }
        let center = ZoneThumbnail.center(request.shape)
        let low = source.minZoom ?? 0
        let high = source.maxZoom ?? 0
        let zoom = min(max(min(request.maxZoom, 13), low), max(high, low))
        guard let tile = try? TileMath.tileAt(center.lon, center.lat, zoom) else { return }
        let id = sourceId
        let data: Data? = await withCheckedContinuation { continuation in
            TileService.shared.load(id, zoom, tile.x, tile.y) { bytes, _ in
                continuation.resume(returning: bytes)
            }
        }
        if let data, let picture = UIImage(data: data) {
            image = picture
        }
    }

    static func center(_ shape: ZoneShape) -> LonLat {
        switch shape {
        case let .rectangle(box):
            return LonLat(lon: (box.west + box.east) / 2, lat: (box.south + box.north) / 2)
        case let .polygon(polygon):
            return middle(polygon.points)
        case let .corridor(band):
            guard !band.points.isEmpty else { return LonLat(lon: 0, lat: 0) }
            let point = band.points[band.points.count / 2]
            return LonLat(lon: point.lon, lat: point.lat)
        }
    }

    private static func middle(_ points: [ZoneShape.Point]) -> LonLat {
        guard !points.isEmpty else { return LonLat(lon: 0, lat: 0) }
        let lon = points.reduce(0.0) { $0 + $1.lon } / Double(points.count)
        let lat = points.reduce(0.0) { $0 + $1.lat } / Double(points.count)
        return LonLat(lon: lon, lat: lat)
    }
}
