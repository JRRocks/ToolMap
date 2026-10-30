import SwiftUI
import ToolMapCore
import UIKit

/// The small picture of each map in the panels (Android `MapThumbnails`): one tile at the preview location of the map
/// (or around the map centre), asked through the same pipeline as the map itself. A tile already stored costs nothing;
/// a missing one is fetched once and stored like any other (nothing leaves the device in offline mode).
final class MapsThumbnails: @unchecked Sendable {
    static let shared = MapsThumbnails()

    private static let retryAfter: TimeInterval = 60
    private static let defaultZoom = 6
    private static let maxZoom = 12

    private let lock = NSLock()
    private var images: [String: UIImage] = [:]
    private var failedAt: [String: Date] = [:]

    func cached(_ id: String) -> UIImage? {
        lock.lock()
        defer { lock.unlock() }
        return images[id]
    }

    /// The thumbnail of `source`, or nil when there is none (yet): the caller draws the icon of its type.
    func load(_ source: TileSource, around: LonLatZoom?) async -> UIImage? {
        if let image = cached(source.id) { return image }
        guard source.isSupported, mayTry(source.id) else { return nil }
        var place = source.previewLocation ?? around
        if place == nil, let fallback = source.defaultLocation {
            place = LonLatZoom(lon: fallback.lon, lat: fallback.lat, zoom: 0)
        }
        let wanted = place?.zoom ?? MapsThumbnails.defaultZoom
        let low = source.minZoom ?? 0
        let high = source.maxZoom ?? MapsThumbnails.maxZoom
        let zoom = Swift.min(Swift.min(Swift.max(wanted, low), high), MapsThumbnails.maxZoom)
        let lon = place?.lon ?? PreviewTile.defaultLocation.lon
        let lat = place?.lat ?? PreviewTile.defaultLocation.lat
        guard let tile = try? TileMath.tileAt(lon, lat, zoom) else { return nil }
        let id = source.id
        let data: Data? = await withCheckedContinuation { (continuation: CheckedContinuation<Data?, Never>) in
            TileService.shared.load(id, zoom, tile.x, tile.y) { data, _ in
                continuation.resume(returning: data)
            }
        }
        let image = data.flatMap { UIImage(data: $0) }
        lock.lock()
        if let image {
            images[id] = image
            failedAt.removeValue(forKey: id)
        } else {
            failedAt[id] = Date()
        }
        lock.unlock()
        return image
    }

    private func mayTry(_ id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let failed = failedAt[id] else { return true }
        return Date().timeIntervalSince(failed) > MapsThumbnails.retryAfter
    }
}

/// The picture of a map (a tile of it), or, while there is none, the icon of its type on the tile colour (P29.3).
struct MapsThumbnail: View {
    @Environment(AppModel.self) private var model
    let source: TileSource?
    var size: CGFloat = 48
    var circle = false

    @State private var image: UIImage? = nil

    var body: some View {
        ZStack {
            Crete.tile
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: MapsText.typeIcon(source))
                    .font(.system(size: size * 0.4))
                    .foregroundStyle(Crete.petrol)
            }
        }
        .frame(width: size, height: size)
        .clipShape(circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 8)))
        .accessibilityHidden(true)
        .task(id: source?.id ?? "") {
            guard let source else { return }
            if let ready = MapsThumbnails.shared.cached(source.id) {
                image = ready
                return
            }
            let around = LonLatZoom(lon: model.center.lon, lat: model.center.lat, zoom: Int(model.maps.displayedZoom))
            image = await MapsThumbnails.shared.load(source, around: around)
        }
    }
}

/// A short message with an optional action, shown at the bottom of a maps page (Android snackbar of the panel and of
/// « Cartes disponibles »): the map's own message bar is hidden under the sheets.
struct MapsMessage: Identifiable {
    let id = UUID()
    let text: String
    var action: String?
    var perform: (() -> Void)?
}

struct MapsMessageBar: View {
    @Binding var message: MapsMessage?

    var body: some View {
        if let current = message {
            HStack(spacing: 12) {
                Text(current.text)
                    .font(.subheadline)
                    .foregroundStyle(Color.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let action = current.action {
                    Button(action) {
                        current.perform?()
                        message = nil
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.pill)
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: current.id) {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if message?.id == current.id {
                    message = nil
                }
            }
        }
    }
}

/// The petrol band of a maps panel (ADR-060): title, subtitle on up to two lines, and buttons at the end.
struct MapsBand<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let trailing: () -> Trailing

    init(title: String, subtitle: String? = nil, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .center, spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(Color.white)
                if let subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(Crete.barText)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
                .foregroundStyle(Color.white)
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .padding(.top, 22)
        .padding(.bottom, 12)
        .background(Crete.petrol)
    }
}

/// A round icon button of a petrol band.
struct MapsBandButton: View {
    let systemImage: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .medium))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
