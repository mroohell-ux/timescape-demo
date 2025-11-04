import SwiftUI

struct CardItem: Identifiable {
    enum BackgroundStyle: Identifiable {
        case gradient(LiquidGradient)
        case photo(GalleryImage)

        var id: UUID {
            switch self {
            case .gradient(let gradient):
                return gradient.id
            case .photo(let image):
                return image.id
            }
        }
    }

    let id = UUID()
    let title: String
    let message: String
    let timestamp: Date
    let background: BackgroundStyle
    let accent: Color
}

extension CardItem {
    var relativeTimestamp: String {
        RelativeDateTimeFormatter.timescape.string(for: timestamp)
    }
}

private extension RelativeDateTimeFormatter {
    static let timescape: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter
    }()
}
