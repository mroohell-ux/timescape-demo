import SwiftUI

struct FontSizeSheet: View {
    @Binding var fontSize: CGFloat

    var body: some View {
        VStack(spacing: 16) {
            Text("Card Font Size")
                .font(.headline)
            Slider(value: $fontSize, in: 14...26, step: 1)
            Text("\(Int(fontSize)) pt")
                .font(.system(.title2, design: .rounded))
                .monospacedDigit()
        }
        .padding()
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding()
    }
}
