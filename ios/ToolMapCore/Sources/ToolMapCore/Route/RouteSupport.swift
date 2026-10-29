// Small helpers shared by the ports of route/, alert/ and record/ (core/src/main/kotlin/fr/toolmap/core/).

import Foundation

/// Kotlin number behaviours the route, alert and record ports rely on.
enum RouteMath {
    /// Kotlin `Double.coerceIn(min, max)`: below `min` gives `min`, above `max` gives `max`; NaN stays NaN.
    static func coerce(_ value: Double, _ min: Double, _ max: Double) -> Double {
        if value < min { return min }
        if value > max { return max }
        return value
    }

    /// Kotlin `Int.coerceIn(min, max)`.
    static func coerce(_ value: Int, _ min: Int, _ max: Int) -> Int {
        if value < min { return min }
        if value > max { return max }
        return value
    }

    /// Kotlin `Double.roundToInt()` (Java `Math.round`, then saturated to the 32-bit range).
    static func roundToInt(_ value: Double) -> Int {
        coerce(kotlinRound(value), Int(Int32.min), Int(Int32.max))
    }
}

/// A map that forgets its least recently used entry beyond `capacity`: Java's `LinkedHashMap` in access order, as the
/// Kotlin caches of computed legs and elevations use it (a read or a write moves the entry to the newest end).
struct RouteLru<Key: Hashable, Value> {
    let capacity: Int
    private var values: [Key: Value] = [:]
    /// Oldest first.
    private var order: [Key] = []

    init(capacity: Int) {
        self.capacity = capacity
    }

    var count: Int { values.count }

    mutating func get(_ key: Key) -> Value? {
        guard let value = values[key] else { return nil }
        touch(key)
        return value
    }

    mutating func put(_ key: Key, _ value: Value) {
        if values.updateValue(value, forKey: key) != nil {
            touch(key)
        } else {
            order.append(key)
        }
        while values.count > capacity, !order.isEmpty {
            let eldest = order.removeFirst()
            values.removeValue(forKey: eldest)
        }
    }

    private mutating func touch(_ key: Key) {
        if let index = order.firstIndex(of: key) {
            order.remove(at: index)
        }
        order.append(key)
    }
}
