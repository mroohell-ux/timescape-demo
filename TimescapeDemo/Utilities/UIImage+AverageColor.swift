import SwiftUI
import UIKit

extension UIImage {
    var averageColor: Color? {
        guard let cgImage = cgImage else { return nil }

        let width = 1
        let height = 1
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        var pixelData = [UInt8](repeating: 0, count: 4)

        guard let context = CGContext(data: &pixelData,
                                      width: width,
                                      height: height,
                                      bitsPerComponent: 8,
                                      bytesPerRow: width * 4,
                                      space: colorSpace,
                                      bitmapInfo: bitmapInfo) else {
            return nil
        }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        let red = Double(pixelData[0]) / 255
        let green = Double(pixelData[1]) / 255
        let blue = Double(pixelData[2]) / 255
        let alpha = Double(pixelData[3]) / 255

        return Color(red: red, green: green, blue: blue).opacity(alpha)
    }
}
