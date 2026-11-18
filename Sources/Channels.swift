import UIKit
import AVKit
import AVFoundation

/// Live channels — the same list as the Android «القنوات» page (sigram-channels-api), with the admin's order and
/// hidden channels (channels-config). Played with the system player (HLS).
struct Channel {
    let key: String
    let name: String
    let image: URL?
    let url: String
    let backup: String          // the admin's «رابط احتياطي» (channels-config backup_url), tried when the main link fails
    let section: String
    var online: Bool = true     // channels-config «online» (the admin's last link check)
    var isDash: Bool { return url.lowercased().contains(".mpd") }

    /// «MBC», «bS», «ال»: two letters for the card while the logo loads (or when the channel has none).
    var monogram: String {
        let words = name.split(whereSeparator: { $0 == " " || $0 == "-" || $0 == "_" }).map(String.init).filter { !$0.isEmpty }
        if let first = words.first, first.count <= 4, first.uppercased() == first, first.rangeOfCharacter(from: .letters) != nil {
            return first
        }
        let letters = words.prefix(2).compactMap { $0.first }.map { String($0) }
        return letters.isEmpty ? "TV" : letters.joined().uppercased()
    }

    /// The logo link of a channel record. The channels API still writes the bucket host the logos LEFT (every such link is
    /// a 404); like the Android app (CigramLogos / MainActivity.image) the link is moved to the bucket that has them,
    /// and ImageLoader tries the other host when one fails.
    static func logoURL(_ ch: [String: Any]) -> URL? {
        var raw = ""
        for k in ["image", "logo", "image_url", "icon", "poster", "poster_url"] {
            if let v = ch[k] as? String, !v.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { raw = v; break }
        }
        raw = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return nil }
        if raw.hasPrefix("//") { raw = "https:" + raw }
        if raw.hasPrefix("http://pub-") { raw = "https://" + raw.dropFirst("http://".count) }   // R2 public hosts are HTTPS (ATS)
        guard let u = URL(string: raw) ?? URL(string: raw.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "") else { return nil }
        return ImageLoader.preferred(u)
    }
}

struct ChannelSection {
    let name: String
    let channels: [Channel]
}

final class ChannelStore {
    static let shared = ChannelStore()
    static let changed = Notification.Name("cigram.channels.changed")
    static let listURL = URL(string: "https://sigram-channels-api.wwq-mixtv.workers.dev/channels")!
    static let configURL = URL(string: Backend.admin + "/channels-config")!

    private(set) var sections: [ChannelSection] = []
    private(set) var loaded = false
    private var fetching = false

    private var baseDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Cigram", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }
    private var cacheFile: URL { return baseDir.appendingPathComponent("channels.json") }
    private var configFile: URL { return baseDir.appendingPathComponent("channels-config.json") }

    /// Saved copy first (instant page, the admin's order and hidden channels included), then the network.
    func start() {
        if let d = try? Data(contentsOf: cacheFile) { install(d, config: try? Data(contentsOf: configFile)) }
        refresh()
    }

    func refresh() {
        if fetching { return }
        fetching = true
        let group = DispatchGroup()
        var list: Data?
        var config: Data?
        group.enter()
        URLSession.shared.dataTask(with: URLRequest(url: ChannelStore.listURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)) { d, r, _ in
            if (r as? HTTPURLResponse)?.statusCode == 200 { list = d }
            group.leave()
        }.resume()
        group.enter()
        URLSession.shared.dataTask(with: URLRequest(url: ChannelStore.configURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)) { d, r, _ in
            if (r as? HTTPURLResponse)?.statusCode == 200 { config = d }
            group.leave()
        }.resume()
        group.notify(queue: .global(qos: .userInitiated)) {
            self.fetching = false
            if let c = config { try? c.write(to: self.configFile, options: .atomic) }
            guard let d = list else {
                // offline: keep what is on screen, but say the page has finished trying
                DispatchQueue.main.async {
                    if !self.loaded { self.loaded = true; NotificationCenter.default.post(name: ChannelStore.changed, object: nil) }
                }
                return
            }
            try? d.write(to: self.cacheFile, options: .atomic)
            self.install(d, config: config ?? (try? Data(contentsOf: self.configFile)))
        }
    }

    private func install(_ data: Data, config: Data?) {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let cats = root["categories"] as? [[String: Any]] else { return }
        var meta: [String: [String: Any]] = [:]
        if let c = config, let o = (try? JSONSerialization.jsonObject(with: c)) as? [String: Any],
           let chans = o["channels"] as? [String: [String: Any]] {
            meta = chans
        }
        var out: [ChannelSection] = []
        for cat in cats {
            let name = (cat["name"] as? String) ?? (cat["title"] as? String) ?? "القنوات"
            var list: [Channel] = []
            for ch in (cat["channels"] as? [[String: Any]]) ?? [] {
                let key = ch["id"].map { String(describing: $0) } ?? ""
                if let m = meta[key], (m["hidden"] as? Bool) == true { continue }
                let url = (ch["url"] as? String) ?? (ch["link"] as? String) ?? ""
                if url.isEmpty { continue }
                let img = Channel.logoURL(ch)
                let backup = ((meta[key]?["backup_url"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
                let online = (meta[key]?["online"] as? Bool) ?? true
                let title = ((ch["name"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                list.append(Channel(key: key, name: title.isEmpty ? "قناة" : title, image: img, url: url, backup: backup,
                                    section: name, online: online))
            }
            list.sort { a, b in
                let oa = (meta[a.key]?["order"] as? NSNumber)?.intValue ?? Int.max
                let ob = (meta[b.key]?["order"] as? NSNumber)?.intValue ?? Int.max
                return oa < ob
            }
            if !list.isEmpty { out.append(ChannelSection(name: name, channels: list)) }
        }
        NSLog("CIGRAM_CHECK channels sections=%ld channels=%ld", out.count, out.reduce(0) { $0 + $1.channels.count })
        DispatchQueue.main.async {
            self.sections = out
            self.loaded = true
            NotificationCenter.default.post(name: ChannelStore.changed, object: nil)
        }
    }

    /// «بلّغ عن القناة» — the same report the Android channel player sends to the admin app.
    func report(_ ch: Channel) {
        guard let url = URL(string: Backend.admin + "/channel-reports") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = ["key": ch.key, "name": ch.name, "url": ch.url, "section": ch.section,
                                   "client_id": UIDevice.current.identifierForVendor?.uuidString ?? "",
                                   "app_version": "ios-" + ((Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "")]
        if Account.shared.isLoggedIn { body["user_name"] = Account.shared.name }
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: req).resume()
    }
}

// MARK: - the page

/// One channel: a rounded tile with the logo (cropped when it is an opaque picture shaped like the tile, fitted when it is
/// square or transparent), a pale plate behind a dark transparent logo so it does not vanish on the dark page, the channel's
/// letters while the logo loads or when it has none, and the «مباشر» badge (grey «متوقفة» when the admin's check failed).
final class ChannelCell: UICollectionViewCell {
    static let id = "ChannelCell"
    private let card = UIView()
    private let shine = CAGradientLayer()
    private let plate = UIView()
    private let logo = UIImageView()
    private let monogram = UILabel()
    private let name = UILabel()
    private let live = UILabel()
    private let dot = UIView()
    private var shown: URL?
    private static var plateNeeded: [URL: Bool] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        card.backgroundColor = Theme.surface
        card.layer.cornerRadius = 16
        card.layer.borderWidth = 1
        card.layer.borderColor = UIColor.white.withAlphaComponent(0.07).cgColor
        card.clipsToBounds = true
        shine.colors = [UIColor.white.withAlphaComponent(0.06).cgColor, UIColor.clear.cgColor]
        shine.startPoint = CGPoint(x: 0.5, y: 0)
        shine.endPoint = CGPoint(x: 0.5, y: 1)
        card.layer.addSublayer(shine)
        plate.backgroundColor = UIColor.white.withAlphaComponent(0.88)
        plate.layer.cornerRadius = 10
        plate.isHidden = true
        logo.clipsToBounds = true
        logo.contentMode = .scaleAspectFit
        monogram.font = Theme.font(22, .heavy)
        monogram.textColor = UIColor.white.withAlphaComponent(0.28)
        monogram.textAlignment = .center
        monogram.adjustsFontSizeToFitWidth = true
        monogram.minimumScaleFactor = 0.5
        name.font = Theme.font(13, .bold)
        name.textColor = .white
        name.textAlignment = .center
        name.numberOfLines = 1
        name.adjustsFontSizeToFitWidth = true
        name.minimumScaleFactor = 0.78
        name.lineBreakMode = .byTruncatingTail
        live.font = Theme.font(9.5, .heavy)
        live.textColor = .white
        live.textAlignment = .center
        live.layer.cornerRadius = 5
        live.clipsToBounds = true
        dot.backgroundColor = .white
        dot.layer.cornerRadius = 2.5
        contentView.addSubview(card)
        card.addSubview(monogram)
        card.addSubview(plate)
        card.addSubview(logo)
        card.addSubview(live)
        live.addSubview(dot)
        contentView.addSubview(name)
        isAccessibilityElement = true
        accessibilityTraits = .button
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        card.frame = CGRect(x: 0, y: 0, width: w, height: floor(w * 0.62))
        shine.frame = card.bounds
        monogram.frame = card.bounds.insetBy(dx: 12, dy: 10)
        layoutLogo()
        let lw: CGFloat = live.text == "مباشر" ? 46 : 52
        live.frame = CGRect(x: w - lw - 8, y: 8, width: lw, height: 17)
        dot.frame = CGRect(x: lw - 10, y: 6, width: 5, height: 5)
        name.frame = CGRect(x: 2, y: card.frame.maxY + 6, width: w - 4, height: 18)
    }

    private func layoutLogo() {
        let b = card.bounds
        guard let img = logo.image, img.size.width > 0, img.size.height > 0, b.height > 0 else {
            logo.frame = b.insetBy(dx: 14, dy: 12)
            plate.isHidden = true
            return
        }
        let ar = img.size.width / img.size.height
        let tile = b.width / b.height
        if !img.cgHasAlpha && abs(ar - tile) / tile < 0.3 {
            // an opaque picture shaped like the tile: fill it edge to edge
            logo.contentMode = .scaleAspectFill
            logo.layer.cornerRadius = 0
            logo.frame = b
            plate.isHidden = true
        } else {
            logo.contentMode = .scaleAspectFit
            logo.layer.cornerRadius = 8
            let box = b.insetBy(dx: b.width * 0.12, dy: b.height * 0.16)
            // the frame of the drawn picture inside `box` (aspect-fit), so the plate hugs the logo itself
            let scale = min(box.width / img.size.width, box.height / img.size.height)
            let fit = CGSize(width: img.size.width * scale, height: img.size.height * scale)
            logo.frame = CGRect(x: box.midX - fit.width / 2, y: box.midY - fit.height / 2, width: fit.width, height: fit.height)
            plate.frame = logo.frame.insetBy(dx: -8, dy: -6)
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        logo.image = nil
        plate.isHidden = true
        monogram.isHidden = false
        shown = nil
    }

    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12) {
                self.card.transform = self.isHighlighted ? CGAffineTransform(scaleX: 0.96, y: 0.96) : .identity
                self.card.alpha = self.isHighlighted ? 0.85 : 1
            }
        }
    }

    func configure(_ c: Channel) {
        name.text = c.name
        monogram.text = c.monogram
        live.text = c.online ? "مباشر" : "متوقفة"
        live.backgroundColor = c.online ? Theme.red : UIColor(white: 0.35, alpha: 1)
        dot.isHidden = !c.online
        accessibilityLabel = c.name + (c.online ? "، بث مباشر" : "، متوقفة حالياً")
        shown = c.image
        let want = c.image
        pulse()
        if let hit = ImageLoader.shared.cached(want, maxPixel: 220) { show(hit, url: want); return }
        ImageLoader.shared.load(want, maxPixel: 220) { [weak self] img in
            guard let self = self, self.shown == want, let img = img else { return }
            self.show(img, url: want)
            self.logo.alpha = 0
            UIView.animate(withDuration: 0.2) { self.logo.alpha = 1 }
        }
    }

    private func show(_ img: UIImage, url: URL?) {
        logo.image = img
        monogram.isHidden = true
        if img.cgHasAlpha {
            // a dark transparent logo (black letters) would vanish on the dark tile: give it a pale plate
            var need = false
            if let u = url, let known = ChannelCell.plateNeeded[u] { need = known } else {
                need = img.visibleLuminance < 0.32
                if let u = url { ChannelCell.plateNeeded[u] = need }
            }
            plate.isHidden = !need
        } else {
            plate.isHidden = true
        }
        setNeedsLayout()
    }

    private func pulse() {
        dot.layer.removeAllAnimations()
        guard !dot.isHidden, !UIAccessibility.isReduceMotionEnabled else { return }
        let a = CABasicAnimation(keyPath: "opacity")
        a.fromValue = 1
        a.toValue = 0.25
        a.duration = 0.9
        a.autoreverses = true
        a.repeatCount = .infinity
        dot.layer.add(a, forKey: "pulse")
    }
}

final class ChannelsViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private var collection: UICollectionView!
    private let refresher = UIRefreshControl()
    private let empty = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "القنوات"
        view.backgroundColor = Theme.bg
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 10
        layout.minimumLineSpacing = 18
        layout.sectionInset = UIEdgeInsets(top: 8, left: 14, bottom: 22, right: 14)
        layout.headerReferenceSize = CGSize(width: 10, height: 46)
        collection = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collection.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collection.backgroundColor = .clear
        collection.semanticContentAttribute = .forceRightToLeft
        collection.alwaysBounceVertical = true
        collection.register(ChannelCell.self, forCellWithReuseIdentifier: ChannelCell.id)
        collection.register(SectionHeader.self, forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader, withReuseIdentifier: SectionHeader.id)
        collection.dataSource = self
        collection.delegate = self
        refresher.tintColor = .white
        refresher.addTarget(self, action: #selector(pull), for: .valueChanged)
        collection.refreshControl = refresher
        view.addSubview(collection)
        empty.text = "لا توجد قنوات متاحة الآن"
        empty.textColor = Theme.text2
        empty.font = Theme.font(15, .medium)
        empty.textAlignment = .center
        empty.numberOfLines = 0
        empty.frame = view.bounds.insetBy(dx: 30, dy: 0)
        empty.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        empty.isHidden = true
        view.addSubview(empty)
        spinner.color = .white
        spinner.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        spinner.autoresizingMask = [.flexibleTopMargin, .flexibleBottomMargin, .flexibleLeftMargin, .flexibleRightMargin]
        view.addSubview(spinner)
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: ChannelStore.changed, object: nil)
        reload()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if ChannelStore.shared.loaded { ChannelStore.shared.refresh() }      // the admin's latest order / links
    }

    @objc private func pull() {
        ChannelStore.shared.refresh()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in self?.refresher.endRefreshing() }
    }

    @objc private func reload() {
        let store = ChannelStore.shared
        if store.loaded || !store.sections.isEmpty { spinner.stopAnimating() } else { spinner.startAnimating() }
        empty.isHidden = !(store.loaded && store.sections.isEmpty)
        collection.reloadData()
    }

    func numberOfSections(in c: UICollectionView) -> Int { return ChannelStore.shared.sections.count }

    func collectionView(_ c: UICollectionView, numberOfItemsInSection s: Int) -> Int {
        let all = ChannelStore.shared.sections
        return s < all.count ? all[s].channels.count : 0
    }

    func collectionView(_ c: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
        let cell = c.dequeueReusableCell(withReuseIdentifier: ChannelCell.id, for: ip) as! ChannelCell
        let all = ChannelStore.shared.sections
        if ip.section < all.count, ip.item < all[ip.section].channels.count { cell.configure(all[ip.section].channels[ip.item]) }
        return cell
    }

    func collectionView(_ c: UICollectionView, viewForSupplementaryElementOfKind kind: String, at ip: IndexPath) -> UICollectionReusableView {
        let h = c.dequeueReusableSupplementaryView(ofKind: kind, withReuseIdentifier: SectionHeader.id, for: ip) as! SectionHeader
        let all = ChannelStore.shared.sections
        h.title.text = ip.section < all.count ? all[ip.section].name : ""
        h.count.text = ip.section < all.count ? String(all[ip.section].channels.count) + " قناة" : ""
        return h
    }

    func collectionView(_ c: UICollectionView, layout l: UICollectionViewLayout, sizeForItemAt ip: IndexPath) -> CGSize {
        let width = c.bounds.width > 0 ? c.bounds.width : view.bounds.width
        let cols: CGFloat = width > 900 ? 6 : width > 700 ? 5 : width > 520 ? 4 : 3
        let w = floor((width - 28 - 10 * (cols - 1)) / cols)
        return CGSize(width: w, height: floor(w * 0.62) + 30)
    }

    func collectionView(_ c: UICollectionView, didSelectItemAt ip: IndexPath) {
        let all = ChannelStore.shared.sections
        guard ip.section < all.count, ip.item < all[ip.section].channels.count else { return }
        let p = ChannelPlayerViewController(channels: all[ip.section].channels, index: ip.item)
        p.modalPresentationStyle = .fullScreen
        present(p, animated: true)
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: { [weak self] _ in self?.collection.collectionViewLayout.invalidateLayout() })
    }
}

final class SectionHeader: UICollectionReusableView {
    static let id = "SectionHeader"
    let title = UILabel()
    let count = UILabel()
    override init(frame: CGRect) {
        super.init(frame: frame)
        title.font = Theme.font(20, .heavy)
        title.textColor = .white
        title.textAlignment = .right
        count.font = Theme.font(12.5, .semibold)
        count.textColor = Theme.text3
        count.textAlignment = .left
        addSubview(title)
        addSubview(count)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        title.frame = CGRect(x: 96, y: 4, width: w - 112, height: bounds.height - 8)
        count.frame = CGRect(x: 16, y: 4, width: 76, height: bounds.height - 8)
    }
}

/// A view that lets every touch through except the ones that land on one of its visible controls: the player bars sit
/// over the web / video player without stealing its taps.
final class PassThroughView: UIView {
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        guard alpha > 0.01, !isHidden else { return false }
        for v in subviews where !v.isHidden && v.alpha > 0.01 && v.isUserInteractionEnabled && v is UIControl {
            if v.frame.insetBy(dx: -6, dy: -6).contains(point) { return true }
        }
        return false
    }
}

// MARK: - the live player

final class ChannelPlayerViewController: UIViewController, UIGestureRecognizerDelegate {
    private let channels: [Channel]
    private var index: Int
    private let playerVC = AVPlayerViewController()
    private let bar = PassThroughView()
    private let shade = CAGradientLayer()
    private let close = UIButton(type: .system)
    private let name = UILabel()
    private let live = UILabel()
    private let prevButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private let report = UIButton(type: .system)
    private let status = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)
    private var observation: NSKeyValueObservation?
    private var bufferObservation: NSKeyValueObservation?
    private var hideTimer: Timer?
    private var wantsPlay = true
    private var stallTimer: Timer?

    init(channels: [Channel], index: Int) {
        self.channels = channels
        self.index = index
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    deinit {
        NotificationCenter.default.removeObserver(self)
        hideTimer?.invalidate()
        stallTimer?.invalidate()
    }

    override var prefersStatusBarHidden: Bool { return true }
    override var prefersHomeIndicatorAutoHidden: Bool { return true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { return .allButUpsideDown }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let session = AVAudioSession.sharedInstance()
        DispatchQueue.global(qos: .userInitiated).async {
            try? session.setCategory(.playback, mode: .moviePlayback, options: [])
            try? session.setActive(true)
        }
        addChild(playerVC)
        playerVC.view.frame = view.bounds
        playerVC.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        playerVC.view.backgroundColor = .black
        view.addSubview(playerVC.view)
        playerVC.didMove(toParent: self)
        playerVC.allowsPictureInPicturePlayback = true
        if #available(iOS 14.2, *) { playerVC.canStartPictureInPictureAutomaticallyFromInline = true }

        shade.colors = [UIColor.black.withAlphaComponent(0.75).cgColor, UIColor.clear.cgColor]
        bar.layer.addSublayer(shade)
        view.addSubview(bar)
        close.setImage(UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .bold)), for: .normal)
        close.tintColor = .white
        close.accessibilityLabel = "إغلاق"
        close.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        name.font = Theme.font(16, .bold)
        name.textColor = .white
        name.textAlignment = .right
        live.text = "مباشر"
        live.font = Theme.font(11, .heavy)
        live.textColor = .white
        live.backgroundColor = Theme.red
        live.textAlignment = .center
        live.layer.cornerRadius = 6
        live.clipsToBounds = true
        prevButton.setImage(UIImage(systemName: "chevron.right"), for: .normal)
        nextButton.setImage(UIImage(systemName: "chevron.left"), for: .normal)
        prevButton.accessibilityLabel = "القناة السابقة"
        nextButton.accessibilityLabel = "القناة التالية"
        [prevButton, nextButton].forEach { $0.tintColor = .white }
        prevButton.addTarget(self, action: #selector(prevTapped), for: .touchUpInside)
        nextButton.addTarget(self, action: #selector(nextTapped), for: .touchUpInside)
        report.setTitle("بلّغ عن القناة", for: .normal)
        report.setTitleColor(.white, for: .normal)
        report.backgroundColor = Theme.red
        report.titleLabel?.font = Theme.font(14, .bold)
        report.layer.cornerRadius = 10
        report.isHidden = true
        report.addTarget(self, action: #selector(reportTapped), for: .touchUpInside)
        status.textColor = .white
        status.font = Theme.font(15, .semibold)
        status.textAlignment = .center
        status.numberOfLines = 0
        status.isHidden = true
        spinner.color = .white
        spinner.hidesWhenStopped = true
        [close, prevButton, nextButton].forEach { bar.addSubview($0) }
        [name, live].forEach { bar.addSubview($0) }
        view.addSubview(spinner)
        view.addSubview(status)
        view.addSubview(report)
        // the player's own controls keep working: this tap only shows the bar, it never swallows the touch
        let tap = UITapGestureRecognizer(target: self, action: #selector(showBar))
        tap.cancelsTouchesInView = false
        tap.delegate = self
        view.addGestureRecognizer(tap)
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(audioInterrupted(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(becameActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(stalled), name: .AVPlayerItemPlaybackStalled, object: nil)
        play()
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { return true }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        return !(touch.view is UIControl)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = view.bounds.width
        let top = view.safeAreaInsets.top
        let side = max(10, max(view.safeAreaInsets.left, view.safeAreaInsets.right))
        bar.frame = CGRect(x: 0, y: 0, width: w, height: top + 56)
        shade.frame = CGRect(x: 0, y: 0, width: w, height: top + 80)
        close.frame = CGRect(x: side, y: top + 6, width: 44, height: 44)
        nextButton.frame = CGRect(x: side + 46, y: top + 6, width: 44, height: 44)
        prevButton.frame = CGRect(x: side + 90, y: top + 6, width: 44, height: 44)
        live.frame = CGRect(x: w - side - 48, y: top + 18, width: 44, height: 20)
        name.frame = CGRect(x: side + 140, y: top + 8, width: max(40, w - side * 2 - 196), height: 40)
        spinner.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        status.frame = CGRect(x: 24, y: view.bounds.midY - 60, width: w - 48, height: 70)
        report.frame = CGRect(x: (w - 180) / 2, y: view.bounds.midY + 18, width: 180, height: 44)
    }

    private func play(backup: Bool = false) {
        let ch = channels[index]
        let link = backup ? ch.backup : ch.url
        name.text = ch.name
        status.isHidden = true
        report.isHidden = true
        prevButton.isEnabled = index > 0
        nextButton.isEnabled = index + 1 < channels.count
        prevButton.alpha = prevButton.isEnabled ? 1 : 0.35
        nextButton.alpha = nextButton.isEnabled ? 1 : 0.35
        observation = nil
        bufferObservation = nil
        wantsPlay = true
        if link.lowercased().contains(".mpd") {
            if !backup && !ch.backup.isEmpty { play(backup: true); return }
            fail("هذه القناة بصيغة لا يدعمها الآيفون (DASH).")
            return
        }
        let parts = link.components(separatedBy: "|")
        let clean = parts[0].trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "&amp;", with: "&")
        guard let url = URL(string: clean) ?? URL(string: clean.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "") else {
            fail("رابط القناة غير صالح."); return
        }
        var headers: [String: String] = [:]
        if parts.count > 1 {
            for kv in parts[1].components(separatedBy: "&") {
                let p = kv.components(separatedBy: "=")
                if p.count >= 2 { headers[p[0]] = p[1...].joined(separator: "=").removingPercentEncoding ?? p[1] }
            }
        }
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 4
        spinner.startAnimating()
        observation = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if it.status == .readyToPlay { self.spinner.stopAnimating(); return }
                guard it.status == .failed else { return }
                if !backup && !ch.backup.isEmpty && ch.backup != ch.url {
                    NSLog("CIGRAM_CHECK channel %@ main link failed, trying the backup", ch.name)
                    self.play(backup: true)
                } else {
                    self.fail("القناة متوقفة حالياً.")
                }
            }
        }
        bufferObservation = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] it, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if it.isPlaybackLikelyToKeepUp { self.spinner.stopAnimating(); if self.wantsPlay { self.playerVC.player?.play() } }
            }
        }
        let player = AVPlayer(playerItem: item)
        player.automaticallyWaitsToMinimizeStalling = true
        playerVC.player = player
        player.play()
        showBar()
    }

    private func fail(_ text: String) {
        spinner.stopAnimating()
        playerVC.player?.pause()
        status.text = text
        status.isHidden = false
        report.isHidden = false
        showBar()
    }

    @objc private func showBar() {
        UIView.animate(withDuration: 0.2) { self.bar.alpha = 1 }
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.3) { self?.bar.alpha = 0 }
        }
    }

    /// Siri, a call, an alarm: iOS pauses the stream. When it ends, the live picture comes back by itself.
    @objc private func audioInterrupted(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw), type == .ended else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.resume() }
    }

    @objc private func becameActive() { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.resume() } }

    private func resume() {
        guard wantsPlay, status.isHidden, let p = playerVC.player, p.timeControlStatus == .paused else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        // a live stream: jump back to the live edge rather than resuming minutes behind
        if let range = p.currentItem?.seekableTimeRanges.last?.timeRangeValue, range.duration.seconds > 30 {
            p.seek(to: CMTimeRangeGetEnd(range))
        }
        p.play()
    }

    /// A stream that stalls for 12 s is nudged (a fresh request of the live playlist), like the Android player.
    @objc private func stalled(_ n: Notification) {
        guard (n.object as? AVPlayerItem) === playerVC.player?.currentItem else { return }
        spinner.startAnimating()
        stallTimer?.invalidate()
        stallTimer = Timer.scheduledTimer(withTimeInterval: 12, repeats: false) { [weak self] _ in
            guard let self = self, let item = self.playerVC.player?.currentItem, !item.isPlaybackLikelyToKeepUp, self.status.isHidden else { return }
            self.play()
        }
    }

    @objc private func closeTapped() {
        wantsPlay = false
        playerVC.player?.pause()
        playerVC.player = nil
        dismiss(animated: true)
    }

    @objc private func prevTapped() { if index > 0 { index -= 1; play() } }
    @objc private func nextTapped() { if index + 1 < channels.count { index += 1; play() } }

    @objc private func reportTapped() {
        ChannelStore.shared.report(channels[index])
        report.isHidden = true
        status.text = "وصل بلاغك للمدير · سنصلح القناة قريباً"
    }
}
