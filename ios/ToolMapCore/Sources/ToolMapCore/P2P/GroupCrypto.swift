// Port of core/src/main/kotlin/fr/toolmap/core/p2p/GroupCrypto.kt: AES-256-GCM by CryptoKit, with the very byte
// layout of javax.crypto (`nonce (12) | ciphertext | tag (16)`) so that a frame sealed on one phone opens on the other.

import CryptoKit
import Foundation

/// Where the random bytes come from (Kotlin `SecureRandom`): the system's generator in the app, a fixed one in the
/// tests that compare frames with those of Android.
public protocol SecureRandomSource: Sendable {
    func nextBytes(_ count: Int) -> [UInt8]
}

extension SecureRandomSource {
    /// Kotlin `SecureRandom.nextLong()`: 8 random bytes, big-endian.
    public func nextLong() -> Int64 {
        let b = nextBytes(8)
        var v: UInt64 = 0
        for byte in b {
            v = v << 8 | UInt64(byte)
        }
        return Int64(bitPattern: v)
    }
}

/// The cryptographic generator of the system (`SystemRandomNumberGenerator`, which is one on Apple platforms).
public struct SystemSecureRandom: SecureRandomSource {
    public init() {}

    public func nextBytes(_ count: Int) -> [UInt8] {
        var generator = SystemRandomNumberGenerator()
        return (0 ..< count).map { _ in UInt8.random(in: UInt8.min ... UInt8.max, using: &generator) }
    }
}

/// Encryption of the messages with the key of the group, on top of the DTLS of each link: a member relaying a message
/// for another cannot forge or read what is not for its group. The key used is `HKDF-SHA256(groupKey, "toolmap-p2p-v1")`.
/// After a re-key the previous key still opens messages for a while, since they travel hop by hop.
public final class GroupCrypto: @unchecked Sendable {
    public static let keyBytes = 32
    public static let nonceBytes = 12
    private static let tagBytes = 16
    private static let keptKeys = 2
    private static let info = Array("toolmap-p2p-v1".utf8)
    private static let hashBytes = 32

    private let random: any SecureRandomSource
    private let lock = NSLock()
    private var keys: [SymmetricKey]

    public init(_ groupKey: [UInt8], random: any SecureRandomSource = SystemSecureRandom()) throws {
        try require(groupKey.count == GroupCrypto.keyBytes, "group key must be \(GroupCrypto.keyBytes) bytes")
        self.random = random
        let first = try GroupCrypto.derive(groupKey)
        keys = [first]
    }

    /// Makes `newKey` the one that seals; the last `keptKeys` − 1 older ones still open.
    public func rekey(_ newKey: [UInt8]) throws {
        try require(newKey.count == GroupCrypto.keyBytes, "Failed requirement.")
        let derived = try GroupCrypto.derive(newKey)
        lock.lock()
        defer { lock.unlock() }
        keys.insert(derived, at: 0)
        while keys.count > GroupCrypto.keptKeys {
            keys.removeLast()
        }
    }

    /// `nonce (12) | ciphertext | tag (16)`; `aad` is authenticated but not stored.
    public func seal(_ plain: [UInt8], _ aad: [UInt8]) -> [UInt8] {
        lock.lock()
        defer { lock.unlock() }
        let nonceBytes = random.nextBytes(GroupCrypto.nonceBytes)
        do {
            let nonce = try AES.GCM.Nonce(data: nonceBytes)
            let box = try AES.GCM.seal(plain, using: keys[0], nonce: nonce, authenticating: aad)
            return nonceBytes + Array(box.ciphertext) + Array(box.tag)
        } catch {
            // A 12-byte nonce and a 256-bit key are always accepted.
            preconditionFailure("AES-GCM refused its parameters: \(error)")
        }
    }

    /// The plain bytes, or a `WireError` when no key of the group authenticates `sealed`.
    public func open(_ sealed: [UInt8], _ aad: [UInt8]) throws -> [UInt8] {
        if sealed.count < GroupCrypto.nonceBytes + GroupCrypto.tagBytes { throw WireError("message too short") }
        lock.lock()
        let candidates = keys
        lock.unlock()
        let box: AES.GCM.SealedBox
        do {
            box = try AES.GCM.SealedBox(combined: sealed)
        } catch {
            throw WireError("message does not belong to this group")
        }
        for key in candidates {
            if let plain = try? AES.GCM.open(box, using: key, authenticating: aad) {
                return Array(plain)
            }
        }
        throw WireError("message does not belong to this group")
    }

    private static func derive(_ groupKey: [UInt8]) throws -> SymmetricKey {
        let derived = try hkdfSha256(groupKey, [], info, keyBytes)
        return SymmetricKey(data: derived)
    }

    /// RFC 5869 HKDF with HMAC-SHA256; an empty `salt` means a string of zeros.
    public static func hkdfSha256(_ ikm: [UInt8], _ salt: [UInt8], _ info: [UInt8], _ length: Int) throws -> [UInt8] {
        try require(length >= 1 && length <= hashBytes * 255, "Failed requirement.")
        let extractKey = SymmetricKey(data: salt.isEmpty ? [UInt8](repeating: 0, count: hashBytes) : salt)
        let prk = SymmetricKey(data: Array(HMAC<SHA256>.authenticationCode(for: ikm, using: extractKey)))
        var out: [UInt8] = []
        var previous: [UInt8] = []
        var counter: UInt8 = 1
        while out.count < length {
            var hmac = HMAC<SHA256>(key: prk)
            hmac.update(data: previous)
            hmac.update(data: info)
            hmac.update(data: [counter])
            previous = Array(hmac.finalize())
            out.append(contentsOf: previous)
            counter &+= 1
        }
        return Array(out.prefix(length))
    }

    public static func randomKey(_ random: any SecureRandomSource = SystemSecureRandom()) -> [UInt8] {
        random.nextBytes(keyBytes)
    }
}
