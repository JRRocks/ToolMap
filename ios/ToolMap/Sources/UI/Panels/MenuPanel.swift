import SwiftUI

/// Main menu (Android §3, P29.2 "Portée"): the app and its version, then the switches of the screen.
struct MenuPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        VStack(spacing: 0) {
            PanelHeader(title: "ToolMap", subtitle: "Version \(model.version) pour iPhone")
            List {
                Section {
                    Toggle(isOn: $model.showInfo) {
                        Label {
                            VStack(alignment: .leading) {
                                Text("Informations à l'écran")
                                Text("Position, échelle, GPS")
                                    .font(.footnote)
                                    .foregroundStyle(Crete.onSurfaceVariant)
                            }
                        } icon: {
                            Image(systemName: "info.circle")
                        }
                    }
                    Toggle(isOn: $model.nightMode) {
                        Label {
                            VStack(alignment: .leading) {
                                Text("Mode nuit")
                                Text(model.nightMode ? "Carte assombrie à 60 %" : "Assombrit la carte à 60 %")
                                    .font(.footnote)
                                    .foregroundStyle(Crete.onSurfaceVariant)
                            }
                        } icon: {
                            Image(systemName: "moon")
                        }
                    }
                }
                .tint(Crete.turquoise)
                Section("À propos") {
                    Text("ToolMap reprend AlpineQuest : cartes de toutes sources, hors ligne, traces GPX. Cette version iPhone est écrite en Swift, à part de l'application Android.")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .listStyle(.insetGrouped)
        }
    }
}

/// Traces et waypoints (Android §6): filled in by P32.8.
struct TracksPanel: View {
    var body: some View {
        VStack(spacing: 0) {
            PanelHeader(title: "Traces et waypoints")
            List {
                Text("Aucune trace pour l'instant.")
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .listStyle(.insetGrouped)
        }
    }
}
