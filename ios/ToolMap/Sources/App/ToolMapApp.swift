import SwiftUI

@main
struct ToolMapApp: App {
    @State private var model: AppModel

    init() {
        // The tile interceptor must be in MapLibre's network configuration before the first map view is created.
        TileURLProtocol.install()
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            MapScreen()
                .environment(model)
                .onOpenURL { url in
                    model.open(url)
                }
        }
    }
}
