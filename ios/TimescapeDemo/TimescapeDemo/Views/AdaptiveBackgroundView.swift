import SwiftUI
import UIKit

struct AdaptiveBackgroundView: View {
    var reference: BackgroundReference?
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            let gradient = LinearGradient(
                colors: [Color.black.opacity(0.6), Color.black.opacity(0.2)],
                startPoint: .top,
                endPoint: .bottom
            )
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                        .overlay(gradient)
                        .transition(.opacity)
                } else {
                    RadialGradient(
                        colors: [Color(.sRGB, red: 0.08, green: 0.12, blue: 0.18, opacity: 1), Color(.sRGB, red: 0.01, green: 0.01, blue: 0.03, opacity: 1)],
                        center: .top,
                        startRadius: 0,
                        endRadius: max(proxy.size.width, proxy.size.height)
                    )
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .overlay(gradient)
                }
            }
            .task(id: reference?.id) {
                image = await BackgroundImageLoader.shared.image(for: reference)
            }
        }
    }
}

actor BackgroundImageLoader {
    static let shared = BackgroundImageLoader()
    private let library = ImageLibrary()

    func image(for reference: BackgroundReference?) async -> UIImage? {
        guard let reference else { return nil }
        if let url = await library.url(for: reference), let image = UIImage(contentsOfFile: url.path) {
            return image
        }
        return nil
    }
}
