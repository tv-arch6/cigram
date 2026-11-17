import UIKit

/// Details: backdrop, poster, «2025 ¦ كوميديا | دراما», IMDb, story, «شاهد الآن», and for a series its seasons + episodes.
final class DetailsViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let show: Show
    private let table = UITableView(frame: .zero, style: .plain)
    private var season = 0
    private var episodes: [CatalogItem] = []
    private let seasonControl = UISegmentedControl()

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
        table.register(UITableViewCell.self, forCellReuseIdentifier: "ep")
        view.addSubview(table)
        if show.isSeries {
            let seasons = show.seasons
            season = seasons.last ?? 0
            episodes = show.episodes.filter { $0.season == season }
        }
        table.tableHeaderView = header()
    }

    private func header() -> UIView {
        let w = view.bounds.width
        let box = UIView(frame: CGRect(x: 0, y: 0, width: w, height: 10))
        let back = UIImageView(frame: CGRect(x: 0, y: 0, width: w, height: w * 0.62))
        back.contentMode = .scaleAspectFill
        back.clipsToBounds = true
        ImageLoader.shared.load(show.backdropURL ?? show.posterURL) { back.image = $0 }
        box.addSubview(back)
        let fade = CAGradientLayer()
        fade.frame = back.bounds
        fade.colors = [UIColor.clear.cgColor, Theme.bg.cgColor]
        fade.locations = [0.45, 1.0]
        back.layer.addSublayer(fade)

        let pw: CGFloat = min(140, w * 0.32)
        let poster = UIImageView(frame: CGRect(x: w - pw - 16, y: back.frame.maxY - pw * 0.9, width: pw, height: pw * 1.5))
        poster.contentMode = .scaleAspectFill
        poster.clipsToBounds = true
        poster.layer.cornerRadius = 12
        poster.backgroundColor = Theme.surface
        ImageLoader.shared.load(show.posterURL) { poster.image = $0 }
        box.addSubview(poster)

        let textX: CGFloat = 16
        let textW = w - pw - 44
        let name = UILabel(frame: CGRect(x: textX, y: back.frame.maxY - pw * 0.6, width: textW, height: 60))
        name.text = show.title
        name.font = Theme.font(22, .heavy)
        name.textColor = .white
        name.numberOfLines = 2
        name.textAlignment = .right
        box.addSubview(name)
        let meta = UILabel(frame: CGRect(x: textX, y: name.frame.maxY + 2, width: textW, height: 18))
        meta.text = show.meta
        meta.font = Theme.font(13)
        meta.textColor = Theme.text3
        meta.textAlignment = .right
        box.addSubview(meta)
        let imdb = UILabel(frame: CGRect(x: textX, y: meta.frame.maxY + 4, width: textW, height: 18))
        imdb.text = show.head.imdb.isEmpty ? "" : "IMDb " + show.head.imdb
        imdb.font = Theme.font(13, .bold)
        imdb.textColor = Theme.imdb
        imdb.textAlignment = .right
        box.addSubview(imdb)

        var y = poster.frame.maxY + 16
        let play = UIButton(type: .system)
        play.frame = CGRect(x: 16, y: y, width: w - 32, height: 50)
        play.backgroundColor = Theme.red
        play.layer.cornerRadius = 12
        play.setTitleColor(.white, for: .normal)
        play.titleLabel?.font = Theme.font(17, .bold)
        if show.isSeries, let last = show.episodes.last {
            play.setTitle("▶  شاهد الحلقة " + String(last.episode) + " (الموسم " + String(last.season) + ")", for: .normal)
        } else {
            play.setTitle("▶  شاهد الآن", for: .normal)
        }
        play.isEnabled = !show.watchLink.isEmpty
        play.alpha = play.isEnabled ? 1 : 0.4
        play.addTarget(self, action: #selector(playMain), for: .touchUpInside)
        box.addSubview(play)
        y = play.frame.maxY + 14

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

        if show.isSeries {
            let seasons = show.seasons
            seasonControl.removeAllSegments()
            for (i, s) in seasons.enumerated() { seasonControl.insertSegment(withTitle: "الموسم " + String(s), at: i, animated: false) }
            seasonControl.selectedSegmentIndex = max(0, seasons.firstIndex(of: season) ?? 0)
            seasonControl.addTarget(self, action: #selector(seasonChanged), for: .valueChanged)
            seasonControl.tintColor = Theme.red
            if #available(iOS 13.0, *) {
                seasonControl.selectedSegmentTintColor = Theme.red
                seasonControl.setTitleTextAttributes([.foregroundColor: UIColor.white], for: .normal)
            }
            let scroll = UIScrollView(frame: CGRect(x: 0, y: y, width: w, height: 36))
            scroll.showsHorizontalScrollIndicator = false
            let segW = max(w - 32, CGFloat(seasons.count) * 92)
            seasonControl.frame = CGRect(x: 16, y: 0, width: segW, height: 32)
            scroll.addSubview(seasonControl)
            scroll.contentSize = CGSize(width: segW + 32, height: 36)
            box.addSubview(scroll)
            y = scroll.frame.maxY + 8
        }
        box.frame.size.height = y
        return box
    }

    @objc private func seasonChanged() {
        let seasons = show.seasons
        let i = seasonControl.selectedSegmentIndex
        guard i >= 0 && i < seasons.count else { return }
        season = seasons[i]
        episodes = show.episodes.filter { $0.season == season }
        table.reloadData()
    }

    @objc private func playMain() { Router.play(show, episode: nil, from: self) }

    func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { return show.isSeries ? episodes.count : 0 }

    func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat { return 64 }

    func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let c = t.dequeueReusableCell(withIdentifier: "ep", for: ip)
        let e = episodes[ip.row]
        c.backgroundColor = Theme.bg
        c.textLabel?.text = "▶  الحلقة " + String(e.episode) + (e.title.isEmpty || e.title.contains("الحلقة") ? "" : " · " + e.title)
        c.textLabel?.textColor = .white
        c.textLabel?.font = Theme.font(15.5, .semibold)
        c.textLabel?.textAlignment = .right
        let sel = UIView()
        sel.backgroundColor = Theme.surfaceHi
        c.selectedBackgroundView = sel
        return c
    }

    func tableView(_ t: UITableView, didSelectRowAt ip: IndexPath) {
        t.deselectRow(at: ip, animated: true)
        Router.play(show, episode: episodes[ip.row], from: self)
    }
}
