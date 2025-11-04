import SwiftUI

struct LiquidGlassCard: View {
    let item: CardItem

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LiquidGlassBackground(style: item.background, accent: item.accent)

            VStack(alignment: .leading, spacing: 16) {
                Spacer(minLength: 0)

                VStack(alignment: .leading, spacing: 8) {
                    Text(item.title)
                        .font(.system(.title2, design: .rounded).weight(.semibold))
                        .foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.2), radius: 6, x: 0, y: 12)

                    Text(item.message)
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(3)
                }

                HStack(spacing: 10) {
                    Circle()
                        .fill(item.accent.opacity(0.35))
                        .frame(width: 12, height: 12)
                        .shadow(color: item.accent.opacity(0.5), radius: 8, x: 0, y: 0)
                    Text(item.relativeTimestamp)
                        .font(.system(.footnote, design: .rounded).smallCaps())
                        .foregroundStyle(.white.opacity(0.75))
                }
            }
            .padding(26)

            VStack {
                HStack {
                    Spacer()
                    LiquidGlassBadge(accent: item.accent)
                }
                Spacer()
            }
            .padding(24)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 340)
    }
}

private struct LiquidGlassBadge: View {
    let accent: Color

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(.ultraThinMaterial.opacity(0.6))
                .background(.regularMaterial.opacity(0.3))
                .blendMode(.plusLighter)
            Image(systemName: "message.fill")
                .font(.title3)
                .foregroundStyle(.white.opacity(0.85), accent.opacity(0.95))
                .shadow(color: accent.opacity(0.6), radius: 6, x: 0, y: 4)
        }
        .frame(width: 52, height: 42)
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Color.white.opacity(0.35), lineWidth: 0.8)
        }
        .shadow(color: accent.opacity(0.35), radius: 18, x: 0, y: 10)
    }
}

private struct LiquidGlassBackground: View {
    let style: CardItem.BackgroundStyle
    let accent: Color

    var body: some View {
        GeometryReader { proxy in
            let radius = max(proxy.size.width, proxy.size.height)

            ZStack {
                backgroundContent
                    .overlay { centerGlow(radius: radius) }
                    .mask(fadeMask(radius: radius))
                    .overlay { glassSheen }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .clipShape(RoundedRectangle(cornerRadius: 36, style: .continuous))
        .overlay { glassStroke }
        .shadow(color: accent.opacity(0.25), radius: 40, x: 0, y: 30)
    }

    @ViewBuilder
    private var backgroundContent: some View {
        switch style {
        case .gradient(let gradient):
            LinearGradient(colors: gradient.colors, startPoint: gradient.startPoint, endPoint: gradient.endPoint)
                .overlay(
                    AngularGradient(gradient: Gradient(colors: gradient.colors + gradient.colors.reversed()), center: .center)
                        .opacity(0.25)
                        .blendMode(.screen)
                )
        case .photo(let image):
            image.image
                .resizable()
                .scaledToFill()
                .overlay(
                    LinearGradient(colors: [.black.opacity(0.05), .clear], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
        }
    }

    @ViewBuilder
    private var glassStroke: some View {
        RoundedRectangle(cornerRadius: 36, style: .continuous)
            .strokeBorder(LinearGradient(colors: [.white.opacity(0.75), .white.opacity(0.15)], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.2)
            .blendMode(.plusLighter)
            .overlay(
                RoundedRectangle(cornerRadius: 36, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 3)
                    .blur(radius: 2)
                    .offset(y: 2)
                    .mask(RoundedRectangle(cornerRadius: 36, style: .continuous).fill(LinearGradient(colors: [.white, .clear], startPoint: .top, endPoint: .bottom)))
            )
    }

    @ViewBuilder
    private var glassSheen: some View {
        RoundedRectangle(cornerRadius: 36, style: .continuous)
            .fill(.ultraThinMaterial.opacity(0.35))
            .blendMode(.plusLighter)
    }

    private func fadeMask(radius: CGFloat) -> some View {
        RadialGradient(
            gradient: Gradient(stops: [
                .init(color: .white, location: 0.0),
                .init(color: .white, location: 0.45),
                .init(color: .white.opacity(0.55), location: 0.7),
                .init(color: .white.opacity(0.0), location: 1.0)
            ]),
            center: .center,
            startRadius: 0,
            endRadius: radius * 0.65
        )
        .compositingGroup()
    }

    private func centerGlow(radius: CGFloat) -> some View {
        Circle()
            .fill(accent.opacity(0.45))
            .frame(width: radius * 0.8, height: radius * 0.8)
            .blur(radius: 65)
            .blendMode(.screen)
            .opacity(0.9)
    }
}
