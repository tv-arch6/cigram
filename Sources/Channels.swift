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
    var isDash: Bool { return url.lowercased().contains(".mpd") }
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

    private var cacheFile: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Cigram", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("channels.json")
    }

    func start() {
        if let d = try? Data(contentsOf: cacheFile) { install(d, config: nil) }
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
            guard let d = list else { return }
            try? d.write(to: self.cacheFile, options: .atomic)
            self.install(d, config: config)
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
                let img = (ch["image"] as? String).flatMap { URL(string: $0) }
                let backup = ((meta[key]?["backup_url"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
                list.append(Channel(key: key, name: (ch["name"] as? String) ?? "قناة", image: img, url: url, backup: backup, section: name))
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

final class ChannelCell: UICollectionViewCell {
    static let id = "ChannelCell"
    private let card = UIView()
    private let logo = UIImageView()
    private let name = UILabel()
    private let live = UILabel()
    private var shown: URL?

    override init(frame: CGRect) {
        super.init(frame: frame)
        card.backgroundColor = Theme.surface
        card.layer.cornerRadius = 16
        card.layer.borderWidth = 1
        card.layer.borderColor = UIColor.white.withAlphaComponent(0.07).cgColor
        logo.contentMode = .scaleAspectFit
        logo.clipsToBounds = true
        logo.layer.cornerRadius = 10
        name.font = Theme.font(13, .bold)
        name.textColor = .white
        name.textAlignment = .center
        name.numberOfLines = 1
        live.text = "مباشر"
        live.font = Theme.font(9.5, .heavy)
        live.textColor = .white
        live.backgroundColor = Theme.red
        live.textAlignment = .center
        live.layer.cornerRadius = 5
        live.clipsToBounds = true
        contentView.addSubview(card)
        card.addSubview(logo)
        card.addSubview(live)
        contentView.addSubview(name)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        card.frame = CGRect(x: 0, y: 0, width: w, height: w * 0.62)
        logo.frame = card.bounds.insetBy(dx: 16, dy: 14)
        live.frame = CGRect(x: w - 46, y: 8, width: 38, height: 16)
        name.frame = CGRect(x: 2, y: card.frame.maxY + 6, width: w - 4, height: 18)
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        logo.image = nil
        shown = nil
    }

    func configure(_ c: Channel) {
        name.text = c.name
        shown = c.image
        let want = c.image
        ImageLoader.shared.load(c.image) { [weak self] img in
            guard let self = self, self.shown == want else { return }
            self.logo.image = img
        }
    }
}

final class ChannelsViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private var collection: UICollectionView!
    private let refresher = UIRefreshControl()
    private let empty = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "القنوات"
        view.backgroundColor = Theme.bg
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 10
        layout.minimumLineSpacing = 18
        layout.sectionInset = UIEdgeInsets(top: 8, left: 14, bottom: 22, right: 14)
        layout.headerReferenceSize = CGSize(width: 10, height: 44)
        collection = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collection.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collection.backgroundColor = .clear
        collection.semanticContentAttribute = .forceRightToLeft
        collection.register(ChannelCell.self, forCellWithReuseIdentifier: ChannelCell.id)
        collection.register(SectionHeader.self, forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader, withReuseIdentifier: SectionHeader.id)
        collection.dataSource = self
        collection.delegate = self
        refresher.tintColor = .white
        refresher.addTarget(self, action: #selector(pull), for: .valueChanged)
        collection.refreshControl = refresher
        view.addSubview(collection)
        empty.text = "جارٍ تحميل القنوات…"
        empty.textColor = Theme.text2
        empty.font = Theme.font(15, .medium)
        empty.textAlignment = .center
        empty.frame = view.bounds
        empty.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(empty)
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: ChannelStore.changed, object: nil)
        reload()
    }

    @objc private func pull() {
        ChannelStore.shared.refresh()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in self?.refresher.endRefreshing() }
    }

    @objc private func reload() {
        empty.isHidden = ChannelStore.shared.loaded && !ChannelStore.shared.sections.isEmpty
        if ChannelStore.shared.loaded && ChannelStore.shared.sections.isEmpty { empty.text = "لا توجد قنوات متاحة الآن" }
        collection.reloadData()
    }

    func numberOfSections(in c: UICollectionView) -> Int { return ChannelStore.shared.sections.count }

    func collectionView(_ c: UICollectionView, numberOfItemsInSection s: Int) -> Int { return ChannelStore.shared.sections[s].channels.count }

    func collectionView(_ c: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
        let cell = c.dequeueReusableCell(withReuseIdentifier: ChannelCell.id, for: ip) as! ChannelCell
        cell.configure(ChannelStore.shared.sections[ip.section].channels[ip.item])
        return cell
    }

    func collectionView(_ c: UICollectionView, viewForSupplementaryElementOfKind kind: String, at ip: IndexPath) -> UICollectionReusableView {
        let h = c.dequeueReusableSupplementaryView(ofKind: kind, withReuseIdentifier: SectionHeader.id, for: ip) as! SectionHeader
        h.title.text = ChannelStore.shared.sections[ip.section].name
        return h
    }

    func collectionView(_ c: UICollectionView, layout l: UICollectionViewLayout, sizeForItemAt ip: IndexPath) -> CGSize {
        let cols: CGFloat = view.bounds.width > 700 ? 5 : 3
        let w = floor((view.bounds.width - 28 - 10 * (cols - 1)) / cols)
        return CGSize(width: w, height: w * 0.62 + 28)
    }

    func collectionView(_ c: UICollectionView, didSelectItemAt ip: IndexPath) {
        let section = ChannelStore.shared.sections[ip.section]
        let p = ChannelPlayerViewController(channels: section.channels, index: ip.item)
        p.modalPresentationStyle = .fullScreen
        present(p, animated: true)
    }
}

final class SectionHeader: UICollectionReusableView {
    static let id = "SectionHeader"
    let title = UILabel()
    override init(frame: CGRect) {
        super.init(frame: frame)
        title.font = Theme.font(20, .heavy)
        title.textColor = .white
        title.textAlignment = .right
        addSubview(title)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    override func layoutSubviews() {
        super.layoutSubviews()
        title.frame = bounds.insetBy(dx: 16, dy: 4)
    }
}

// MARK: - the live player

final class ChannelPlayerViewController: UIViewController {
    private let channels: [Channel]
    private var index: Int
    private let playerVC = AVPlayerViewController()
    private let bar = UIView()
    private let close = UIButton(type: .system)
    private let name = UILabel()
    private let live = UILabel()
    private let prevButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private let report = UIButton(type: .system)
    private let status = UILabel()
    private var observation: NSKeyValueObservation?
    private var hideTimer: Timer?

    init(channels: [Channel], index: Int) {
        self.channels = channels
        self.index = index
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var prefersStatusBarHidden: Bool { return true }
    override var prefersHomeIndicatorAutoHidden: Bool { return true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { return .allButUpsideDown }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        addChild(playerVC)
        playerVC.view.frame = view.bounds
        playerVC.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        playerVC.view.backgroundColor = .black
        view.addSubview(playerVC.view)
        playerVC.didMove(toParent: self)
        playerVC.allowsPictureInPicturePlayback = true

        bar.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        view.addSubview(bar)
        close.setImage(UIImage(systemName: "xmark"), for: .normal)
        close.tintColor = .white
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
        [close, name, live, prevButton, nextButton].forEach { bar.addSubview($0) }
        view.addSubview(status)
        view.addSubview(report)
        let tap = UITapGestureRecognizer(target: self, action: #selector(showBar))
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)
        play()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = view.bounds.width
        let top = view.safeAreaInsets.top
        let side = max(10, max(view.safeAreaInsets.left, view.safeAreaInsets.right))
        bar.frame = CGRect(x: 0, y: 0, width: w, height: top + 52)
        close.frame = CGRect(x: side, y: top + 6, width: 40, height: 40)
        nextButton.frame = CGRect(x: side + 44, y: top + 6, width: 40, height: 40)
        prevButton.frame = CGRect(x: side + 84, y: top + 6, width: 40, height: 40)
        live.frame = CGRect(x: w - side - 48, y: top + 17, width: 44, height: 20)
        name.frame = CGRect(x: side + 130, y: top + 6, width: max(40, w - side * 2 - 186), height: 40)
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
        observation = nil
        if link.lowercased().contains(".mpd") {
            if !backup && !ch.backup.isEmpty { play(backup: true); return }
            fail("هذه القناة بصيغة لا يدعمها الآيفون (DASH).")
            return
        }
        let parts = link.components(separatedBy: "|")
        guard let url = URL(string: parts[0].trimmingCharacters(in: .whitespaces)) else { fail("رابط القناة غير صالح."); return }
        var headers: [String: String] = [:]
        if parts.count > 1 {
            for kv in parts[1].components(separatedBy: "&") {
                let p = kv.components(separatedBy: "=")
                if p.count >= 2 { headers[p[0]] = p[1...].joined(separator: "=").removingPercentEncoding ?? p[1] }
            }
        }
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        observation = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            DispatchQueue.main.async {
                guard it.status == .failed, let self = self else { return }
                if !backup && !ch.backup.isEmpty && ch.backup != ch.url {
                    NSLog("CIGRAM_CHECK channel %@ main link failed, trying the backup", ch.name)
                    self.play(backup: true)
                } else {
                    self.fail("القناة متوقفة حالياً.")
                }
            }
        }
        let player = AVPlayer(playerItem: item)
        player.automaticallyWaitsToMinimizeStalling = true
        playerVC.player = player
        player.play()
        showBar()
    }

    private func fail(_ text: String) {
        playerVC.player?.pause()
        status.text = text
        status.isHidden = false
        report.isHidden = false
        showBar()
    }

    @objc private func showBar() {
        bar.alpha = 1
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.3) { self?.bar.alpha = 0 }
        }
    }

    @objc private func closeTapped() {
        playerVC.player?.pause()
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
