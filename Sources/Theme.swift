import UIKit

/// Cigram 2026 palette (same values as the Android CigramDesign).
enum Theme {
    static let bg = UIColor(red: 0x0E / 255.0, green: 0x0E / 255.0, blue: 0x10 / 255.0, alpha: 1)
    static let surface = UIColor(red: 0x1A / 255.0, green: 0x1A / 255.0, blue: 0x1D / 255.0, alpha: 1)
    static let surfaceHi = UIColor(red: 0x22 / 255.0, green: 0x22 / 255.0, blue: 0x26 / 255.0, alpha: 1)
    static let red = UIColor(red: 0xE2 / 255.0, green: 0x1B / 255.0, blue: 0x14 / 255.0, alpha: 1)
    static let imdb = UIColor(red: 0xF5 / 255.0, green: 0xC5 / 255.0, blue: 0x18 / 255.0, alpha: 1)
    static let text = UIColor.white
    static let text2 = UIColor(red: 0xC7 / 255.0, green: 0xC7 / 255.0, blue: 0xCC / 255.0, alpha: 1)
    static let text3 = UIColor(red: 0x87 / 255.0, green: 0x96 / 255.0, blue: 0xA0 / 255.0, alpha: 1)
    static let good = UIColor(red: 0x3D / 255.0, green: 0xDC / 255.0, blue: 0x97 / 255.0, alpha: 1)

    static func font(_ size: CGFloat, _ weight: UIFont.Weight = .regular) -> UIFont {
        return UIFont.systemFont(ofSize: size, weight: weight)
    }

    /// Poster width of a home row: 3 posters + a peek of the 4th on a phone, more on an iPad.
    static func cardWidth(for screenWidth: CGFloat) -> CGFloat {
        if screenWidth >= 700 { return 150 }
        return floor((screenWidth - 24) / 3.35)
    }
}

/// A horizontal flow layout that lays its items out right-to-left in an RTL interface (the newest title at the far right).
final class RTLFlowLayout: UICollectionViewFlowLayout {
    override var flipsHorizontallyInOppositeLayoutDirection: Bool { return true }
}
