import UIKit
import CoreText

// «الخطوط» — the subtitle look the viewer chooses in «حسابي»: the font, a preview, the background colour and the text colour
// (the same section as the Android CigramFonts). Everything is saved on this iPhone, applies at once in the player's subtitles.

extension UIColor {
    /// 0xAARRGGBB (the Android colour ints).
    convenience init(argb: UInt32) {
        self.init(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                  blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

// MARK: - the fonts

enum SubtitleFonts {
    struct Entry {
        let key: String
        let name: String
        let file: String            // file name in the app bundle, without ".ttf"
    }

    static let defaultKey = "lmohanad"

    /// The same list as the Android app (assets/fonts).
    static let builtin: [Entry] = [
        Entry(key: "lmohanad", name: "مهنّد", file: "lmohanad"),
        Entry(key: "lmohanadbold", name: "مهنّد عريض", file: "lmohanadbold"),
        Entry(key: "lfont_com_alyamamabold", name: "اليمامة عريض", file: "lfont_com_alyamamabold"),
        Entry(key: "lfont_com_alyamamasemibold", name: "اليمامة نصف عريض", file: "lfont_com_alyamamasemibold"),
        Entry(key: "lfont_com_nsd96generosityotf", name: "جينيروسِتي", file: "lfont_com_nsd96generosityotf"),
        Entry(key: "amiri_regular", name: "أميري", file: "amiri_regular"),
        Entry(key: "amiri_bold", name: "أميري عريض", file: "amiri_bold"),
        Entry(key: "cairo_regular", name: "القاهرة", file: "cairo_regular"),
        Entry(key: "cairo_bold", name: "القاهرة عريض", file: "cairo_bold"),
        Entry(key: "scheherazade_regular", name: "شهرزاد", file: "scheherazade_regular"),
        Entry(key: "scheherazade_bold", name: "شهرزاد عريض", file: "scheherazade_bold"),
        Entry(key: "beiruti", name: "بيروتي", file: "beiruti"),
    ]

    private static var postScript: [String: String] = [:]
    private static let defaults = UserDefaults.standard
    private static let customKey = "cg.sub.custom"

    private static var folder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("Cigram/subfonts", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// The fonts the viewer imported: [{file, name}].
    static var custom: [(file: String, name: String)] {
        guard let raw = defaults.array(forKey: customKey) as? [[String: String]] else { return [] }
        var out: [(file: String, name: String)] = []
        for d in raw {
            if let f = d["file"], let n = d["name"] { out.append((file: f, name: n)) }
        }
        return out
    }

    private static func saveCustom(_ list: [(file: String, name: String)]) {
        defaults.set(list.map { ["file": $0.file, "name": $0.name] }, forKey: customKey)
    }

    /// Registers the bundled fonts (and the imported ones) with the system, once.
    private static let registered: Bool = {
        for e in SubtitleFonts.builtin {
            // xcodegen copies the files flat into the app; the folder name is tried too in case a project keeps it
            let url = Bundle.main.url(forResource: e.file, withExtension: "ttf") ?? Bundle.main.url(forResource: e.file, withExtension: "ttf", subdirectory: "Fonts")
            if let u = url { SubtitleFonts.register(u, key: e.key) }
        }
        for c in SubtitleFonts.custom { SubtitleFonts.register(SubtitleFonts.folder.appendingPathComponent(c.file), key: "custom:" + c.file) }
        return true
    }()

    @discardableResult
    private static func register(_ url: URL, key: String) -> Bool {
        guard FileManager.default.fileExists(atPath: url.path), let provider = CGDataProvider(url: url as CFURL),
              let cg = CGFont(provider), let ps = cg.postScriptName as String? else { return false }
        CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)       // already registered is fine
        postScript[key] = ps
        return true
    }

    static func font(forKey key: String, size: CGFloat) -> UIFont {
        _ = registered
        if let ps = postScript[key], let f = UIFont(name: ps, size: size) { return f }
        if key != defaultKey, let ps = postScript[defaultKey], let f = UIFont(name: ps, size: size) { return f }
        return Theme.font(size, .semibold)
    }

    /// Imports a .ttf / .otf the viewer picked. Returns the new key, or nil when the file is not a font.
    static func importFont(from source: URL, name: String) -> String? {
        _ = registered
        let file = String(Int(Date().timeIntervalSince1970)) + "_" + source.lastPathComponent
        let dest = folder.appendingPathComponent(file)
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: source, to: dest)
        } catch { return nil }
        let key = "custom:" + file
        guard register(dest, key: key) else {
            try? FileManager.default.removeItem(at: dest)
            return nil
        }
        var list = custom
        list.append((file: file, name: name.isEmpty ? "خط مخصص" : name))
        saveCustom(list)
        return key
    }

    static func deleteCustom(file: String) {
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(file))
        postScript["custom:" + file] = nil
        saveCustom(custom.filter { $0.file != file })
        if SubtitleStyle.fontKey == "custom:" + file { SubtitleStyle.fontKey = defaultKey }
    }
}

// MARK: - the style

enum SubtitleStyle {
    private static let d = UserDefaults.standard

    static let sample = "كن صادقاً، ووفياً ياصديقي."

    static var fontKey: String {
        get { return d.string(forKey: "cg.sub.font") ?? SubtitleFonts.defaultKey }
        set { d.set(newValue, forKey: "cg.sub.font") }
    }
    /// "none" | "black" | "color"
    static var bgMode: String {
        get { return d.string(forKey: "cg.sub.bgmode") ?? "black" }
        set { d.set(newValue, forKey: "cg.sub.bgmode") }
    }
    static var bgColor: UInt32 {
        get { return (d.object(forKey: "cg.sub.bgcolor") as? Int).map { UInt32(truncatingIfNeeded: $0) } ?? 0xFF000000 }
        set { d.set(Int(newValue), forKey: "cg.sub.bgcolor") }
    }
    static var textColor: UInt32 {
        get { return (d.object(forKey: "cg.sub.textcolor") as? Int).map { UInt32(truncatingIfNeeded: $0) } ?? 0xFFFFFFFF }
        set { d.set(Int(newValue), forKey: "cg.sub.textcolor") }
    }
    static var previewText: String {
        get { return d.string(forKey: "cg.sub.preview") ?? "" }
        set { d.set(newValue, forKey: "cg.sub.preview") }
    }

    static var background: UIColor {
        switch bgMode {
        case "none": return UIColor.clear
        case "color": return UIColor(argb: bgColor)
        default: return UIColor.black
        }
    }

    /// The subtitle text as the player (and the preview) draws it.
    static func attributed(_ text: String, size: CGFloat) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.alignment = .center
        var attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key.font: SubtitleFonts.font(forKey: fontKey, size: size),
            NSAttributedString.Key.foregroundColor: UIColor(argb: textColor),
            NSAttributedString.Key.paragraphStyle: style,
        ]
        var alpha: CGFloat = 0
        background.getRed(nil, green: nil, blue: nil, alpha: &alpha)
        if alpha > 0.01 {
            attrs[NSAttributedString.Key.backgroundColor] = background
        } else {
            // without a background the text is outlined, so it stays readable on any picture
            attrs[NSAttributedString.Key.strokeColor] = UIColor.black
            attrs[NSAttributedString.Key.strokeWidth] = -3.0
        }
        return NSAttributedString(string: text, attributes: attrs)
    }

    static let bgColors: [(name: String, color: UInt32)] = [
        ("أسود", 0xCC000000), ("أسود كامل", 0xFF000000), ("ظل خفيف", 0x80000000), ("رمادي", 0xCC1C1C1C), ("أبيض", 0xCCFFFFFF),
        ("زجاجي", 0x66FFFFFF), ("كحلي", 0xCC0B2545), ("بنفسجي", 0xCC2A1A4A), ("نبيذي", 0xCC3A0B16), ("أخضر", 0xCC0A2F22),
        ("قهوة", 0xCC3B2A12), ("بترولي", 0xCC10343A), ("توتي", 0xCC3A1036), ("فحمي", 0xCC1F2937),
    ]

    static let textColors: [(name: String, color: UInt32)] = [
        ("أبيض", 0xFFFFFFFF), ("فضي", 0xFFE5E5EA), ("أسود", 0xFF000000), ("أصفر", 0xFFFFEB3B), ("ذهبي", 0xFFFFD60A),
        ("سماوي", 0xFF4FE3FF), ("أخضر", 0xFF7CFF9B), ("وردي", 0xFFFF8AE0), ("برتقالي", 0xFFFFA94D), ("مرجاني", 0xFFFF6B6B),
        ("بنفسجي", 0xFFB79CFF), ("نعناعي", 0xFF9BE7C4), ("عاجي", 0xFFF5E6C8),
    ]
}

// MARK: - the screen

private final class FontRow: UIControl {
    var onTap: (() -> Void)?
    var onDelete: (() -> Void)?
    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.cornerRadius = 14
        addTarget(self, action: #selector(tapped), for: .touchUpInside)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    @objc private func tapped() { onTap?() }
}

private final class SwatchItem: UIControl {
    var onTap: (() -> Void)?
    override init(frame: CGRect) {
        super.init(frame: frame)
        addTarget(self, action: #selector(tapped), for: .touchUpInside)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    @objc private func tapped() { onTap?() }
}

/// «الخطوط»: preview on top (always visible while the viewer chooses), then the font list, the background colours and the text colours.
final class FontsViewController: UIViewController, UIDocumentPickerDelegate, UITextFieldDelegate {
    private let previewField = UITextField()
    private let previewBox = UIView()
    private let previewGradient = CAGradientLayer()
    private let previewLabel = UILabel()
    private let scroll = TouchScroll()
    private var builtWidth: CGFloat = -1
    private var dirty = true

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "الخطوط"
        view.backgroundColor = Theme.bg
        view.semanticContentAttribute = .forceLeftToRight

        previewField.backgroundColor = Theme.surface
        previewField.textColor = .white
        previewField.font = Theme.font(15)
        previewField.textAlignment = .right
        previewField.attributedPlaceholder = NSAttributedString(string: "اكتب اسماً للمعاينة",
                                                                 attributes: [NSAttributedString.Key.foregroundColor: UIColor(white: 1, alpha: 0.5)])
        previewField.layer.cornerRadius = 12
        previewField.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 10))
        previewField.leftViewMode = .always
        previewField.rightView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 10))
        previewField.rightViewMode = .always
        previewField.returnKeyType = .done
        previewField.delegate = self
        previewField.text = SubtitleStyle.previewText
        previewField.addTarget(self, action: #selector(previewChanged), for: .editingChanged)
        view.addSubview(previewField)

        previewGradient.colors = [UIColor(argb: 0xFF3A3A3F).cgColor, UIColor(argb: 0xFF18181B).cgColor, UIColor(argb: 0xFF050506).cgColor]
        previewBox.layer.addSublayer(previewGradient)
        previewBox.layer.cornerRadius = 16
        previewBox.layer.masksToBounds = true
        previewBox.layer.borderWidth = 1
        previewBox.layer.borderColor = UIColor(white: 1, alpha: 0.25).cgColor
        previewLabel.numberOfLines = 0
        previewLabel.textAlignment = .center
        previewBox.addSubview(previewLabel)
        view.addSubview(previewBox)

        let hint = UILabel()
        hint.tag = 77
        hint.text = "كل تغيير يُحفظ وحده ويظهر فوراً في المعاينة"
        hint.font = Theme.font(12.5)
        hint.textColor = UIColor(white: 1, alpha: 0.6)
        hint.textAlignment = .right
        view.addSubview(hint)

        scroll.alwaysBounceVertical = true
        scroll.keyboardDismissMode = .onDrag
        scroll.showsVerticalScrollIndicator = false
        view.addSubview(scroll)
        updatePreview()
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    @objc private func previewChanged() {
        SubtitleStyle.previewText = previewField.text ?? ""
        updatePreview()
    }

    private func updatePreview() {
        let t = (previewField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        previewLabel.attributedText = SubtitleStyle.attributed(t.isEmpty ? SubtitleStyle.sample : t, size: 26)
        view.setNeedsLayout()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let b = view.bounds
        let top = view.safeAreaInsets.top
        previewField.frame = CGRect(x: 16, y: top + 10, width: b.width - 32, height: 42)
        previewBox.frame = CGRect(x: 16, y: top + 60, width: b.width - 32, height: 108)
        previewGradient.frame = previewBox.bounds
        let lw = previewBox.bounds.width - 28
        let lh = min(previewBox.bounds.height - 16, ceil(previewLabel.sizeThatFits(CGSize(width: lw, height: 300)).height))
        previewLabel.frame = CGRect(x: 14, y: previewBox.bounds.height - lh - 14, width: lw, height: lh)
        if let hint = view.viewWithTag(77) { hint.frame = CGRect(x: 16, y: previewBox.frame.maxY + 6, width: b.width - 32, height: 20) }
        let sy = previewBox.frame.maxY + 30
        scroll.frame = CGRect(x: 0, y: sy, width: b.width, height: max(0, b.height - sy))
        if dirty || abs(builtWidth - scroll.bounds.width) > 0.5 { build() }
    }

    private func refresh() {
        dirty = true
        updatePreview()
    }

    // MARK: rows

    /// Rebuilds the lists (a choice changed). The scroll position stays where the viewer is.
    private func build() {
        dirty = false
        builtWidth = scroll.bounds.width
        let keep = scroll.contentOffset.y
        for v in scroll.subviews { v.removeFromSuperview() }
        let w = scroll.bounds.width
        let pad: CGFloat = 16
        let rw = w - pad * 2
        var y: CGFloat = 8

        func section(_ text: String) {
            let l = UILabel(frame: CGRect(x: pad, y: y, width: rw, height: 26))
            l.text = text
            l.font = Theme.font(16, .bold)
            l.textColor = .white
            l.textAlignment = .right
            scroll.addSubview(l)
            y += 32
        }

        section("خط الترجمة")
        var fonts: [(key: String, name: String, file: String?)] = []
        for e in SubtitleFonts.builtin { fonts.append((key: e.key, name: e.name, file: nil)) }
        for c in SubtitleFonts.custom { fonts.append((key: "custom:" + c.file, name: c.name, file: c.file)) }
        for f in fonts {
            let selected = SubtitleStyle.fontKey == f.key
            let row = FontRow(frame: CGRect(x: pad, y: y, width: rw, height: 66))
            row.backgroundColor = selected ? UIColor(argb: 0xFF1B536B) : UIColor(argb: 0xFF262629)
            if selected {
                row.layer.borderWidth = 1
                row.layer.borderColor = UIColor.white.cgColor
            }
            let sample = UILabel(frame: CGRect(x: 14, y: 6, width: rw - 28, height: 36))
            sample.text = SubtitleStyle.sample
            sample.font = SubtitleFonts.font(forKey: f.key, size: 22)
            sample.textColor = selected ? .white : UIColor(argb: 0xFFD7E0E4)
            sample.textAlignment = .right
            sample.adjustsFontSizeToFitWidth = true
            sample.minimumScaleFactor = 0.6
            row.addSubview(sample)
            let name = UILabel(frame: CGRect(x: 14, y: 42, width: rw - 28, height: 18))
            name.text = f.name
            name.font = Theme.font(12, .semibold)
            name.textColor = selected ? UIColor(argb: 0xFF9FE6FF) : UIColor(argb: 0xFF7F95A0)
            name.textAlignment = .right
            row.addSubview(name)
            if let file = f.file {
                let del = UIButton(type: .system)
                del.setTitle("حذف", for: .normal)
                del.setTitleColor(UIColor(argb: 0xFFFF7A7A), for: .normal)
                del.titleLabel?.font = Theme.font(13, .bold)
                del.frame = CGRect(x: 6, y: 36, width: 56, height: 28)
                del.setTapAction { [weak self] in
                    SubtitleFonts.deleteCustom(file: file)
                    self?.refresh()
                }
                row.addSubview(del)
            }
            let key = f.key
            row.onTap = { [weak self] in
                SubtitleStyle.fontKey = key
                self?.refresh()
            }
            scroll.addSubview(row)
            y += 72
        }
        let add = UIButton(type: .system)
        add.setTitle("＋  إضافة خط من الجهاز", for: .normal)
        add.setTitleColor(.white, for: .normal)
        add.titleLabel?.font = Theme.font(16, .bold)
        add.backgroundColor = UIColor(argb: 0xFF123240)
        add.layer.cornerRadius = 14
        add.layer.borderWidth = 1
        add.layer.borderColor = UIColor(argb: 0x3329A9E0).cgColor
        add.frame = CGRect(x: pad, y: y, width: rw, height: 52)
        add.setTapAction { [weak self] in self?.pickFont() }
        scroll.addSubview(add)
        y += 70

        // background colours: «بدون» first, then the palette
        section("خلفية الترجمة")
        let noBg = SubtitleStyle.bgMode == "none"
        var bgItems: [(name: String, color: UInt32?, selected: Bool, action: () -> Void)] = []
        bgItems.append((name: "بدون", color: nil, selected: noBg, action: { SubtitleStyle.bgMode = "none" }))
        let currentBg: UInt32 = SubtitleStyle.bgMode == "color" ? SubtitleStyle.bgColor : 0xFF000000
        for c in SubtitleStyle.bgColors {
            let col = c.color
            bgItems.append((name: c.name, color: col, selected: !noBg && currentBg == col, action: {
                SubtitleStyle.bgMode = "color"
                SubtitleStyle.bgColor = col
            }))
        }
        y = swatches(bgItems, y: y, width: w)

        section("لون النص")
        var textItems: [(name: String, color: UInt32?, selected: Bool, action: () -> Void)] = []
        for c in SubtitleStyle.textColors {
            let col = c.color
            textItems.append((name: c.name, color: col, selected: SubtitleStyle.textColor == col, action: { SubtitleStyle.textColor = col }))
        }
        y = swatches(textItems, y: y, width: w)

        scroll.contentSize = CGSize(width: w, height: y + max(view.safeAreaInsets.bottom, 12) + 24)
        let maxOffset = max(0, scroll.contentSize.height - scroll.bounds.height)
        scroll.contentOffset = CGPoint(x: 0, y: min(max(0, keep), maxOffset))
    }

    /// One horizontal row of colour dots (the first one at the right edge, like the Android row); returns the next y.
    private func swatches(_ items: [(name: String, color: UInt32?, selected: Bool, action: () -> Void)], y: CGFloat, width: CGFloat) -> CGFloat {
        let h = TouchScroll(frame: CGRect(x: 0, y: y, width: width, height: 78))
        h.showsHorizontalScrollIndicator = false
        let itemW: CGFloat = 68
        let total = max(width, CGFloat(items.count) * itemW + 16)
        for (i, it) in items.enumerated() {
            let x = total - 8 - CGFloat(i + 1) * itemW
            let box = SwatchItem(frame: CGRect(x: x, y: 0, width: itemW, height: 76))
            let dot = UIView(frame: CGRect(x: (itemW - 38) / 2, y: 4, width: 38, height: 38))
            dot.isUserInteractionEnabled = false
            dot.layer.cornerRadius = 19
            if let c = it.color {
                dot.backgroundColor = UIColor(argb: (c & 0x00FFFFFF) | 0xFF000000)
                dot.layer.borderWidth = it.selected ? 3 : 1
                dot.layer.borderColor = (it.selected ? UIColor.white : UIColor(white: 1, alpha: 0.2)).cgColor
            } else {
                dot.backgroundColor = .clear
                dot.layer.borderWidth = it.selected ? 3 : 1
                dot.layer.borderColor = (it.selected ? UIColor.white : UIColor(white: 1, alpha: 0.33)).cgColor
            }
            box.addSubview(dot)
            let l = UILabel(frame: CGRect(x: 0, y: 46, width: itemW, height: 18))
            l.text = it.name
            l.font = Theme.font(11)
            l.textAlignment = .center
            l.textColor = it.selected ? .white : UIColor(argb: 0xFF8E8E94)
            box.addSubview(l)
            let action = it.action
            box.onTap = { [weak self] in
                action()
                self?.refresh()
            }
            h.addSubview(box)
        }
        h.contentSize = CGSize(width: total, height: 78)
        h.contentOffset = CGPoint(x: max(0, total - width), y: 0)       // starts at the right edge
        scroll.addSubview(h)
        return y + 90
    }

    // MARK: importing a font

    private func pickFont() {
        let types = ["public.truetype-ttf-font", "public.opentype-font", "public.font", "public.data"]
        let p = UIDocumentPickerViewController(documentTypes: types, in: .import)
        p.delegate = self
        present(p, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first else { return }
        let name = u.deletingPathExtension().lastPathComponent
        if let key = SubtitleFonts.importFont(from: u, name: name) {
            SubtitleStyle.fontKey = key
            refresh()
        } else {
            let a = UIAlertController(title: "الملف ليس خطاً صالحاً", message: "اختر ملف خط بصيغة TTF أو OTF.", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "حسناً", style: .default))
            present(a, animated: true)
        }
    }
}

/// A tiny helper: a closure instead of a selector for the buttons built in code.
private final class ButtonAction: NSObject {
    let run: () -> Void
    init(_ run: @escaping () -> Void) { self.run = run }
    @objc func fire() { run() }
}

private var buttonActionKey: UInt8 = 0

private extension UIButton {
    func setTapAction(_ run: @escaping () -> Void) {
        let a = ButtonAction(run)
        objc_setAssociatedObject(self, &buttonActionKey, a, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        addTarget(a, action: #selector(ButtonAction.fire), for: .touchUpInside)
    }
}
