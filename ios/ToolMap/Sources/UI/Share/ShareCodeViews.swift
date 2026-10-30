import AVFoundation
import SwiftUI
import ToolMapCore
import UIKit

/// A QR code on a white square with its quiet zone, which scanners need to find it (Android `QrImage`).
struct ShareQrImage: View {
    let text: String

    var body: some View {
        if let image = ShareQr.image(text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(16)
                .background(Color.white)
                .accessibilityLabel("Code QR de l'invitation")
        } else {
            Text("Code impossible à afficher.")
                .foregroundStyle(Crete.danger)
        }
    }
}

/// The invitation in a sheet (P29.8): the QR first, what it does, « Envoyer le lien par message » across the sheet and
/// « Fermer » (an invitation of the server stays valid 7 days: closing does not cancel it).
struct ShareInviteSheet: View {
    @Environment(\.dismiss) private var dismiss
    let code: ShareInviteCode

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text("Invitation")
                    .font(.title2.weight(.semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                ShareQrImage(text: code.qrText)
                    .frame(width: 240, height: 240)
                Text("Faites scanner ce code (ou envoyez le lien) : la personne rejoint le groupe aussitôt, sans code de réponse. Valable 7 jours ; tous ceux qui l'ont peuvent entrer, ne le publiez pas.")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                ShareLink(item: code.link) {
                    Label("Envoyer le lien par message", systemImage: "square.and.arrow.up")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(Crete.turquoise)
                Button("Fermer") {
                    dismiss()
                }
                .frame(minHeight: 44)
            }
            .padding(24)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}

/// The camera with a QR code reader, full screen (Android `ScanDialog`); asks for the camera first.
struct ShareScannerView: View {
    let onCode: (String) -> Void
    let onClose: () -> Void
    @State private var status = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var unavailable = false

    var body: some View {
        ZStack {
            Color.black
                .ignoresSafeArea()
            if status == .authorized && !unavailable {
                ShareQrCameraView(onCode: onCode, onUnavailable: { unavailable = true })
                    .ignoresSafeArea()
                VStack {
                    Text("Visez le QR code de l'autre téléphone")
                        .font(.headline)
                        .foregroundStyle(Color.white)
                        .padding(16)
                    Spacer()
                }
            } else if status == .notDetermined && !unavailable {
                ProgressView()
                    .tint(Color.white)
            } else {
                ShareCameraRefused(unavailable: unavailable)
            }
            VStack {
                Spacer()
                Button("Annuler", action: onClose)
                    .font(.headline)
                    .foregroundStyle(Color.white)
                    .frame(minWidth: 120, minHeight: 44)
                    .padding(.bottom, 16)
            }
        }
        .task {
            if AVCaptureDevice.default(for: .video) == nil {
                unavailable = true
                return
            }
            if status == .notDetermined {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                status = granted ? .authorized : .denied
            }
        }
    }
}

/// Why the camera does not show: refused (with the way to allow it), or none on this device.
struct ShareCameraRefused: View {
    let unavailable: Bool

    var body: some View {
        VStack(spacing: 16) {
            Text(unavailable
                ? "Impossible d'ouvrir l'appareil photo. Collez plutôt le lien reçu par message."
                : "L'appareil photo sert uniquement à lire le QR code. Vous pouvez aussi coller le lien reçu par message.")
                .foregroundStyle(Color.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            if !unavailable {
                Button("Autoriser l'appareil photo") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .foregroundStyle(Crete.pill)
            }
        }
    }
}

/// The camera preview and its QR reader (AVCaptureSession + AVCaptureMetadataOutput).
struct ShareQrCameraView: UIViewRepresentable {
    let onCode: (String) -> Void
    let onUnavailable: () -> Void

    func makeCoordinator() -> ShareQrCamera {
        ShareQrCamera()
    }

    func makeUIView(context: Context) -> ShareQrPreviewView {
        let view = ShareQrPreviewView()
        view.backgroundColor = .black
        let camera = context.coordinator
        camera.onCode = onCode
        if let session = camera.start() {
            view.previewLayer?.session = session
            view.previewLayer?.videoGravity = .resizeAspectFill
        } else {
            let report = onUnavailable
            DispatchQueue.main.async {
                report()
            }
        }
        return view
    }

    func updateUIView(_ uiView: ShareQrPreviewView, context: Context) {
        context.coordinator.onCode = onCode
    }

    static func dismantleUIView(_ uiView: ShareQrPreviewView, coordinator: ShareQrCamera) {
        coordinator.stop()
    }
}

/// A view whose layer is the camera preview.
final class ShareQrPreviewView: UIView {
    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    var previewLayer: AVCaptureVideoPreviewLayer? {
        layer as? AVCaptureVideoPreviewLayer
    }
}

/// The capture session and the reader of its QR codes; the first code read is handed over once.
final class ShareQrCamera: NSObject, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "fr.toolmap.share.camera")
    private var delivered = false

    /// Sets the session up and starts it; nil when there is no camera or it cannot read QR codes.
    func start() -> AVCaptureSession? {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else { return nil }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return nil }
        session.addOutput(output)
        guard output.availableMetadataObjectTypes.contains(.qr) else { return nil }
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]
        let running = session
        queue.async {
            running.startRunning()
        }
        return session
    }

    func stop() {
        let running = session
        queue.async {
            running.stopRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !delivered else { return }
        for object in metadataObjects {
            if let code = (object as? AVMetadataMachineReadableCodeObject)?.stringValue {
                delivered = true
                onCode?(code)
                return
            }
        }
    }
}
