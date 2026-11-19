import UIKit

/// Details — the Android layout: backdrop, poster, title, «2025 ¦ كوميديا | دراما», IMDb, «شاهد الآن» (or «أكمل الحلقة …»),
/// the action row («قائمتي», «أعجبني», «لم يعجبني», «مشاركة»), the story, seasons + episodes, «أعمال مشابهة».
final class DetailsViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let show: Show
    private let table = UITableView(frame: .zero, style: .plain)
    private var season = 0
    private var episodes: [CatalogItem] = []
    private var related: [Show] = []
    private let seasonControl = UISegmentedControl()
    private let listButton = ActionButton(title: "قائمتي", symbol: "plus")
    private let likeButton = ActionButton(title: "أعجبني", symbol: "hand.thumbsup")
    private let dislikeButton = ActionButton(title: "لم يعجبني", symbol: "hand.thumbsdown")
    private let shareButton = ActionButton(title: "مشاركة", symbol: "square.and.arrow.up")
    private let playButton = UIButton(type: .system)
    private let downloadPanel = DownloadPanel()
    private var headerWidth: CGFloat = 0

    init(show: Show) {
        self.show = show
        super.init(nibName: nil, bundle: nil)
        title = show.title
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        table.backgroundColor = Theme.bg
        table.separatorColor = UIColor.white.withAlphaComponent(0.08)
        table.dataSource = self
        table.delegate = self
        table.frame = view.bounds
        table.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        table.register(EpisodeCell.self, forCellReuseIdentifier: EpisodeCell.id)
        table.register(RowCell.self, forCellReuseIdentifier: RowCell.id)
        view.addSubview(table)
        if show.isSeries {
            let current = Library.shared.lastEpisode(of: show)
            season = current?.season ?? (show.seasons.first ?? 0)
            episodes = show.episodes.filter { $0.season == season }
        }
        related = show.related()
        listButton.addTarget(self, action: #selector(toggleList), for: .touchUpInside)
        likeButton.addTarget(self, action: #selector(like), for: .touchUpInside)
        dislikeButton.addTarget(self, action: #selector(dislike), for: .touchUpInside)
        shareButton.addTarget(self, action: #selector(share), for: .touchUpInside)
        playButton.addTarget(self, action: #selector(playMain), for: .touchUpInside)
        seasonControl.addTarget(self, action: #selector(seasonChanged), for: .valueChanged)
        refreshActions()
        NotificationCenter.default.addObserver(self, selector: #selector(refreshActions), name: Library.changed, object: nil)
        // «تنزيل»: the film's panel and every episode's button follow the download manager (live progress)
        downloadPanel.onAction = { [weak self] a in self?.movieDownload(a) }
        DownloadManager.shared.launch()
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(refreshDownloads), name: DownloadManager.changed, object: nil)
        nc.addObserver(self, selector: #selector(refreshDownloads), name: DownloadManager.progressed, object: nil)
        refreshDownloads()
    }

    /// The header is built for the page's real width, and again when it changes (rotation, iPad split view).
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = table.bounds.width
        guard w > 0, abs(w - headerWidth) > 0.5 else { return }
        headerWidth = w
        table.tableHeaderView = header()
        table.reloadData()
    }

    private func header() -> UIView {
        let w = headerWidth > 0 ? headerWidth : view.bounds.width
        let box = UIView(frame: CGRect(x: 0, y: 0, width: w, height: 10))
        let back = UIImageView(frame: CGRect(x: 0, y: 0, width: w, height: w * 0.62))
        back.contentMode = .scaleAspectFill
        back.clipsToBounds = true
        ImageLoader.shared.load(show.backdropURL ?? show.posterURL, maxPixel: max(w, 400)) { back.image = $0 }
        box.addSubview(back)
        let fade = CAGradientLayer()
        fade.frame = back.bounds
        fade.colors = [UIColor.clear.cgColor, Theme.bg.cgColor]
        fade.locations = [0.45, 1.0]
        back.layer.addSublayer(fade)

        let pw: CGFloat = min(130, w * 0.3)
        let poster = UIImageView(frame: CGRect(x: w - pw - 16, y: back.frame.maxY - pw * 0.9, width: pw, height: pw * 1.5))
        poster.contentMode = .scaleAspectFill
        poster.clipsToBounds = true
        poster.layer.cornerRadius = 12
        poster.layer.borderWidth = 1
        poster.layer.borderColor = UIColor.white.withAlphaComponent(0.12).cgColor
        poster.backgroundColor = Theme.surface
        ImageLoader.shared.load(show.posterURL, maxPixel: 300) { poster.image = $0 }
        box.addSubview(poster)

        let textW = w - pw - 44
        let name = UILabel(frame: CGRect(x: 16, y: back.frame.maxY - pw * 0.55, width: textW, height: 58))
        name.text = show.title
        name.font = Theme.font(22, .heavy)
        name.textColor = .white
        name.numberOfLines = 2
        name.textAlignment = .right
        box.addSubview(name)
        let meta = UILabel(frame: CGRect(x: 16, y: name.frame.maxY + 2, width: textW, height: 18))
        meta.text = show.meta
        meta.font = Theme.font(13)
        meta.textColor = Theme.text3
        meta.textAlignment = .right
        box.addSubview(meta)
        let badges = UILabel(frame: CGRect(x: 16, y: meta.frame.maxY + 4, width: textW, height: 18))
        var b: [String] = []
        if !show.head.imdb.isEmpty && !show.head.imdb.hasPrefix("tt") { b.append("IMDb " + show.head.imdb) }
        b.append(show.isSeries ? "مسلسل · " + String(show.seasons.count) + " مواسم · " + String(show.episodes.count) + " حلقة" : "فيلم")
        badges.text = b.joined(separator: "   ")
        badges.font = Theme.font(13, .bold)
        badges.textColor = Theme.imdb
        badges.textAlignment = .right
        box.addSubview(badges)

        var y = poster.frame.maxY + 16
        playButton.frame = CGRect(x: 16, y: y, width: w - 32, height: 52)
        playButton.backgroundColor = Theme.red
        playButton.layer.cornerRadius = 12
        playButton.setTitleColor(.white, for: .normal)
        playButton.tintColor = .white
        playButton.titleLabel?.font = Theme.font(17, .bold)
        playButton.setImage(UIImage(systemName: "play.fill"), for: .normal)
        playButton.semanticContentAttribute = .forceRightToLeft
        playButton.isEnabled = !show.watchLink.isEmpty || !show.episodes.isEmpty
        playButton.alpha = playButton.isEnabled ? 1 : 0.4
        box.addSubview(playButton)
        y = playButton.frame.maxY + 14
        if !show.isSeries {
            // a film is downloaded from here; a series episode from its own row
            downloadPanel.frame = CGRect(x: 16, y: y, width: w - 32, height: DownloadPanel.height)
            box.addSubview(downloadPanel)
            y = downloadPanel.frame.maxY + 14
            refreshDownloads()
        }

        let actions = UIStackView(arrangedSubviews: [listButton, likeButton, dislikeButton, shareButton])
        actions.axis = .horizontal
        actions.distribution = .fillEqually
        actions.semanticContentAttribute = .forceRightToLeft
        actions.frame = CGRect(x: 8, y: y, width: w - 16, height: 58)
        box.addSubview(actions)
        y = actions.frame.maxY + 12

        let story = UILabel(frame: CGRect(x: 16, y: y, width: w - 32, height: 10))
        story.text = show.head.overview
        story.font = Theme.font(14.5)
        story.textColor = Theme.text2
        story.numberOfLines = 0
        story.textAlignment = .right
        story.sizeToFit()
        story.frame = CGRect(x: 16, y: y, width: w - 32, height: story.frame.height)
        box.addSubview(story)
        y = story.frame.maxY + 16

        if !show.genres.isEmpty {
            let g = UILabel(frame: CGRect(x: 16, y: y, width: w - 32, height: 18))
            g.text = "التصنيف: " + show.genres.joined(separator: " | ")
            g.font = Theme.font(13)
            g.textColor = Theme.text3
            g.textAlignment = .right
            box.addSubview(g)
            y = g.frame.maxY + 14
        }

        if show.isSeries {
            let seasons = show.seasons
            seasonControl.removeAllSegments()
            for (i, s) in seasons.enumerated() { seasonControl.insertSegment(withTitle: "الموسم " + String(s), at: i, animated: false) }
            seasonControl.selectedSegmentIndex = max(0, seasons.firstIndex(of: season) ?? 0)
            seasonControl.selectedSegmentTintColor = Theme.red
            seasonControl.setTitleTextAttributes([.foregroundColor: UIColor.white, .font: Theme.font(13.5, .bold)], for: .normal)
            let scroll = UIScrollView(frame: CGRect(x: 0, y: y, width: w, height: 36))
            scroll.showsHorizontalScrollIndicator = false
            let segW = max(w - 32, CGFloat(seasons.count) * 92)
            seasonControl.frame = CGRect(x: 16, y: 0, width: segW, height: 32)
            scroll.addSubview(seasonControl)
            scroll.contentSize = CGSize(width: segW + 32, height: 36)
            box.addSubview(scroll)
            y = scroll.frame.maxY + 6
        }
        box.frame.size.height = y
        return box
    }

    @objc private func refreshActions() {
        let inList = Library.shared.inMyList(show.key)
        listButton.set(title: "قائمتي", symbol: inList ? "checkmark" : "plus", active: inList)
        let r = Library.shared.reaction(show.key)
        likeButton.set(title: "أعجبني", symbol: r == 1 ? "hand.thumbsup.fill" : "hand.thumbsup", active: r == 1)
        dislikeButton.set(title: "لم يعجبني", symbol: r == -1 ? "hand.thumbsdown.fill" : "hand.thumbsdown", active: r == -1)
        if show.isSeries {
            if let e = Library.shared.lastEpisode(of: show) {
                playButton.setTitle("  أكمل الحلقة " + String(e.episode) + " · الموسم " + String(e.season), for: .normal)
            } else if let f = show.episodes.first {
                playButton.setTitle("  شاهد الحلقة " + String(f.episode) + " · الموسم " + String(f.season), for: .normal)
            }
        } else {
            playButton.setTitle("  شاهد الآن", for: .normal)
        }
    }

    @objc private func toggleList() { Library.shared.toggleMyList(show.key) }

    @objc private func like() { react(1) }
    @objc private func dislike() { react(-1) }

    private func react(_ v: Int) {
        let now = Library.shared.reaction(show.key) == v ? 0 : v
        Library.shared.setReaction(show.key, now)
        Account.shared.react(contentId: show.contentId, value: now)
        refreshActions()
    }

    @objc private func share() {
        let text = "شاهد «" + show.title + "» على سيكرام" + (show.meta.isEmpty ? "" : "\n" + show.meta)
        let a = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        a.popoverPresentationController?.sourceView = shareButton
        present(a, animated: true)
    }

    @objc private func seasonChanged() {
        let seasons = show.seasons
        let i = seasonControl.selectedSegmentIndex
        guard i >= 0 && i < seasons.count else { return }
        season = seasons[i]
        episodes = show.episodes.filter { $0.season == season }
        table.reloadSections(IndexSet(integer: 0), with: .none)
    }

    @objc private func playMain() { Router.play(show, episode: nil, from: self) }

    // MARK: downloads

    @objc private func refreshDownloads() {
        if !show.isSeries {
            let r = DownloadManager.shared.record(for: show, episode: nil)
            downloadPanel.update(r, speed: r.map { DownloadManager.shared.speed(of: $0.id) } ?? 0)
        }
        for c in table.visibleCells {
            if let cell = c as? EpisodeCell, let e = cell.item { cell.downloadButton.update(DownloadManager.shared.record(for: show, episode: e)) }
        }
    }

    private func movieDownload(_ a: DownloadAction) {
        if a == .start { DownloadUI.start(show: show, episode: nil, from: self); return }
        guard let r = DownloadManager.shared.record(for: show, episode: nil) else { return }
        DownloadUI.act(a, id: r.id, from: self)
    }

    private func episodeDownload(_ e: CatalogItem, source: UIView) {
        if let r = DownloadManager.shared.record(for: show, episode: e) {
            DownloadUI.menu(for: r, from: self, source: source)       // the actions of its state
        } else {
            DownloadUI.start(show: show, episode: e, from: self)
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // the stream of what «شاهد الآن» opens is found while the viewer reads the page: the player then starts at once
        let ep = show.isSeries ? (Library.shared.lastEpisode(of: show) ?? show.episodes.first) : nil
        let link = (ep?.video ?? (show.isSeries ? "" : show.head.video)).replacingOccurrences(of: "vidbolt.xyz", with: "vidbolt.pro")
        if link.hasPrefix("http") { EmbedResolver.shared.prewarm(page: link) }
    }

    // MARK: table — 0 episodes, 1 «أعمال مشابهة»

    func numberOfSections(in t: UITableView) -> Int { return 2 }

    func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int {
        if s == 0 { return show.isSeries ? episodes.count : 0 }
        return related.isEmpty ? 0 : 1
    }

    func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat {
        return ip.section == 0 ? 74 : RowCell.height(cardWidth: Theme.cardWidth(for: view.bounds.width))
    }

    func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        if ip.section == 1 {
            let c = t.dequeueReusableCell(withIdentifier: RowCell.id, for: ip) as! RowCell
            c.configure(HomeSection(id: "related", name: "أعمال مشابهة", shows: related), cardWidth: Theme.cardWidth(for: view.bounds.width))
            c.onOpen = { [weak self] s in if let self = self { Router.details(s, from: self) } }
            c.onMore = { [weak self] _ in
                guard let self = self else { return }
                self.navigationController?.pushViewController(GridViewController(title: "أعمال مشابهة", shows: self.related), animated: true)
            }
            return c
        }
        let c = t.dequeueReusableCell(withIdentifier: EpisodeCell.id, for: ip) as! EpisodeCell
        let e = episodes[ip.row]
        let last = Library.shared.lastEpisode(of: show)
        c.configure(e, poster: show.posterURL, current: last?.season == e.season && last?.episode == e.episode)
        c.item = e
        c.downloadButton.update(DownloadManager.shared.record(for: show, episode: e))
        c.onDownload = { [weak self, weak c] in
            guard let self = self, let c = c else { return }
            self.episodeDownload(e, source: c.downloadButton)
        }
        return c
    }

    func tableView(_ t: UITableView, didSelectRowAt ip: IndexPath) {
        t.deselectRow(at: ip, animated: true)
        guard ip.section == 0 else { return }
        Router.play(show, episode: episodes[ip.row], from: self)
    }
}

/// One episode row: thumbnail, «الحلقة 4», its title / duration, a play glyph, the one being watched highlighted.
final class EpisodeCell: UITableViewCell {
    static let id = "EpisodeCell"
    private let thumb = UIImageView()
    private let title = UILabel()
    private let sub = UILabel()
    private let glyph = UIImageView(image: UIImage(systemName: "play.circle.fill"))
    private var shown: URL?
    /// «تنزيل» of this episode (state and progress ring); a tap starts the download or opens its actions.
    let downloadButton = DownloadCellButton()
    var item: CatalogItem?
    var onDownload: (() -> Void)?

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = Theme.bg
        let sel = UIView()
        sel.backgroundColor = Theme.surfaceHi
        selectedBackgroundView = sel
        thumb.contentMode = .scaleAspectFill
        thumb.clipsToBounds = true
        thumb.layer.cornerRadius = 8
        thumb.backgroundColor = Theme.surface
        title.font = Theme.font(15.5, .semibold)
        title.textColor = .white
        title.textAlignment = .right
        sub.font = Theme.font(12.5)
        sub.textColor = Theme.text3
        sub.textAlignment = .right
        glyph.tintColor = .white
        [thumb, title, sub, glyph, downloadButton].forEach { contentView.addSubview($0) }
        downloadButton.addTarget(self, action: #selector(downloadTapped), for: .touchUpInside)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    @objc private func downloadTapped() { onDownload?() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = contentView.bounds.width
        thumb.frame = CGRect(x: w - 16 - 96, y: 9, width: 96, height: 56)
        downloadButton.frame = CGRect(x: 8, y: 17, width: 40, height: 40)
        glyph.frame = CGRect(x: 52, y: 25, width: 24, height: 24)
        title.frame = CGRect(x: 84, y: 14, width: w - 84 - 124, height: 22)
        sub.frame = CGRect(x: 84, y: 38, width: w - 84 - 124, height: 18)
    }

    func configure(_ e: CatalogItem, poster: URL?, current: Bool) {
        title.text = "الحلقة " + String(e.episode)
        let name = e.title.contains("الحلقة") ? "" : e.title
        sub.text = [name, e.year].filter { !$0.isEmpty }.joined(separator: " · ")
        title.textColor = current ? Theme.red : .white
        glyph.tintColor = current ? Theme.red : .white
        let url = Show.image(e.backdrop.isEmpty ? e.poster : e.backdrop, size: "w300") ?? poster
        thumb.image = nil
        shown = url
        ImageLoader.shared.load(url, maxPixel: 120) { [weak self] img in
            guard let self = self, self.shown == url else { return }
            self.thumb.image = img
        }
    }
}
