import Foundation
import ToolMapCore

// The interfaces a feature declares for what it needs from another one, met here, where both features are known.

/// Drawing and routes save, reopen and follow their lines through the shared repository of the library.
extension LibraryController: RouteLibrarySource {
    var trackRepository: TrackRepository? { repository }
}
