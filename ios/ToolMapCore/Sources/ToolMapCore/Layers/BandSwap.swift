// Port of core/src/main/kotlin/fr/toolmap/core/layers/BandSwap.kt.

/// A layer whose elevation band changes is rebuilt on a new style source (P20, `StyleSources`): until the new tiles are
/// drawn, its previous state (a "ghost") stays below it so that the map does not go blank. Pure rules of which layers
/// get one and which lose it.
public enum BandSwap {
    /// Layers of `current`, as they are now, that `desired` moves to another band of the same map while they are
    /// drawn: they get a ghost, unless one is in `ghosted` already (a layer changed again while it loads keeps its
    /// first drawing).
    public static func newGhosts(
        _ current: [AppliedLayer],
        _ desired: [AppliedLayer],
        _ ghosted: Set<String>
    ) -> [AppliedLayer] {
        let wanted = byId(desired)
        return current.filter { (old: AppliedLayer) -> Bool in
            guard let new = wanted[old.id] else { return false }
            return new.sourceId != old.sourceId
                && StyleSources.sourceOf(new.sourceId) == StyleSources.sourceOf(old.sourceId)
                && old.props.visible
                && !ghosted.contains(old.id)
        }
    }

    /// Ghosts of `ghosts` to remove at once: their layer is gone, or back on the very source the ghost draws.
    public static func stale(_ ghosts: [String: AppliedLayer], _ desired: [AppliedLayer]) -> Set<String> {
        let wanted = byId(desired)
        var result = Set<String>()
        for (id, ghost) in ghosts {
            let source = wanted[id]?.sourceId
            if source == nil || source == ghost.sourceId { result.insert(id) }
        }
        return result
    }

    /// Kotlin `associateBy { it.id }`: the last layer of an id wins.
    private static func byId(_ layers: [AppliedLayer]) -> [String: AppliedLayer] {
        var map: [String: AppliedLayer] = [:]
        for layer in layers {
            map[layer.id] = layer
        }
        return map
    }
}
