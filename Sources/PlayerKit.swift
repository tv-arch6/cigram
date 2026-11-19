import UIKit
import AVFoundation
import Network

// Widgets and helpers of the native player (PlayerViewController): the video surface, glass buttons, the seek bar, the
// settings panel, the network monitor and the skip-intro / next-episode logic (ported from the Android CigramSkipIntro).

// MARK: - network

/// «Is the phone online?» for the repair ladder (a cut connection is waited for, never counted as a failed attempt),
/// and the data-saver state for the smart quality cap.
final class NetworkMonitor {
    static let shared = NetworkMonitor()
    static let changed = Notification.Name("cigram.network.changed")

    private let monitor = NWPathMonitor()
    private(set) var isOnline = true
    private(set) var isExpensive = false
    private(set) var isConstrained = false

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self = self else { return }
                let was = self.isOnline
                self.isOnline = path.status == .satisfied
                self.isExpensive = path.isExpensive
                self.isConstrained = path.isConstrained
                if was != self.isOnline { NotificationCenter.default.post(name: NetworkMonitor.changed, object: nil) }
            }
        }
        monitor.start(queue: DispatchQueue(label: "cigram.network.monitor"))
    }

    /// Touch it once so the monitor starts before the first film.
    func start() {}

    /// The conditions of the moment for SmartPlayback.decide.
    func conditions() -> SmartPlayback.Conditions {
        var c = SmartPlayback.Conditions()
        c.constrained = isConstrained
        c.expensive = isExpensive
        c.lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: c.thermal = 0
        case .fair: c.thermal = 1
        case .serious: c.thermal = 2
        case .critical: c.thermal = 3
        @unknown default: c.thermal = 0
        }
        let dev = UIDevice.current
        if !dev.isBatteryMonitoringEnabled { dev.isBatteryMonitoringEnabled = true }
        if dev.batteryLevel >= 0 { c.batteryPct = Int(dev.batteryLevel * 100) }
        c.charging = dev.batteryState == .charging || dev.batteryState == .full
        return c
    }
}

// MARK: - the video surface

final class PlayerSurface: UIView {
    override class var layerClass: AnyClass { return AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { return layer as! AVPlayerLayer }
}

// MARK: - icons

enum PlayerIcon {
    /// The first SF Symbol of the list this iOS knows (a name newer than the phone's iOS gives nil, so a fallback is listed).
    static func image(_ names: [String], size: CGFloat = 20, weight: UIImage.SymbolWeight = .semibold) -> UIImage? {
        let cfg = UIImage.SymbolConfiguration(pointSize: size, weight: weight)
        for n in names {
            if let i = UIImage(systemName: n, withConfiguration: cfg) { return i }
        }
        return nil
    }
}

// MARK: - buttons

/// A button that shrinks a little under the finger (the Android «Liquid Glass» press feel).
class PressButton: UIButton {
    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12, delay: 0, options: [.allowUserInteraction, .beginFromCurrentState], animations: {
                self.transform = self.isHighlighted ? CGAffineTransform(scaleX: 0.9, y: 0.9) : CGAffineTransform.identity
            }, completion: nil)
        }
    }
}

/// A dark translucent pill with a hairline border.
final class GlassChip: PressButton {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(white: 0.07, alpha: 0.72)
        layer.cornerRadius = 17
        layer.borderWidth = 1
        layer.borderColor = UIColor(white: 1, alpha: 0.18).cgColor
        titleLabel?.font = Theme.font(13.5, .semibold)
        setTitleColor(.white, for: .normal)
        tintColor = .white
        contentEdgeInsets = UIEdgeInsets(top: 0, left: 14, bottom: 0, right: 14)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func setText(_ t: String) {
        setTitle(t, for: .normal)
        invalidateIntrinsicContentSize()
    }

    /// Width this chip needs.
    var neededWidth: CGFloat {
        let t = (title(for: .normal) ?? "") as NSString
        let w = t.size(withAttributes: [NSAttributedString.Key.font: Theme.font(13.5, .semibold)]).width
        return ceil(w) + 32 + (image(for: .normal) != nil ? 22 : 0)
    }
}

/// A round icon button over the video.
final class RoundIconButton: PressButton {
    init(symbols: [String], size: CGFloat, label: String) {
        super.init(frame: .zero)
        setImage(PlayerIcon.image(symbols, size: size), for: .normal)
        tintColor = .white
        accessibilityLabel = label
    }
    override init(frame: CGRect) { super.init(frame: frame) }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func set(symbols: [String], size: CGFloat) { setImage(PlayerIcon.image(symbols, size: size), for: .normal) }
}

// MARK: - seek bar

final class SeekBar: UIControl {
    var duration: Double = 0 { didSet { setNeedsLayout() } }
    var position: Double = 0 { didSet { if !isScrubbing { setNeedsLayout() } } }
    /// Seconds buffered ahead of the start (the end of the loaded range around the playhead).
    var buffered: Double = 0 { didSet { setNeedsLayout() } }
    /// Skip ranges (intro, ...) drawn as yellow marks on the bar.
    var marks: [(start: Double, end: Double)] = [] { didSet { setNeedsLayout() } }
    private(set) var isScrubbing = false
    private(set) var scrubSeconds: Double = 0

    var onBegan: (() -> Void)?
    var onChanged: ((Double) -> Void)?
    /// nil = the touch was cancelled (no seek).
    var onEnded: ((Double?) -> Void)?

    private let track = CALayer()
    private let bufferLayer = CALayer()
    private let progress = CALayer()
    private let thumb = CALayer()
    private var markLayers: [CALayer] = []
    private let inset: CGFloat = 10

    override init(frame: CGRect) {
        super.init(frame: frame)
        semanticContentAttribute = .forceLeftToRight
        track.backgroundColor = UIColor(white: 1, alpha: 0.25).cgColor
        bufferLayer.backgroundColor = UIColor(white: 1, alpha: 0.38).cgColor
        progress.backgroundColor = Theme.red.cgColor
        thumb.backgroundColor = UIColor.white.cgColor
        thumb.shadowColor = UIColor.black.cgColor
        thumb.shadowOpacity = 0.4
        thumb.shadowRadius = 3
        thumb.shadowOffset = CGSize(width: 0, height: 1)
        for l in [track, bufferLayer, progress, thumb] { layer.addSublayer(l) }
        isAccessibilityElement = true
        accessibilityLabel = "شريط التقدم"
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private var shown: Double { return isScrubbing ? scrubSeconds : position }

    /// The x position (in this view) of a time, for the bubble above the thumb.
    func x(for seconds: Double) -> CGFloat {
        let w = max(1, bounds.width - inset * 2)
        let f = duration > 0 ? CGFloat(min(1, max(0, seconds / duration))) : 0
        return inset + w * f
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let w = max(1, bounds.width - inset * 2)
        let h: CGFloat = isScrubbing ? 6 : 4
        let y = (bounds.height - h) / 2
        track.frame = CGRect(x: inset, y: y, width: w, height: h)
        track.cornerRadius = h / 2
        let d = max(duration, 0.001)
        let f = duration > 0 ? CGFloat(min(1, max(0, shown / d))) : 0
        let b = duration > 0 ? CGFloat(min(1, max(0, buffered / d))) : 0
        bufferLayer.frame = CGRect(x: inset, y: y, width: w * b, height: h)
        bufferLayer.cornerRadius = h / 2
        progress.frame = CGRect(x: inset, y: y, width: w * f, height: h)
        progress.cornerRadius = h / 2
        let t: CGFloat = isScrubbing ? 20 : 14
        thumb.frame = CGRect(x: inset + w * f - t / 2, y: (bounds.height - t) / 2, width: t, height: t)
        thumb.cornerRadius = t / 2
        while markLayers.count < marks.count {
            let m = CALayer()
            m.backgroundColor = UIColor(red: 1, green: 0.82, blue: 0.2, alpha: 0.95).cgColor
            layer.insertSublayer(m, below: thumb)
            markLayers.append(m)
        }
        for (i, m) in markLayers.enumerated() {
            if i < marks.count && duration > 0 {
                let a = CGFloat(min(1, max(0, marks[i].start / d))), e = CGFloat(min(1, max(0, marks[i].end / d)))
                m.frame = CGRect(x: inset + w * a, y: y, width: max(2, w * (e - a)), height: h)
                m.isHidden = false
            } else {
                m.isHidden = true
            }
        }
        CATransaction.commit()
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        return bounds.insetBy(dx: -8, dy: -14).contains(point)
    }

    private func seconds(for touch: UITouch) -> Double {
        let p = touch.location(in: self)
        let w = max(1, bounds.width - inset * 2)
        let f = min(1, max(0, Double((p.x - inset) / w)))
        return f * duration
    }

    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        if duration <= 0 { return false }
        isScrubbing = true
        scrubSeconds = seconds(for: touch)
        setNeedsLayout()
        onBegan?()
        onChanged?(scrubSeconds)
        return true
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        scrubSeconds = seconds(for: touch)
        setNeedsLayout()
        onChanged?(scrubSeconds)
        return true
    }

    override func endTracking(_ touch: UITouch?, with event: UIEvent?) {
        if let t = touch { scrubSeconds = seconds(for: t) }
        isScrubbing = false
        setNeedsLayout()
        onEnded?(scrubSeconds)
    }

    override func cancelTracking(with event: UIEvent?) {
        isScrubbing = false
        setNeedsLayout()
        onEnded?(nil)
    }
}

// MARK: - settings panel

enum SheetItem {
    case section(String)
    case action(title: String, value: String, handler: () -> Void)
    case toggle(title: String, isOn: Bool, handler: (Bool) -> Void)
    case choice(title: String, selected: Bool, handler: () -> Void)
    case segments(title: String, labels: [String], selected: Int, handler: (Int) -> Void)
    case info(title: String, value: String)
    case note(String)
}

final class SheetPage {
    let title: String
    let builder: () -> [SheetItem]
    init(title: String, builder: @escaping () -> [SheetItem]) {
        self.title = title
        self.builder = builder
    }
}

private final class SheetRow: UIControl {
    var onTap: (() -> Void)?
    override var isHighlighted: Bool {
        didSet { backgroundColor = isHighlighted ? UIColor(white: 1, alpha: 0.16) : UIColor(white: 1, alpha: 0.07) }
    }
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(white: 1, alpha: 0.07)
        layer.cornerRadius = 14
        addTarget(self, action: #selector(tapped), for: .touchUpInside)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    @objc private func tapped() { onTap?() }
}

/// The settings panel: a side panel in landscape, a bottom sheet in portrait; a main page with rows and sub pages with
/// choices («‹» goes back). Same idea as the Android CigramPlayerSheet.
final class PlayerSheet: UIView {
    private let dim = UIButton(type: .custom)
    private let panel = UIView()
    private let blur = UIVisualEffectView(effect: UIBlurEffect(style: .dark))
    private let header = UIView()
    private let headerLabel = UILabel()
    private let backButton = UIButton(type: .system)
    private let closeButton = UIButton(type: .system)
    private let scroll = UIScrollView()
    private var pages: [SheetPage] = []
    private var builtWidth: CGFloat = -1
    private var dirty = true
    private var closing = false
    var onClose: (() -> Void)?

    var isOpen: Bool { return superview != nil && !closing }

    override init(frame: CGRect) {
        super.init(frame: frame)
        semanticContentAttribute = .forceLeftToRight
        dim.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        dim.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        addSubview(dim)
        panel.backgroundColor = UIColor(red: 0x08 / 255.0, green: 0x0C / 255.0, blue: 0x10 / 255.0, alpha: 0.88)
        panel.layer.cornerRadius = 22
        panel.layer.masksToBounds = true
        panel.layer.borderWidth = 1
        panel.layer.borderColor = UIColor(white: 1, alpha: 0.14).cgColor
        addSubview(panel)
        blur.isUserInteractionEnabled = false
        panel.addSubview(blur)
        panel.addSubview(header)
        headerLabel.font = Theme.font(18, .bold)
        headerLabel.textColor = .white
        headerLabel.textAlignment = .center
        header.addSubview(headerLabel)
        backButton.setImage(PlayerIcon.image(["chevron.right"], size: 18, weight: .bold), for: .normal)
        backButton.tintColor = .white
        backButton.accessibilityLabel = "رجوع"
        backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        header.addSubview(backButton)
        closeButton.setImage(PlayerIcon.image(["xmark"], size: 16, weight: .bold), for: .normal)
        closeButton.tintColor = .white
        closeButton.accessibilityLabel = "إغلاق"
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        header.addSubview(closeButton)
        scroll.showsVerticalScrollIndicator = false
        scroll.alwaysBounceVertical = true
        panel.addSubview(scroll)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    // MARK: pages

    func show(_ page: SheetPage, in parent: UIView) {
        pages = [page]
        closing = false
        frame = parent.bounds
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        parent.addSubview(self)
        dirty = true
        layoutIfNeeded()
        let landscape = bounds.width > bounds.height
        let from = landscape ? CGAffineTransform(translationX: -panel.frame.width, y: 0) : CGAffineTransform(translationX: 0, y: panel.frame.height)
        panel.transform = from
        dim.alpha = 0
        UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseOut], animations: {
            self.panel.transform = CGAffineTransform.identity
            self.dim.alpha = 1
        }, completion: nil)
    }

    func push(_ page: SheetPage) {
        pages.append(page)
        dirty = true
        scroll.contentOffset = .zero
        setNeedsLayout()
    }

    func pop() {
        if pages.count > 1 {
            pages.removeLast()
            dirty = true
            scroll.contentOffset = .zero
            setNeedsLayout()
        } else {
            close()
        }
    }

    /// Re-reads the rows of the current page (a value changed).
    func reload() {
        dirty = true
        setNeedsLayout()
    }

    func close(animated: Bool = true) {
        if closing { return }
        closing = true
        let landscape = bounds.width > bounds.height
        let to = landscape ? CGAffineTransform(translationX: -panel.frame.width, y: 0) : CGAffineTransform(translationX: 0, y: panel.frame.height)
        let finish = { [weak self] in
            guard let self = self else { return }
            self.removeFromSuperview()
            self.panel.transform = CGAffineTransform.identity
            self.closing = false
            let c = self.onClose
            self.onClose = nil
            c?()
        }
        if !animated { finish(); return }
        UIView.animate(withDuration: 0.2, delay: 0, options: [.curveEaseIn], animations: {
            self.panel.transform = to
            self.dim.alpha = 0
        }, completion: { _ in finish() })
    }

    @objc private func closeTapped() { close() }
    @objc private func backTapped() { pop() }

    // MARK: layout

    override func layoutSubviews() {
        super.layoutSubviews()
        dim.frame = bounds
        let landscape = bounds.width > bounds.height
        let s = safeAreaInsets
        if landscape {
            let w = min(400, max(300, bounds.width * 0.46))
            panel.frame = CGRect(x: 0, y: 0, width: w + s.left, height: bounds.height)
            panel.layer.maskedCorners = [.layerMaxXMinYCorner, .layerMaxXMaxYCorner]
        } else {
            let h = min(bounds.height * 0.66, 560)
            panel.frame = CGRect(x: 0, y: bounds.height - h, width: bounds.width, height: h)
            panel.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        }
        blur.frame = panel.bounds
        let leftPad = landscape ? s.left : 0
        header.frame = CGRect(x: leftPad, y: landscape ? 6 : 8, width: panel.bounds.width - leftPad, height: 52)
        headerLabel.frame = CGRect(x: 56, y: 0, width: header.bounds.width - 112, height: 52)
        headerLabel.text = pages.last?.title ?? ""
        backButton.frame = CGRect(x: header.bounds.width - 52, y: 0, width: 48, height: 52)
        backButton.isHidden = pages.count <= 1
        closeButton.frame = CGRect(x: 4, y: 0, width: 48, height: 52)
        let top = header.frame.maxY
        scroll.frame = CGRect(x: leftPad, y: top, width: panel.bounds.width - leftPad, height: panel.bounds.height - top)
        if dirty || abs(builtWidth - scroll.bounds.width) > 0.5 { rebuildRows() }
    }

    private func rebuildRows() {
        dirty = false
        builtWidth = scroll.bounds.width
        let offset = scroll.contentOffset
        for v in scroll.subviews { v.removeFromSuperview() }
        guard let page = pages.last else { return }
        let w = scroll.bounds.width
        let pad: CGFloat = 14
        let rw = w - pad * 2
        var y: CGFloat = 4
        let bottomInset = max(safeAreaInsets.bottom, 12)
        for item in page.builder() {
            switch item {
            case .section(let text):
                y += 8
                let l = UILabel(frame: CGRect(x: pad + 4, y: y, width: rw - 8, height: 22))
                l.text = text
                l.font = Theme.font(13, .bold)
                l.textColor = Theme.text3
                l.textAlignment = .right
                scroll.addSubview(l)
                y += 26

            case .action(let title, let value, let handler):
                let row = SheetRow(frame: CGRect(x: pad, y: y, width: rw, height: 52))
                row.onTap = handler
                let t = UILabel(frame: CGRect(x: rw * 0.40, y: 0, width: rw * 0.60 - 14, height: 52))
                t.text = title
                t.font = Theme.font(16, .semibold)
                t.textColor = .white
                t.textAlignment = .right
                row.addSubview(t)
                let chev = UIImageView(image: PlayerIcon.image(["chevron.left"], size: 13, weight: .bold))
                chev.tintColor = Theme.text3
                chev.contentMode = .center
                chev.frame = CGRect(x: 8, y: 0, width: 24, height: 52)
                row.addSubview(chev)
                let v = UILabel(frame: CGRect(x: 34, y: 0, width: rw * 0.40 - 34, height: 52))
                v.text = value
                v.font = Theme.font(14)
                v.textColor = Theme.text3
                v.textAlignment = .left
                v.adjustsFontSizeToFitWidth = true
                v.minimumScaleFactor = 0.7
                row.addSubview(v)
                row.accessibilityLabel = title + "، " + value
                scroll.addSubview(row)
                y += 58

            case .toggle(let title, let isOn, let handler):
                let row = SheetRow(frame: CGRect(x: pad, y: y, width: rw, height: 52))
                let t = UILabel(frame: CGRect(x: 70, y: 0, width: rw - 84, height: 52))
                t.text = title
                t.font = Theme.font(16, .semibold)
                t.textColor = .white
                t.textAlignment = .right
                row.addSubview(t)
                let sw = UISwitch()
                sw.isOn = isOn
                sw.onTintColor = Theme.red
                sw.center = CGPoint(x: 14 + sw.bounds.width / 2, y: 26)
                row.addSubview(sw)
                row.onTap = { [weak sw] in
                    guard let sw = sw else { return }
                    sw.setOn(!sw.isOn, animated: true)
                    handler(sw.isOn)
                }
                sw.addTarget(ToggleTarget.shared, action: #selector(ToggleTarget.changed(_:)), for: .valueChanged)
                ToggleTarget.shared.register(sw, handler)
                row.accessibilityLabel = title + (isOn ? "، مفعّل" : "، غير مفعّل")
                scroll.addSubview(row)
                y += 58

            case .choice(let title, let selected, let handler):
                let row = SheetRow(frame: CGRect(x: pad, y: y, width: rw, height: 50))
                row.onTap = handler
                if selected {
                    row.layer.borderWidth = 1
                    row.layer.borderColor = UIColor(red: 0.36, green: 0.95, blue: 0.65, alpha: 0.6).cgColor
                }
                let t = UILabel(frame: CGRect(x: 52, y: 0, width: rw - 52 - 16, height: 50))
                t.text = title
                t.font = Theme.font(16, selected ? .bold : .regular)
                t.textColor = .white
                t.textAlignment = .right
                row.addSubview(t)
                let mark = UIImageView(image: PlayerIcon.image(selected ? ["checkmark.circle.fill"] : ["circle"], size: 22, weight: .regular))
                mark.tintColor = selected ? Theme.good : UIColor(white: 1, alpha: 0.3)
                mark.contentMode = .center
                mark.frame = CGRect(x: 10, y: 0, width: 32, height: 50)
                row.addSubview(mark)
                row.accessibilityLabel = title + (selected ? "، مفعّل" : "، غير مفعّل")
                scroll.addSubview(row)
                y += 56

            case .segments(let title, let labels, let selected, let handler):
                let box = UIView(frame: CGRect(x: pad, y: y, width: rw, height: 84))
                box.backgroundColor = UIColor(white: 1, alpha: 0.07)
                box.layer.cornerRadius = 14
                let t = UILabel(frame: CGRect(x: 14, y: 8, width: rw - 28, height: 24))
                t.text = title
                t.font = Theme.font(15, .semibold)
                t.textColor = .white
                t.textAlignment = .right
                box.addSubview(t)
                let seg = UISegmentedControl(items: labels)
                seg.selectedSegmentIndex = selected
                seg.frame = CGRect(x: 10, y: 38, width: rw - 20, height: 36)
                seg.semanticContentAttribute = .forceLeftToRight
                if #available(iOS 13.0, *) { seg.selectedSegmentTintColor = Theme.red }
                seg.setTitleTextAttributes([NSAttributedString.Key.foregroundColor: UIColor.white, NSAttributedString.Key.font: Theme.font(13, .semibold)], for: .normal)
                seg.setTitleTextAttributes([NSAttributedString.Key.foregroundColor: UIColor.white, NSAttributedString.Key.font: Theme.font(13, .bold)], for: .selected)
                ToggleTarget.shared.registerSegments(seg, handler)
                seg.addTarget(ToggleTarget.shared, action: #selector(ToggleTarget.segmentChanged(_:)), for: .valueChanged)
                box.addSubview(seg)
                scroll.addSubview(box)
                y += 90

            case .info(let title, let value):
                let row = UIView(frame: CGRect(x: pad, y: y, width: rw, height: 44))
                row.backgroundColor = UIColor(white: 1, alpha: 0.05)
                row.layer.cornerRadius = 12
                let t = UILabel(frame: CGRect(x: rw * 0.45, y: 0, width: rw * 0.55 - 14, height: 44))
                t.text = title
                t.font = Theme.font(14, .semibold)
                t.textColor = Theme.text2
                t.textAlignment = .right
                row.addSubview(t)
                let v = UILabel(frame: CGRect(x: 14, y: 0, width: rw * 0.45 - 14, height: 44))
                v.text = value
                v.font = Theme.font(13)
                v.textColor = Theme.text3
                v.textAlignment = .left
                v.adjustsFontSizeToFitWidth = true
                v.minimumScaleFactor = 0.6
                row.addSubview(v)
                scroll.addSubview(row)
                y += 50

            case .note(let text):
                let l = UILabel()
                l.text = text
                l.font = Theme.font(13)
                l.textColor = Theme.text3
                l.textAlignment = .right
                l.numberOfLines = 0
                let h = ceil(l.sizeThatFits(CGSize(width: rw - 12, height: CGFloat.greatestFiniteMagnitude)).height)
                l.frame = CGRect(x: pad + 6, y: y, width: rw - 12, height: h)
                scroll.addSubview(l)
                y += h + 10
            }
        }
        scroll.contentSize = CGSize(width: w, height: y + bottomInset + 12)
        let maxOffset = max(0, scroll.contentSize.height - scroll.bounds.height)
        scroll.contentOffset = CGPoint(x: 0, y: min(offset.y, maxOffset))
    }
}

/// Keeps the closures of the switches and segmented controls of the panel (UIKit targets are Objective-C selectors).
private final class ToggleTarget: NSObject {
    static let shared = ToggleTarget()
    private var toggles: [ObjectIdentifier: (Bool) -> Void] = [:]
    private var segments: [ObjectIdentifier: (Int) -> Void] = [:]

    func register(_ s: UISwitch, _ h: @escaping (Bool) -> Void) {
        if toggles.count > 200 { toggles.removeAll() }
        toggles[ObjectIdentifier(s)] = h
    }
    func registerSegments(_ s: UISegmentedControl, _ h: @escaping (Int) -> Void) {
        if segments.count > 200 { segments.removeAll() }
        segments[ObjectIdentifier(s)] = h
    }
    @objc func changed(_ s: UISwitch) { toggles[ObjectIdentifier(s)]?(s.isOn) }
    @objc func segmentChanged(_ s: UISegmentedControl) { segments[ObjectIdentifier(s)]?(s.selectedSegmentIndex) }
}

// MARK: - skip intro / next episode (CigramSkipIntro)

/// Where the intro of an episode is, and when the «next episode» card shows. Sources, by priority: the admin's fields on the
/// episode / series; then what this iPhone learned (the viewer jumped 20–200 s forward in the first 10 minutes: remembered
/// for the season). No UI here: the player draws the button and the card from the state `tick` returns.
final class SkipIntroEngine {
    struct State {
        var showSkip = false
        var skipTarget: Double = 0
        var autoSkip = false
        var showNext = false
        var autoNext = false
        var countdownLeft = 0
        var countdownFraction: Double = 0
        var goNext = false
    }

    private enum Source { case none, admin, local }

    private let defaults = UserDefaults.standard
    private static let nextBeforeEnd = 30.0
    private static let countdown = 10.0

    private(set) var introStart: Double = -1
    private(set) var introEnd: Double = -1
    private var creditsAt: Double = -1
    private var source = Source.none
    private var localCount = 0
    private var introKey = ""
    private var ticks = 0
    private var autoSkipped = false

    private var lastPos: Double = -1
    private var lastTickAt: Double = 0
    private var naturalSec: Double = 0
    private var burstStart: Double = -1
    private var burstEnd: Double = -1
    private var burstLastAt: Double = 0
    private var ignoreJumpsUntil: Double = 0

    private var nextDismissed = false
    private var countdownStartAt: Double = -1

    /// A new episode: `key` = series + season ("" = no learning), the admin's numbers in seconds (negative = not set).
    func reset(key: String, introStart: Double, introEnd: Double, credits: Double) {
        introKey = key
        ticks = 0
        autoSkipped = false
        lastPos = -1
        naturalSec = 0
        burstStart = -1
        burstEnd = -1
        nextDismissed = false
        countdownStartAt = -1
        localCount = 0
        self.introStart = -1
        self.introEnd = -1
        creditsAt = credits > 0 ? credits : -1
        source = .none
        if introEnd > 0 && introEnd > max(0, introStart) {
            self.introStart = max(0, introStart)
            self.introEnd = introEnd
            source = .admin
        } else if let v = readLocal() {
            self.introStart = v.0
            self.introEnd = v.1
            localCount = v.2
            source = .local
        }
    }

    /// Admin segments ("10-30,100-120") as marks for the seek bar.
    var marks: [(start: Double, end: Double)] {
        if introEnd > introStart && introEnd > 0 { return [(start: max(0, introStart), end: introEnd)] }
        return []
    }

    func dismissNext() { nextDismissed = true }

    /// The player jumped to the end of the intro itself: that jump is not something to learn.
    func skipped() {
        ignoreJumpsUntil = CACurrentMediaTime() + 2.5
        burstStart = -1
        burstEnd = -1
    }

    // 4 ticks a second, from the player's time observer
    func tick(pos: Double, dur: Double, playing: Bool, dragging: Bool, isSeries: Bool, hasNext: Bool) -> State {
        var st = State()
        if !isSeries { return st }
        ticks += 1
        let now = CACurrentMediaTime()
        learn(pos: pos, playing: playing, dragging: dragging, now: now)
        updateSkip(pos: pos, playing: playing, dragging: dragging, into: &st)
        updateNext(pos: pos, dur: dur, playing: playing, now: now, hasNext: hasNext, into: &st)
        lastPos = pos
        lastTickAt = now
        return st
    }

    // MARK: skip

    private func updateSkip(pos: Double, playing: Bool, dragging: Bool, into st: inout State) {
        if introEnd <= 0 || dragging { return }
        let inWindow = pos >= max(0, introStart - 2) && pos < introEnd - 3
        if !inWindow { return }
        let trusted = source == .admin || localCount >= 2
        if trusted && playing && !autoSkipped && PlayerPrefs.autoSkipIntro && pos >= introStart {
            autoSkipped = true
            st.autoSkip = true
            st.skipTarget = introEnd
            return
        }
        st.showSkip = true
        st.skipTarget = introEnd
    }

    // MARK: next episode

    private func updateNext(pos: Double, dur: Double, playing: Bool, now: Double, hasNext: Bool, into st: inout State) {
        if nextDismissed || dur < 120 || !hasNext { return }
        let start = creditsAt > 0 && creditsAt < dur ? creditsAt : dur - SkipIntroEngine.nextBeforeEnd
        if pos < start - 1.5 || pos >= dur - 0.3 {
            countdownStartAt = -1
            return
        }
        if pos < start { return }
        let auto = PlayerPrefs.autoNext
        st.showNext = true
        st.autoNext = auto
        if !auto { return }
        if countdownStartAt < 0 { countdownStartAt = now }
        if !playing { countdownStartAt += max(0, now - lastTickAt) }       // paused: freeze the countdown
        let elapsed = now - countdownStartAt
        st.countdownLeft = Int(max(0, SkipIntroEngine.countdown - floor(elapsed)))
        st.countdownFraction = min(1, elapsed / SkipIntroEngine.countdown)
        if elapsed >= SkipIntroEngine.countdown {
            nextDismissed = true
            st.goNext = true
        }
    }

    // MARK: learning from the viewer's own skips

    private func learn(pos: Double, playing: Bool, dragging: Bool, now: Double) {
        if lastPos < 0 || dragging { return }
        let delta = pos - lastPos
        let dt = max(0.001, now - lastTickAt)
        let natural = delta >= -0.6 && delta <= dt * 3 + 0.6
        if natural {
            if playing && delta > 0 { naturalSec += delta }
        } else if now >= ignoreJumpsUntil && ticks > 16 {
            if delta > 3 {
                if burstStart < 0 {
                    if naturalSec >= 1.5 || lastPos <= 3 {
                        burstStart = lastPos
                        burstEnd = pos
                        burstLastAt = now
                    }
                } else {
                    burstEnd = pos
                    burstLastAt = now
                }
            } else if delta < -3 {
                if burstStart >= 0 && pos > burstStart {          // a small overshoot corrected backwards
                    burstEnd = pos
                    burstLastAt = now
                } else {
                    burstStart = -1
                    burstEnd = -1
                }
            }
            naturalSec = 0
        }
        if burstStart >= 0 && now - burstLastAt > 3.5 {
            let len = burstEnd - burstStart
            if burstStart <= 600 && len >= 20 && len <= 200 && (source == .none || source == .local) { remember(burstStart, burstEnd) }
            burstStart = -1
            burstEnd = -1
        }
    }

    private func readLocal() -> (Double, Double, Int)? {
        if introKey.isEmpty { return nil }
        let raw = defaults.string(forKey: "cg.intro.L|" + introKey) ?? ""
        let p = raw.components(separatedBy: ",")
        if p.count < 3 { return nil }
        guard let a = Double(p[0]), let b = Double(p[1]), let c = Int(p[2]) else { return nil }
        return (a, b, c)
    }

    private func remember(_ start: Double, _ end: Double) {
        if introKey.isEmpty { return }
        var s = start, e = end
        var count = 1
        if let old = readLocal(), abs(old.1 - e) <= 10 {
            count = min(50, old.2 + 1)
            s = (old.0 + s) / 2
            e = (old.1 + e) / 2
        }
        defaults.set(String(s) + "," + String(e) + "," + String(count), forKey: "cg.intro.L|" + introKey)
        if source == .none || source == .local {
            introStart = s
            introEnd = e
            localCount = count
            source = .local
        }
    }
}
