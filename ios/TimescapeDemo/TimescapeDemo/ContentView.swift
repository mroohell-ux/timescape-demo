import SwiftUI
import PhotosUI

struct ContentView: View {
    @EnvironmentObject private var appState: AppState
    @State private var showingBackgroundLibrary = false
    @State private var showingFontSheet = false
    @State private var photoPickerItems: [PhotosPickerItem] = []
    @State private var handwritingSession: HandwritingSession?

    var body: some View {
        NavigationStack {
            ZStack {
                AdaptiveBackgroundView(reference: appState.appBackground)
                    .ignoresSafeArea()

                VStack(spacing: 24) {
                    FlowSelectorView(
                        flows: appState.flows,
                        selectedIndex: appState.selectedFlowIndex,
                        onSelect: { appState.selectFlow(index: $0) },
                        onRename: { flow, name in appState.updateFlow(flow, name: name) },
                        onDelete: { flow in appState.deleteFlow(flow) }
                    )
                    .padding(.top, 12)

                    if let flow = appState.flows[safe: appState.selectedFlowIndex] {
                        CardCarouselView(
                            flow: flow,
                            fontSize: appState.cardFontSize,
                            onTap: { card in
                                appState.editingCard = CardEditContext(flow: flow, card: card, snippet: card.snippet, mode: .update)
                            },
                            onDoubleTap: { card in
                                handwritingSession = HandwritingSession(flow: flow, card: card, isNew: false)
                            }
                        )
                        .preferredFrameRateRange(.init(minimum: 80, maximum: 120, preferred: 120))
                        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: flow.cards)
                        .padding(.horizontal, 16)
                    } else {
                        Spacer()
                    }
                }
                .padding(.bottom, 32)
            }
            .navigationTitle("Timescape")
            .toolbar { toolbar }
            .photosPicker(isPresented: Binding(get: {
                false
            }, set: { newValue in
                if newValue { showingBackgroundLibrary = true }
            }), selection: $photoPickerItems)
            .sheet(isPresented: $showingBackgroundLibrary) {
                BackgroundLibrarySheet(
                    photosSelection: $photoPickerItems,
                    backgrounds: appState.selectedBackgrounds,
                    onPhotosPicked: loadPickedImages,
                    onSetAppBackground: { appState.setAppBackground($0) },
                    onRemoveBackground: { appState.removeBackground($0) },
                    currentAppBackground: appState.appBackground
                )
                .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showingFontSheet) {
                FontSizeSheet(
                    fontSize: Binding(
                        get: { appState.cardFontSize },
                        set: { appState.setFontSize($0) }
                    )
                )
                .presentationDetents([.height(200)])
            }
            .sheet(item: $appState.editingCard) { context in
                CardEditorSheet(context: context) { result in
                    handleEditorResult(result, context: context)
                }
            }
            .sheet(item: $handwritingSession) { session in
                HandwritingEditorSheet(
                    initialContent: session.card?.handwriting,
                    onSave: { content in
                        if let card = session.card {
                            appState.setHandwriting(content, for: card, in: session.flow)
                        } else if let content {
                            appState.addHandwritingCard(content, to: session.flow)
                        }
                        handwritingSession = nil
                    },
                    onDelete: {
                        if let card = session.card {
                            appState.setHandwriting(nil, for: card, in: session.flow)
                        }
                        handwritingSession = nil
                    },
                    onCancel: {
                        handwritingSession = nil
                    }
                )
            }
        }
    }

    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Menu {
                Button("Add Card") {
                    guard let flow = appState.flows[safe: appState.selectedFlowIndex] else { return }
                    appState.editingCard = CardEditContext(flow: flow, card: nil, snippet: "", mode: .create)
                }
                Button("Add Handwriting") {
                    guard let flow = appState.flows[safe: appState.selectedFlowIndex] else { return }
                    handwritingSession = HandwritingSession(flow: flow, card: nil, isNew: true)
                }
                Divider()
                Button("Add Flow") {
                    appState.addFlow(named: appState.defaultFlowName())
                }
                Divider()
                Button("Background Library") {
                    showingBackgroundLibrary = true
                }
                Button("Card Font Size") {
                    showingFontSheet = true
                }
            } label: {
                Label("Actions", systemImage: "ellipsis.circle")
            }
        }
    }

    private func handleEditorResult(_ result: CardEditorResult, context: CardEditContext) {
        switch (context.mode, result) {
        case (.create, .save(let snippet)):
            appState.addCard(to: context.flow, snippet: snippet)
        case (.update, .save(let snippet)):
            if let card = context.card {
                appState.updateCard(card, in: context.flow, snippet: snippet)
            }
        case (_, .delete):
            if let card = context.card {
                appState.removeCard(card, from: context.flow)
            }
        case (_, .cancel):
            break
        }
        appState.editingCard = nil
    }

    private func loadPickedImages(from items: [PhotosPickerItem]) async {
        guard !items.isEmpty else { return }
        var pickeds: [PickedImage] = []
        for item in items {
            if let image = try? await item.loadTransferable(type: PickedImage.self) {
                pickeds.append(image)
            }
        }
        if !pickeds.isEmpty {
            await appState.addBackgrounds(from: pickeds)
        }
        await MainActor.run { photoPickerItems.removeAll() }
    }
}

private struct HandwritingSession: Identifiable {
    let id = UUID()
    let flow: CardFlow
    let card: CardItem?
    let isNew: Bool
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
