import SwiftUI
import PencilKit
import UIKit

struct HandwritingEditorSheet: View {
    var initialContent: HandwritingContent?
    var onSave: (HandwritingContent?) -> Void
    var onDelete: () -> Void
    var onCancel: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var drawing: PKDrawing = PKDrawing()
    @State private var backgroundColor: Color = Color(.systemBackground)
    @State private var brushColor: Color = .label
    @State private var brushWidth: CGFloat = 6
    @State private var canvasSize: CGSize = CGSize(width: 600, height: 400)

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                HandwritingCanvas(
                    drawing: $drawing,
                    backgroundColor: UIColor(backgroundColor),
                    brushColor: UIColor(brushColor),
                    brushWidth: brushWidth
                )
                .frame(maxWidth: .infinity)
                .frame(height: 400)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .shadow(radius: 16)
                .padding(.horizontal)
                .background(
                    GeometryReader { geometry in
                        Color.clear
                            .onChange(of: geometry.size) { newSize in
                                canvasSize = newSize
                            }
                            .onAppear {
                                canvasSize = geometry.size
                            }
                    }
                )

                VStack(alignment: .leading, spacing: 16) {
                    ColorPicker("Paper", selection: $backgroundColor)
                    ColorPicker("Ink", selection: $brushColor)
                    HStack {
                        Text("Stroke Width")
                        Slider(value: $brushWidth, in: 1...20)
                        Text("\(Int(brushWidth)) pt")
                            .font(.caption)
                            .monospacedDigit()
                    }
                }
                .padding()
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .padding(.horizontal)
                Spacer()
            }
            .padding(.top)
            .navigationTitle("Handwriting")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                        onCancel()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        dismiss()
                        let content = HandwritingContent(
                            drawingData: drawing.dataRepresentation(),
                            size: canvasSize,
                            backgroundColor: backgroundColor,
                            brushColor: brushColor
                        )
                        onSave(content)
                    }
                }
                ToolbarItem(placement: .bottomBar) {
                    Button(role: .destructive) {
                        dismiss()
                        onDelete()
                    } label: {
                        Label("Remove", systemImage: "trash")
                    }
                }
            }
        }
        .onAppear(perform: loadInitial)
    }

    private func loadInitial() {
        guard let initialContent else { return }
        if let drawing = initialContent.drawing() {
            self.drawing = drawing
        }
        backgroundColor = initialContent.backgroundColor
        brushColor = initialContent.brushColor
        canvasSize = initialContent.size
    }
}
