import MapLibre
import ToolMapCore

/// The zone or band being saved for offline use (Android T5) needs no MapLibre layer: like Android's
/// `SaveAreaOverlay` and `SaveCorridorOverlay`, it is drawn above the map in SwiftUI (`ZoneFrameOverlay`), which can
/// dim everything but the inside of a band and puts the four handles under the finger. The set stays in
/// `MapFeatureRegistry` for its place in the order of the layers.
enum ZoneLayers {
    static let sets: [FeatureLayerSet] = []
}
