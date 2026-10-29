// Port of p2p/src/main/kotlin/fr/toolmap/p2p/RoomState.kt (Android module :p2p).

/// What `RoomState` reports (Kotlin `RoomState.Sink`).
protocol RoomStateSink: AnyObject {
    func heartbeat(_ member: String, _ serverMs: Int64, _ name: String, _ color: Int32)

    func frame(_ member: String, _ bytes: [UInt8], _ serverMs: Int64, _ isPosition: Bool)

    /// The member removed its node: it left the room (or the room was emptied).
    func gone(_ member: String)
}

/// A node of the tree: its children in the order they came (Kotlin `LinkedHashMap`).
private final class RoomNode {
    private(set) var keys: [String] = []
    private var children: [String: RoomEntry] = [:]

    subscript(key: String) -> RoomEntry? { children[key] }

    func set(_ key: String, _ value: RoomEntry) {
        if children[key] == nil { keys.append(key) }
        children[key] = value
    }

    func remove(_ key: String) {
        if children.removeValue(forKey: key) != nil {
            keys.removeAll { $0 == key }
        }
    }

    var isEmpty: Bool { keys.isEmpty }
}

/// A child: a map of children or a leaf value.
private enum RoomEntry {
    case node(RoomNode)
    case leaf(P2PJson)

    var node: RoomNode? {
        if case let .node(n) = self { return n }
        return nil
    }

    var leaf: P2PJson? {
        if case let .leaf(v) = self { return v }
        return nil
    }
}

/// The mirror of the `b` node of a room, kept up to date from the `put` and `patch` events of the stream, and the
/// changes it turns into calls on a `RoomStateSink`: a member's heartbeat, a member's frame. Layout of a member `m`:
/// `h` = `{t, n, k}` (server time, name, colour), `p` = `{f, t}` (latest position frame), `c` = `{<id>: {f, t}}`
/// (control frames). `f` is the frame in Base64. Used by one thread at a time (the stream's).
final class RoomState {
    private let sink: any RoomStateSink
    private var root = RoomNode()
    private var lastBeat: [String: Int64] = [:]
    private var lastPos: [String: String] = [:]
    private var seenControl: [String: Set<String>] = [:]

    init(_ sink: any RoomStateSink) {
        self.sink = sink
    }

    /// `put`: replaces what is at `path` ("/" is the whole room) by `data`.
    func put(_ path: String, _ data: P2PJson) {
        let s = segments(path)
        set(s, convert(data))
        emit(affected(s))
    }

    /// `patch`: sets each child of `data` under `path`; a `null` child deletes it.
    func patch(_ path: String, _ data: P2PJsonObject) {
        let s = segments(path)
        for (k, v) in data.entries {
            set(s + segments(k), convert(v))
        }
        emit(affected(s))
    }

    private func segments(_ path: String) -> [String] {
        kotlinSplit(path, "/").filter { !$0.isEmpty }
    }

    private func affected(_ segments: [String]) -> [String] {
        segments.isEmpty ? root.keys : [segments[0]]
    }

    private func convert(_ e: P2PJson) -> RoomEntry? {
        switch e {
        case .null:
            return nil
        case let .object(o):
            let n = RoomNode()
            for (k, v) in o.entries {
                if let child = convert(v) { n.set(k, child) }
            }
            return n.isEmpty ? nil : .node(n)
        case .literal, .string:
            return .leaf(e)
        case .array:
            return nil
        }
    }

    private func set(_ segments: [String], _ value: RoomEntry?) {
        guard let last = segments.last else {
            root = value?.node ?? RoomNode()
            return
        }
        // nodes[i] is the map that holds segments[i].
        var nodes: [RoomNode] = [root]
        var node = root
        for s in segments.dropLast() {
            if let next = node[s]?.node {
                node = next
            } else {
                if value == nil { return }
                let fresh = RoomNode()
                node.set(s, .node(fresh))
                node = fresh
            }
            nodes.append(node)
        }
        if let value {
            node.set(last, value)
        } else {
            node.remove(last)
        }
        // An empty node does not exist in the database: prune it.
        var depth = nodes.count - 1
        while depth > 0, nodes[depth].isEmpty {
            nodes[depth - 1].remove(segments[depth - 1])
            depth -= 1
        }
    }

    private func emit(_ members: [String]) {
        var frames: [() -> Void] = []
        let sink = self.sink
        for member in members {
            guard let m = root[member]?.node else {
                if lastBeat.removeValue(forKey: member) != nil { sink.gone(member) }
                lastPos[member] = nil
                seenControl[member] = nil
                continue
            }
            if m["h"] == nil, lastBeat.removeValue(forKey: member) != nil { sink.gone(member) }
            if let h = m["h"]?.node, let t = h["t"]?.leaf?.longValue, lastBeat[member] != t {
                lastBeat[member] = t
                let name = h["n"]?.leaf?.stringValue ?? ""
                let color = h["k"]?.leaf?.longValue.map { Int32(truncatingIfNeeded: $0) } ?? 0
                sink.heartbeat(member, t, name, color)
            }
            if let p = m["p"]?.node, let f = p["f"]?.leaf?.stringValue, let t = p["t"]?.leaf?.longValue,
               lastPos[member] != "\(t):\(f)" {
                lastPos[member] = "\(t):\(f)"
                if let bytes = P2PBase64.decode(f) {
                    frames.append { sink.frame(member, bytes, t, true) }
                }
            }
            let control = m["c"]?.node
            var seen = (seenControl[member] ?? []).intersection(control?.keys ?? [])
            for id in control?.keys ?? [] {
                if !seen.insert(id).inserted { continue }
                guard let e = control?[id]?.node else { continue }
                guard let f = e["f"]?.leaf?.stringValue, let t = e["t"]?.leaf?.longValue else { continue }
                if let bytes = P2PBase64.decode(f) {
                    frames.append { sink.frame(member, bytes, t, false) }
                }
            }
            seenControl[member] = seen
        }
        for f in frames {
            f()
        }
    }
}
