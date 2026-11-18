import UIKit

/// The admin's carousel at the top of Home (same slides as the Android hero): backdrop, title, «2025 ¦ …» line,
/// «شاهد الآن» and «التفاصيل», page bars, auto-advance every 6 s.
final class CarouselView: UIView, UIScrollViewDelegate {
    private let scroll = UIScrollView()
    private let bars = UIStackView()
    private var slides: [CatalogStore.Slide] = []
    private var timer: Timer?
    var onPlay: ((Show) -> Void)?
    var onOpen: ((Show) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        backgroundColor = Theme.surface
        scroll.isPagingEnabled = true
        scroll.showsHorizontalScrollIndicator = false
        scroll.delegate = self
        scroll.semanticContentAttribute = .forceLeftToRight
        addSubview(scroll)
        bars.axis = .horizontal
        bars.spacing = 5
        bars.distribution = .fillEqually
        bars.semanticContentAttribute = .forceRightToLeft
        addSubview(bars)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func configure(_ list: [CatalogStore.Slide]) {
        slides = list
        scroll.subviews.forEach { $0.removeFromSuperview() }
        bars.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, s) in list.enumerated() {
            let page = slideView(s, index: i)
            scroll.addSubview(page)
            let bar = UIView()
            bar.backgroundColor = UIColor.white.withAlphaComponent(0.3)
            bar.layer.cornerRadius = 1.5
            bars.addArrangedSubview(bar)
        }
        setNeedsLayout()
        highlight(0)
        timer?.invalidate()
        if list.count > 1 {
            timer = Timer.scheduledTimer(withTimeInterval: 6, repeats: true) { [weak self] _ in self?.advance() }
        }
    }

    private func slideView(_ s: CatalogStore.Slide, index: Int) -> UIView {
        let v = UIView()
        v.tag = 100 + index
        let img = UIImageView()
        img.contentMode = .scaleAspectFill
        img.clipsToBounds = true
        img.tag = 1
        v.addSubview(img)
        ImageLoader.shared.load(s.image, maxPixel: 900) { img.image = $0 }
        let shade = CAGradientLayer()
        shade.colors = [UIColor.clear.cgColor, Theme.bg.withAlphaComponent(0.55).cgColor, Theme.bg.cgColor]
        shade.locations = [0.3, 0.72, 1.0]
        shade.name = "shade"
        v.layer.addSublayer(shade)
        let title = UILabel()
        title.tag = 2
        title.text = s.show.title
        title.font = Theme.font(26, .heavy)
        title.textColor = .white
        title.textAlignment = .center
        title.numberOfLines = 2
        v.addSubview(title)
        let meta = UILabel()
        meta.tag = 3
        meta.text = s.show.meta
        meta.font = Theme.font(13, .medium)
        meta.textColor = Theme.text2
        meta.textAlignment = .center
        v.addSubview(meta)
        let play = PillButton(title: "شاهد الآن", symbol: "play.fill", filled: true)
        play.tag = 4
        play.addAction { [weak self] in self?.onPlay?(s.show) }
        v.addSubview(play)
        let info = PillButton(title: "التفاصيل", symbol: "info.circle", filled: false)
        info.tag = 5
        info.addAction { [weak self] in self?.onOpen?(s.show) }
        v.addSubview(info)
        return v
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        let h = bounds.height
        scroll.frame = bounds
        scroll.contentSize = CGSize(width: w * CGFloat(slides.count), height: h)
        for v in scroll.subviews where v.tag >= 100 {
            let i = CGFloat(v.tag - 100)
            v.frame = CGRect(x: w * i, y: 0, width: w, height: h)
            v.viewWithTag(1)?.frame = v.bounds
            v.layer.sublayers?.first(where: { $0.name == "shade" })?.frame = v.bounds
            v.viewWithTag(2)?.frame = CGRect(x: 20, y: h - 158, width: w - 40, height: 64)
            v.viewWithTag(3)?.frame = CGRect(x: 20, y: h - 92, width: w - 40, height: 20)
            let bw = min(160, (w - 52) / 2)
            v.viewWithTag(4)?.frame = CGRect(x: w / 2 + 6, y: h - 64, width: bw, height: 44)
            v.viewWithTag(5)?.frame = CGRect(x: w / 2 - 6 - bw, y: h - 64, width: bw, height: 44)
        }
        let bw = min(CGFloat(slides.count) * 22, w - 80)
        bars.frame = CGRect(x: (w - bw) / 2, y: h - 12, width: bw, height: 3)
    }

    private var page: Int { return bounds.width > 0 ? Int(round(scroll.contentOffset.x / bounds.width)) : 0 }

    private func advance() {
        guard slides.count > 1, bounds.width > 0 else { return }
        let next = (page + 1) % slides.count
        scroll.setContentOffset(CGPoint(x: CGFloat(next) * bounds.width, y: 0), animated: true)
        highlight(next)
    }

    func scrollViewDidEndDecelerating(_ s: UIScrollView) { highlight(page) }

    private func highlight(_ i: Int) {
        for (n, b) in bars.arrangedSubviews.enumerated() {
            b.backgroundColor = n == i ? .white : UIColor.white.withAlphaComponent(0.3)
        }
    }
}

/// A rounded button with an SF Symbol: white («شاهد الآن») or glass («التفاصيل»).
final class PillButton: UIButton {
    private var action: (() -> Void)?

    convenience init(title: String, symbol: String?, filled: Bool) {
        self.init(type: .system)
        setTitle("  " + title, for: .normal)
        if let s = symbol { setImage(UIImage(systemName: s), for: .normal) }
        titleLabel?.font = Theme.font(16, .bold)
        layer.cornerRadius = 12
        tintColor = filled ? .black : .white
        setTitleColor(filled ? .black : .white, for: .normal)
        backgroundColor = filled ? .white : UIColor.white.withAlphaComponent(0.2)
        semanticContentAttribute = .forceRightToLeft
        addTarget(self, action: #selector(fire), for: .touchUpInside)
    }

    func addAction(_ a: @escaping () -> Void) { action = a }
    @objc private func fire() { action?() }
}

/// The chips under the carousel: «أفلام», «مسلسلات», «القنوات», «قائمتي».
final class ChipBar: UIScrollView {
    private let stack = UIStackView()
    var onTap: ((Int) -> Void)?

    init(items: [(String, String)]) {
        super.init(frame: .zero)
        showsHorizontalScrollIndicator = false
        semanticContentAttribute = .forceRightToLeft
        stack.axis = .horizontal
        stack.spacing = 8
        stack.semanticContentAttribute = .forceRightToLeft
        addSubview(stack)
        for (i, it) in items.enumerated() {
            let b = UIButton(type: .system)
            b.setTitle("  " + it.0, for: .normal)
            b.setImage(UIImage(systemName: it.1), for: .normal)
            b.tintColor = .white
            b.setTitleColor(.white, for: .normal)
            b.titleLabel?.font = Theme.font(14, .semibold)
            b.backgroundColor = Theme.surfaceHi
            b.layer.cornerRadius = 17
            b.layer.borderWidth = 1
            b.layer.borderColor = UIColor.white.withAlphaComponent(0.1).cgColor
            b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 14, bottom: 0, right: 14)
            b.semanticContentAttribute = .forceRightToLeft
            b.tag = i
            b.addTarget(self, action: #selector(tap(_:)), for: .touchUpInside)
            b.heightAnchor.constraint(equalToConstant: 34).isActive = true
            stack.addArrangedSubview(b)
        }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let size = stack.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
        let w = max(size.width, bounds.width - 28)
        stack.frame = CGRect(x: 14, y: (bounds.height - 34) / 2, width: size.width, height: 34)
        if size.width < bounds.width - 28 { stack.frame.origin.x = bounds.width - 14 - size.width }   // start from the right
        contentSize = CGSize(width: w + 28, height: bounds.height)
    }

    @objc private func tap(_ b: UIButton) { onTap?(b.tag) }
}

/// The details page's small action buttons (icon over label): «قائمتي», «أعجبني», «لم يعجبني», «مشاركة».
final class ActionButton: UIControl {
    private let icon = UIImageView()
    private let label = UILabel()

    init(title: String, symbol: String) {
        super.init(frame: .zero)
        icon.tintColor = .white
        icon.contentMode = .scaleAspectFit
        label.font = Theme.font(12.5, .semibold)
        label.textColor = Theme.text2
        label.textAlignment = .center
        addSubview(icon)
        addSubview(label)
        set(title: title, symbol: symbol, active: false)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func set(title: String, symbol: String, active: Bool) {
        icon.image = UIImage(systemName: symbol)
        icon.tintColor = active ? Theme.red : .white
        label.text = title
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        icon.frame = CGRect(x: (bounds.width - 26) / 2, y: 4, width: 26, height: 26)
        label.frame = CGRect(x: 0, y: 36, width: bounds.width, height: 18)
    }

    override var isHighlighted: Bool { didSet { alpha = isHighlighted ? 0.5 : 1 } }
}
