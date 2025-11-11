import SwiftUI

struct LiquidGlassStyle: ViewModifier {
    var cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .overlay(
                        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                            .stroke(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.6), Color.white.opacity(0.05)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 1.2
                            )
                            .blendMode(.screen)
                    )
                    .shadow(color: Color.white.opacity(0.25), radius: 12, x: -8, y: -8)
                    .shadow(color: Color.black.opacity(0.35), radius: 20, x: 10, y: 20)
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color.white.opacity(0.2), Color.white.opacity(0)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .blendMode(.screen)
            )
    }
}

extension View {
    func liquidGlass(cornerRadius: CGFloat = 28) -> some View {
        modifier(LiquidGlassStyle(cornerRadius: cornerRadius))
    }
}
