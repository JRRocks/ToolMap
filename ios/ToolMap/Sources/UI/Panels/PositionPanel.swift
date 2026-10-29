import CoreLocation
import SwiftUI
import ToolMapCore

/// Positionnement (Android §4, P29.5 "Sentier"): the live GPS position and the button that centres the map on it.
struct PositionPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            PanelHeader(title: "Positionnement", subtitle: subtitle)
            List {
                Section("Position en temps réel") {
                    if let position = model.location.location {
                        LabeledContent("Coordonnées") {
                            Text(MapText.position(LonLat(
                                lon: position.coordinate.longitude,
                                lat: position.coordinate.latitude
                            )))
                            .font(.footnote.monospacedDigit())
                        }
                        LabeledContent("Précision", value: "± " + MapText.number(position.horizontalAccuracy, decimals: 0) + " m")
                        if position.verticalAccuracy >= 0 {
                            LabeledContent("Altitude GPS", value: MapText.number(position.altitude, decimals: 0) + " m")
                        }
                        if position.speed >= 0 {
                            LabeledContent("Vitesse", value: MapText.number(position.speed * 3.6, decimals: 1) + " km/h")
                        }
                    } else {
                        Text(MapText.gpsSummary(nil, authorized: model.location.isAuthorized))
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                }
                Section {
                    Button {
                        model.centerOnPosition()
                        model.panel = nil
                    } label: {
                        Label("Centrer la carte sur ma position", systemImage: "location.fill")
                    }
                    .disabled(model.location.location == nil)
                    if !model.location.isAuthorized {
                        Button {
                            model.location.start()
                        } label: {
                            Label("Autoriser la localisation", systemImage: "location.circle")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private var subtitle: String {
        MapText.gpsSummary(model.location.location, authorized: model.location.isAuthorized)
    }
}
