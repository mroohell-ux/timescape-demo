import SwiftUI
import UIKit

struct CardCarouselView: View {
    var flow: CardFlow
    var fontSize: CGFloat
    var onTap: (CardItem) -> Void
    var onDoubleTap: (CardItem) -> Void

    @State private var selectedCardId: UUID?

    var body: some View {
        TabView(selection: $selectedCardId) {
            ForEach(flow.cards) { card in
                CardView(card: card, fontSize: fontSize)
                    .tag(card.id as UUID?)
                    .padding(.vertical, 24)
                    .onTapGesture { onTap(card) }
                    .onTapGesture(count: 2) { onDoubleTap(card) }
                    .transition(.scale(scale: 0.98).combined(with: .opacity))
                    .padding(.horizontal, 4)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .onAppear { selectedCardId = flow.cards.first?.id }
        .onChange(of: flow.cards) { cards in
            if let first = cards.first, selectedCardId == nil {
                selectedCardId = first.id
            }
        }
    }
}

struct CardView: View {
    var card: CardItem
    var fontSize: CGFloat
    @State private var backgroundImage: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .bottomLeading) {
                background(proxy: proxy)
                VStack(alignment: .leading, spacing: 12) {
                    Text(card.updatedAt, style: .relative)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.85))
                    if let handwriting = card.handwriting, let image = handwriting.renderedImage() {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 24, style: .continuous)
                                    .stroke(Color.white.opacity(0.2), lineWidth: 1)
                            )
                            .shadow(color: Color.black.opacity(0.35), radius: 20, y: 10)
                    }
                    Text(card.snippet)
                        .font(.system(size: fontSize, weight: .regular, design: .default))
                        .foregroundStyle(Color.white)
                        .lineLimit(nil)
                        .minimumScaleFactor(0.9)
                }
                .padding(24)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .liquidGlass(cornerRadius: 36)
            .task(id: card.background?.id) {
                backgroundImage = await BackgroundImageLoader.shared.image(for: card.background)
                if card.background == nil {
                    backgroundImage = nil
                }
            }
        }
    }

    @ViewBuilder
    private func background(proxy: GeometryProxy) -> some View {
        let overlay = LinearGradient(colors: [Color.black.opacity(0.55), Color.black.opacity(0.1)], startPoint: .bottom, endPoint: .top)
        if let image = backgroundImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: proxy.size.width, height: proxy.size.height)
                .clipped()
                .overlay(overlay)
                .blur(radius: 20)
        } else {
            LinearGradient(colors: [Color.blue.opacity(0.5), Color.purple.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing)
                .frame(width: proxy.size.width, height: proxy.size.height)
                .overlay(overlay)
        }
    }
}
