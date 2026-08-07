import SwiftUI
import PencilKit

extension HandwritingContent {
    func drawing() -> PKDrawing? {
        try? PKDrawing(data: drawingData)
    }

    func renderedImage(scale: CGFloat = UIScreen.main.scale) -> UIImage? {
        guard let drawing = drawing() else { return nil }
        let canvasSize = CGSize(width: size.width, height: size.height)
        let bounds = CGRect(origin: .zero, size: canvasSize)
        return drawing.image(from: bounds, scale: scale)
    }
}

struct HandwritingCanvas: UIViewRepresentable {
    @Binding var drawing: PKDrawing
    var backgroundColor: UIColor
    var brushColor: UIColor
    var brushWidth: CGFloat

    func makeUIView(context: Context) -> PKCanvasView {
        let canvas = PKCanvasView()
        canvas.backgroundColor = backgroundColor
        canvas.drawing = drawing
        canvas.tool = PKInkingTool(.pen, color: brushColor, width: brushWidth)
        canvas.drawingPolicy = .pencilOnly
        return canvas
    }

    func updateUIView(_ uiView: PKCanvasView, context: Context) {
        uiView.backgroundColor = backgroundColor
        if uiView.drawing != drawing {
            uiView.drawing = drawing
        }
        if let tool = uiView.tool as? PKInkingTool {
            if tool.color != brushColor || tool.width != brushWidth {
                uiView.tool = PKInkingTool(tool.inkType, color: brushColor, width: brushWidth)
            }
        }
    }
}
