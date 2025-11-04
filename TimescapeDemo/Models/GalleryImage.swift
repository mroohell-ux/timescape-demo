import SwiftUI
import UIKit

struct GalleryImage: Identifiable, Equatable {
    let id = UUID()
    let uiImage: UIImage
    let accent: Color

    init(image: UIImage, accent: Color) {
        self.uiImage = image
        self.accent = accent
    }

    var image: Image {
        Image(uiImage: uiImage)
    }

    static func == (lhs: GalleryImage, rhs: GalleryImage) -> Bool {
        lhs.id == rhs.id
    }
}
