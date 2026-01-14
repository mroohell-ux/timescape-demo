import SwiftUI

enum CardEditorResult {
    case save(String)
    case delete
    case cancel
}

struct CardEditorSheet: View {
    let context: CardEditContext
    var onComplete: (CardEditorResult) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var snippet: String

    init(context: CardEditContext, onComplete: @escaping (CardEditorResult) -> Void) {
        self.context = context
        self.onComplete = onComplete
        _snippet = State(initialValue: context.snippet)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Card") {
                    TextEditor(text: $snippet)
                        .frame(minHeight: 200)
                }
            }
            .navigationTitle(context.mode == .create ? "New Card" : "Edit Card")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                        onComplete(.cancel)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        dismiss()
                        onComplete(.save(snippet))
                    }
                }
                if context.mode == .update {
                    ToolbarItem(placement: .bottomBar) {
                        Button(role: .destructive) {
                            dismiss()
                            onComplete(.delete)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
        }
    }
}
