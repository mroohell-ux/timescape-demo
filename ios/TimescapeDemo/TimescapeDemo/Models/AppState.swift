import Foundation
import SwiftUI

@MainActor
final class AppState: ObservableObject {
    @Published var flows: [CardFlow] = []
    @Published var selectedFlowIndex: Int = 0
    @Published var selectedBackgrounds: [BackgroundReference] = []
    @Published var appBackground: BackgroundReference?
    @Published var cardFontSize: CGFloat = 17

    @Published var isPresentingImagePicker = false
    @Published var isPresentingHandwriting = false
    @Published var editingCard: CardEditContext?

    private let storage = StorageService()
    private let imageLibrary = ImageLibrary()

    func load() async {
        do {
            let snapshot = try await storage.load()
            flows = snapshot.flows.isEmpty ? Self.seedFlows() : snapshot.flows
            selectedBackgrounds = snapshot.backgrounds
            appBackground = snapshot.appBackground
            cardFontSize = snapshot.cardFontSize
            selectedFlowIndex = snapshot.selectedFlowIndex.clamped(to: 0...(max(0, flows.count - 1)))
            ensureCardsHaveBackgrounds()
        } catch {
            flows = Self.seedFlows()
            selectedBackgrounds = []
            appBackground = nil
            cardFontSize = 17
            selectedFlowIndex = 0
        }
    }

    func save() {
        Task { await persist() }
    }

    private func persist() async {
        do {
            let snapshot = AppSnapshot(
                flows: flows,
                backgrounds: selectedBackgrounds,
                appBackground: appBackground,
                selectedFlowIndex: selectedFlowIndex,
                cardFontSize: cardFontSize
            )
            try await storage.save(snapshot)
        } catch {
            print("Failed to save state: \(error)")
        }
    }

    func defaultFlowName() -> String {
        "Flow \(flows.count + 1)"
    }

    func addFlow(named name: String) {
        let flow = CardFlow(name: name)
        flows.append(flow)
        selectedFlowIndex = max(0, flows.count - 1)
        save()
    }

    func deleteFlow(_ flow: CardFlow) {
        guard flows.count > 1 else { return }
        if let index = flows.firstIndex(of: flow) {
            flows.remove(at: index)
            selectedFlowIndex = min(selectedFlowIndex, max(0, flows.count - 1))
            save()
        }
    }

    func updateFlow(_ flow: CardFlow, name: String) {
        guard let index = flows.firstIndex(of: flow) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        flows[index].name = trimmed.isEmpty ? defaultFlowName() : trimmed
        save()
    }

    func addCard(to flow: CardFlow, snippet: String) {
        guard let index = flows.firstIndex(of: flow) else { return }
        let background = randomBackground()
        let card = CardItem(title: "", snippet: snippet, background: background)
        flows[index].cards.insert(card, at: 0)
        flows[index].lastViewedCardId = card.id
        flows[index].lastViewedCardIndex = 0
        save()
    }

    func updateCard(_ card: CardItem, in flow: CardFlow, snippet: String) {
        guard let flowIndex = flows.firstIndex(of: flow) else { return }
        guard let cardIndex = flows[flowIndex].cards.firstIndex(of: card) else { return }
        flows[flowIndex].cards[cardIndex].snippet = snippet
        flows[flowIndex].cards[cardIndex].updatedAt = .now
        save()
    }

    func setHandwriting(_ content: HandwritingContent?, for card: CardItem, in flow: CardFlow) {
        guard let flowIndex = flows.firstIndex(of: flow) else { return }
        guard let cardIndex = flows[flowIndex].cards.firstIndex(of: card) else { return }
        flows[flowIndex].cards[cardIndex].handwriting = content
        flows[flowIndex].cards[cardIndex].updatedAt = .now
        save()
    }

    func addHandwritingCard(_ content: HandwritingContent, to flow: CardFlow) {
        guard let index = flows.firstIndex(of: flow) else { return }
        let background = randomBackground()
        let card = CardItem(title: "", snippet: "", background: background, handwriting: content)
        flows[index].cards.insert(card, at: 0)
        flows[index].lastViewedCardId = card.id
        flows[index].lastViewedCardIndex = 0
        save()
    }

    func removeCard(_ card: CardItem, from flow: CardFlow) {
        guard let flowIndex = flows.firstIndex(of: flow) else { return }
        flows[flowIndex].cards.removeAll { $0.id == card.id }
        save()
    }

    func addBackgrounds(from items: [PickedImage]) async {
        let stored = await imageLibrary.store(items: items)
        selectedBackgrounds.append(contentsOf: stored)
        ensureCardsHaveBackgrounds()
        save()
    }

    func removeBackground(_ reference: BackgroundReference) {
        selectedBackgrounds.removeAll { $0 == reference }
        if appBackground == reference {
            appBackground = nil
        }
        for flowIndex in flows.indices {
            for cardIndex in flows[flowIndex].cards.indices {
                if flows[flowIndex].cards[cardIndex].background == reference {
                    flows[flowIndex].cards[cardIndex].background = randomBackground()
                }
            }
        }
        save()
    }

    func setAppBackground(_ ref: BackgroundReference?) {
        appBackground = ref
        save()
    }

    func setFontSize(_ value: CGFloat) {
        cardFontSize = value
        save()
    }

    func randomBackground() -> BackgroundReference? {
        guard !selectedBackgrounds.isEmpty else { return nil }
        return selectedBackgrounds.randomElement()
    }

    func ensureCardsHaveBackgrounds() {
        guard !selectedBackgrounds.isEmpty else { return }
        for index in flows.indices {
            for cardIndex in flows[index].cards.indices where flows[index].cards[cardIndex].background == nil {
                flows[index].cards[cardIndex].background = randomBackground()
            }
        }
    }

    func selectFlow(index: Int) {
        selectedFlowIndex = index
        save()
    }
}

struct CardEditContext: Identifiable {
    let id = UUID()
    let flow: CardFlow
    let card: CardItem?
    var snippet: String
    let mode: Mode

    enum Mode {
        case create
        case update
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

extension AppState {
    private static func seedFlows() -> [CardFlow] {
        let snippets: [String] = [
            "Ping me when you’re free.",
            "Grows toward center. Two neighbors overlap a bit.",
            "This is a slightly longer body so you can see the card stretch beyond a couple of lines.",
            "Here’s a longer description to show adaptive height. Tap to focus; tap again to defocus.",
            "Long body to approach the 2/3 screen-height cap.",
            "EXTREMELY LONG CONTENT — intentionally capped by parent height. EXTREMELY LONG CONTENT — intentionally capped by parent height."
        ]
        var flows: [CardFlow] = []
        for index in 0..<3 {
            var cards: [CardItem] = []
            for i in 0..<30 {
                let snippet = snippets[i % snippets.count]
                cards.append(CardItem(title: "", snippet: snippet))
            }
            flows.append(CardFlow(name: "Flow \(index + 1)", cards: cards))
        }
        return flows
    }
}

struct AppSnapshot: Codable {
    var flows: [CardFlow]
    var backgrounds: [BackgroundReference]
    var appBackground: BackgroundReference?
    var selectedFlowIndex: Int
    var cardFontSize: CGFloat
}
