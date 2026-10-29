/// A broken precondition: the Swift form of the `IllegalArgumentException` thrown by Kotlin's `require(...)`
/// in `:core`. Functions that `require` something in Kotlin `throw` this error here, so that the same tests apply.
public enum ToolMapError: Error, Equatable, Sendable, CustomStringConvertible {
    case invalidArgument(String)

    public var message: String {
        switch self {
        case let .invalidArgument(message): message
        }
    }

    public var description: String { message }
}

/// Kotlin's `require(condition) { message }`.
func require(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition { throw ToolMapError.invalidArgument(message()) }
}
