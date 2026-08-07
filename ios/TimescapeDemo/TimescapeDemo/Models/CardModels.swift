import Foundation
import SwiftUI

struct CardItem: Identifiable, Hashable, Codable {
    enum CodingKeys: CodingKey {
        case id, title, snippet, updatedAt, background, handwriting
    }

    var id: UUID
    var title: String
    var snippet: String
    var updatedAt: Date
    var background: BackgroundReference?
    var handwriting: HandwritingContent?

    init(
        id: UUID = UUID(),
        title: String,
        snippet: String,
        updatedAt: Date = .now,
        background: BackgroundReference? = nil,
        handwriting: HandwritingContent? = nil
    ) {
        self.id = id
        self.title = title
        self.snippet = snippet
        self.updatedAt = updatedAt
        self.background = background
        self.handwriting = handwriting
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        snippet = try container.decodeIfPresent(String.self, forKey: .snippet) ?? ""
        updatedAt = try container.decodeIfPresent(Date.self, forKey: .updatedAt) ?? .now
        background = try container.decodeIfPresent(BackgroundReference.self, forKey: .background)
        handwriting = try container.decodeIfPresent(HandwritingContent.self, forKey: .handwriting)
    }
}

struct CardFlow: Identifiable, Hashable, Codable {
    var id: UUID
    var name: String
    var cards: [CardItem]
    var lastViewedCardId: UUID?
    var lastViewedCardIndex: Int
    var lastViewedCardFocused: Bool

    init(
        id: UUID = UUID(),
        name: String,
        cards: [CardItem] = [],
        lastViewedCardId: UUID? = nil,
        lastViewedCardIndex: Int = 0,
        lastViewedCardFocused: Bool = false
    ) {
        self.id = id
        self.name = name
        self.cards = cards
        self.lastViewedCardId = lastViewedCardId
        self.lastViewedCardIndex = lastViewedCardIndex
        self.lastViewedCardFocused = lastViewedCardFocused
    }
}

struct BackgroundReference: Hashable, Codable, Identifiable {
    enum Kind: String, Codable {
        case asset
        case bundled
        case stored
    }

    var id: UUID
    var kind: Kind
    var resource: String

    init(id: UUID = UUID(), kind: Kind, resource: String) {
        self.id = id
        self.kind = kind
        self.resource = resource
    }
}

struct HandwritingContent: Hashable, Codable {
    var drawingData: Data
    var size: CGSize
    var backgroundColorHex: String
    var brushColorHex: String

    init(drawingData: Data, size: CGSize, backgroundColor: Color, brushColor: Color) {
        self.drawingData = drawingData
        self.size = size
        self.backgroundColorHex = backgroundColor.toHex(alpha: true)
        self.brushColorHex = brushColor.toHex(alpha: true)
    }
}

extension HandwritingContent {
    var backgroundColor: Color { Color(hex: backgroundColorHex) ?? .clear }
    var brushColor: Color { Color(hex: brushColorHex) ?? .primary }
}
