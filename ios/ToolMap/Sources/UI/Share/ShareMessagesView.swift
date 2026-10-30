import SwiftUI
import ToolMapCore

/// The page of the messages (Android `MessagesPage`): the last 50 with their time, then the field and « Envoyer ».
/// Open, the page reads what comes in.
struct ShareMessagesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        let shown = Array(sharing.messages.suffix(50))
        ScrollViewReader { proxy in
            List {
                if shown.isEmpty {
                    Text("Aucun message pour l'instant.")
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                ForEach(shown) { message in
                    ShareMessageRow(message: message)
                }
            }
            .listStyle(.plain)
            .onChange(of: sharing.messages.count) { _, _ in
                sharing.markMessagesRead()
                if let last = sharing.messages.last {
                    withAnimation {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
            .onAppear {
                sharing.markMessagesRead()
                if let last = shown.last {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            ShareComposer()
        }
        .navigationTitle("Messages")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// « Moi · 10:41 » over the text.
struct ShareMessageRow: View {
    let message: ShareChatMessage

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(header)
                .font(.caption.weight(.medium))
                .foregroundStyle(Crete.onSurfaceVariant)
            Text(message.text)
                .font(.body)
                .foregroundStyle(Crete.onSurface)
        }
        .padding(.vertical, 2)
    }

    private var header: String {
        let author = message.mine ? "Moi" : message.name
        return author + " · " + ShareText.clock(message.timeMs)
    }
}

/// The field « Un mot pour le groupe » and « Envoyer » (200 characters at most, never empty).
struct ShareComposer: View {
    @Environment(AppModel.self) private var model
    @State private var draft = ""

    var body: some View {
        let blank = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        HStack(spacing: 8) {
            TextField("Un mot pour le groupe", text: $draft)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.send)
                .onSubmit { send() }
                .onChange(of: draft) { _, value in
                    if value.count > 200 {
                        draft = String(value.prefix(200))
                    }
                }
            Button("Envoyer") {
                send()
            }
            .buttonStyle(.borderedProminent)
            .tint(Crete.turquoise)
            .disabled(blank)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func send() {
        let text = draft
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        model.sharing.sendText(text)
        draft = ""
    }
}
