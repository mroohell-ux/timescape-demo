import SwiftUI

struct CardRailView: View {
    @EnvironmentObject private var library: MediaLibrary

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.05, green: 0.08, blue: 0.16), Color(red: 0.13, green: 0.05, blue: 0.21)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: -140) {
                    ForEach(library.cards) { card in
                        GeometryReader { proxy in
                            let frame = proxy.frame(in: .named("scroll"))
                            let viewport = UIScreen.main.bounds.height * 0.45
                            let distance = frame.midY - viewport
                            let normalized = max(min(distance / 520, 1), -1)
                            let scale = 1.0 - abs(normalized) * 0.22
                            let tilt = Angle(degrees: Double(-normalized) * 16)
                            let horizontal = normalized * 90 + 36
                            let vertical = -abs(normalized) * 24
                            let opacity = 0.85 + (1 - abs(normalized)) * 0.15

                            LiquidGlassCard(item: card)
                                .scaleEffect(scale, anchor: .center)
                                .rotation3DEffect(tilt, axis: (x: 1, y: 0, z: 0), anchor: .center, perspective: 0.8)
                                .rotationEffect(.degrees(Double(normalized) * 4), anchor: .center)
                                .offset(x: horizontal, y: vertical)
                                .opacity(opacity)
                                .shadow(color: card.accent.opacity(0.25), radius: 30, x: 0, y: 28)
                        }
                        .frame(height: 340)
                        .padding(.horizontal, 20)
                    }
                }
                .padding(.top, 120)
                .padding(.bottom, 200)
            }
            .coordinateSpace(name: "scroll")
        }
        .toolbar { railToolbar }
    }

    @ToolbarContentBuilder
    private var railToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Button {
                library.refreshCards()
            } label: {
                Label("Shuffle", systemImage: "arrow.triangle.2.circlepath")
            }

            if !library.userImages.isEmpty {
                Button(role: .destructive) {
                    library.resetLibrary()
                } label: {
                    Label("Clear", systemImage: "trash")
                }
            }
        }
    }
}

struct CardRailView_Previews: PreviewProvider {
    static var previews: some View {
        NavigationStack {
            CardRailView()
                .environmentObject(MediaLibrary())
        }
    }
}
