import SwiftUI

extension Color {
    init?(hex: String) {
        var string = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if string.hasPrefix("#") { string.removeFirst() }
        guard let value = UInt64(string, radix: 16) else { return nil }
        switch string.count {
        case 6:
            let r = Double((value >> 16) & 0xFF) / 255
            let g = Double((value >> 8) & 0xFF) / 255
            let b = Double(value & 0xFF) / 255
            self.init(red: r, green: g, blue: b)
        case 8:
            let r = Double((value >> 24) & 0xFF) / 255
            let g = Double((value >> 16) & 0xFF) / 255
            let b = Double((value >> 8) & 0xFF) / 255
            let a = Double(value & 0xFF) / 255
            self.init(red: r, green: g, blue: b, opacity: a)
        default:
            return nil
        }
    }

    func toHex(alpha: Bool = false) -> String {
        let uiColor = UIColor(self)
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        uiColor.getRed(&r, green: &g, blue: &b, alpha: &a)
        if alpha {
            let value = (Int(r * 255) << 24) | (Int(g * 255) << 16) | (Int(b * 255) << 8) | Int(a * 255)
            return String(format: "#%08X", value)
        } else {
            let value = (Int(r * 255) << 16) | (Int(g * 255) << 8) | Int(b * 255)
            return String(format: "#%06X", value)
        }
    }
}
