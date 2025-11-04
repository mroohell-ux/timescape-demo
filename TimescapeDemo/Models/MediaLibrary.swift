import Foundation
import SwiftUI
import UIKit

@MainActor
final class MediaLibrary: ObservableObject {
    @Published private(set) var cards: [CardItem] = []
    @Published private(set) var gradients: [LiquidGradient]
    @Published var userImages: [GalleryImage] = [] {
        didSet { rebuildCards() }
    }

    private let titles = [
        "Aurora Minutes",
        "Launch Check-in",
        "Design Handoff",
        "Capture Session",
        "Glacier Stories",
        "Twilight Dispatch"
    ]

    private let bodies = [
        "Ride the timeline into a shimmering burst of highlights.",
        "Every swipe reveals another liquid-glass vignette worth saving.",
        "A curated rail of moments, anchored in fluid depth and light.",
        "Hold to spotlight the scene and feel the depth bloom.",
        "Add your own shots to remix the story instantly.",
        "From sunrise briefs to midnight recaps, it all flows here."
    ]

    init() {
        gradients = Self.defaultGradients
        rebuildCards()
    }

    var availableBackgrounds: [CardItem.BackgroundStyle] {
        let gradientStyles = gradients.map { CardItem.BackgroundStyle.gradient($0) }
        let photoStyles = userImages.map { CardItem.BackgroundStyle.photo($0) }
        return gradientStyles + photoStyles
    }

    func addImage(_ image: UIImage) {
        let accent = image.averageColor ?? Color.white
        let galleryImage = GalleryImage(image: image, accent: accent)
        userImages.append(galleryImage)
    }

    func removeImage(_ image: GalleryImage) {
        userImages.removeAll { $0.id == image.id }
    }

    func resetLibrary() {
        userImages.removeAll()
    }

    func refreshCards() {
        gradients.shuffle()
        rebuildCards()
    }

    private func rebuildCards() {
        let backgrounds = availableBackgrounds
        guard !backgrounds.isEmpty else {
            cards = []
            return
        }

        let sequence = backgrounds.shuffled()
        var updated: [CardItem] = []
        let total = max(24, sequence.count * 3)
        let now = Date()
        for index in 0..<total {
            let background = sequence[index % sequence.count]
            let accent: Color
            switch background {
            case .gradient(let gradient):
                accent = gradient.glow
            case .photo(let image):
                accent = image.accent
            }

            let title = titles[index % titles.count]
            let body = bodies[index % bodies.count]
            let timestamp = Calendar.current.date(byAdding: .hour, value: -(index * 3), to: now) ?? now
            updated.append(CardItem(title: title, message: body, timestamp: timestamp, background: background, accent: accent))
        }

        cards = updated
    }
}

private extension MediaLibrary {
    static var defaultGradients: [LiquidGradient] {
        [
            LiquidGradient(colors: [.purple, .blue, Color(red: 0.1, green: 0.8, blue: 0.9)], glow: Color(red: 0.8, green: 0.9, blue: 1.0)),
            LiquidGradient(colors: [Color(red: 0.9, green: 0.3, blue: 0.5), Color(red: 0.98, green: 0.6, blue: 0.3)], glow: Color(red: 1.0, green: 0.8, blue: 0.6)),
            LiquidGradient(colors: [Color(red: 0.3, green: 0.2, blue: 0.6), Color(red: 0.6, green: 0.4, blue: 1.0)], glow: Color(red: 0.7, green: 0.5, blue: 1.0)),
            LiquidGradient(colors: [Color(red: 0.15, green: 0.15, blue: 0.3), Color(red: 0.4, green: 0.75, blue: 0.9)], glow: Color(red: 0.55, green: 0.9, blue: 1.0)),
            LiquidGradient(colors: [Color(red: 0.95, green: 0.9, blue: 0.6), Color(red: 0.95, green: 0.75, blue: 0.4)], glow: Color(red: 1.0, green: 0.85, blue: 0.6)),
            LiquidGradient(colors: [Color(red: 0.4, green: 0.2, blue: 0.2), Color(red: 0.85, green: 0.4, blue: 0.45)], glow: Color(red: 1.0, green: 0.6, blue: 0.65))
        ]
    }
}
