import SwiftUI

struct LiquidGradient: Identifiable, Hashable {
    let id = UUID()
    let colors: [Color]
    let startPoint: UnitPoint
    let endPoint: UnitPoint
    let glow: Color

    init(colors: [Color], startPoint: UnitPoint = .topLeading, endPoint: UnitPoint = .bottomTrailing, glow: Color? = nil) {
        self.colors = colors
        self.startPoint = startPoint
        self.endPoint = endPoint
        self.glow = glow ?? colors.first ?? .white
    }
}
