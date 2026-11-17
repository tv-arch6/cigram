import UIKit

/// Home: the newest title as a banner, then the administrator's sections in their order, 11 posters each.
final class HomeViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let table = UITableView(frame: .zero, style: .plain)
    private let hero = HeroView()
    private let spinner = UIActivityIndicatorView(style: .whiteLarge)
    private let empty = UILabel()
    private let refresher = UIRefreshControl()
    private var sections: [HomeSection] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "سيكرام"
        view.backgroundColor = Theme.bg
        table.backgroundColor = Theme.bg
        table.separatorStyle = .none
        table.dataSource = self
        table.delegate = self
        table.register(RowCell.self, forCellReuseIdentifier: RowCell.id)
        table.frame = view.bounds
        table.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        refresher.tintColor = .white
        refresher.addTarget(self, action: #selector(pullRefresh), for: .valueChanged)
        table.refreshControl = refresher
        view.addSubview(table)
        hero.play.addTarget(self, action: #selector(playHero), for: .touchUpInside)
        hero.info.addTarget(self, action: #selector(openHero), for: .touchUpInside)
        spinner.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        spinner.autoresizingMask = [.flexibleTopMargin, .flexibleBottomMargin, .flexibleLeftMargin, .flexibleRightMargin]
        view.addSubview(spinner)
        empty.text = "تعذر تحميل المحتوى. اسحب للأسفل لإعادة المحاولة."
        empty.textColor = Theme.text2
        empty.font = Theme.font(15, .medium)
        empty.textAlignment = .center
        empty.numberOfLines = 0
        empty.frame = view.bounds.insetBy(dx: 30, dy: 0)
        empty.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        empty.isHidden = true
        view.addSubview(empty)
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: .cigramCatalogChanged, object: nil)
        if CatalogStore.shared.loaded { reload() } else { spinner.startAnimating() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
            guard let self = self, !CatalogStore.shared.loaded else { return }
            self.spinner.stopAnimating()
            self.empty.isHidden = false
        }
    }

    @objc private func reload() {
        spinner.stopAnimating()
        empty.isHidden = true
        let store = CatalogStore.shared
        sections = store.sections
        if sections.isEmpty, !store.shows.isEmpty {
            sections = [HomeSection(id: "latest", name: "الأحدث", shows: Array(store.shows.prefix(Backend.homeRowMax)))]
        }
        if let top = store.shows.first {
            hero.configure(top)
            hero.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: min(520, view.bounds.width * 1.15))
            table.tableHeaderView = hero
        }
        table.reloadData()
    }

    @objc private func pullRefresh() {
        CatalogStore.shared.refresh(force: true) { [weak self] _ in self?.refresher.endRefreshing() }
        CatalogStore.shared.refreshSections()
    }

    @objc private func playHero() { if let s = hero.show { Router.play(s, episode: nil, from: self) } }
    @objc private func openHero() { if let s = hero.show { Router.details(s, from: self) } }

    private var cardWidth: CGFloat { return Theme.cardWidth(for: view.bounds.width) }

    func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { return sections.count }

    func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat { return RowCell.height(cardWidth: cardWidth) }

    func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let cell = t.dequeueReusableCell(withIdentifier: RowCell.id, for: ip) as! RowCell
        cell.configure(sections[ip.row], cardWidth: cardWidth)
        cell.onOpen = { [weak self] show in if let self = self { Router.details(show, from: self) } }
        cell.onMore = { [weak self] sec in
            guard let self = self else { return }
            let all = CatalogStore.shared.shows.filter { $0.sectionId == sec.id }
            self.navigationController?.pushViewController(GridViewController(title: sec.name, shows: all.isEmpty ? sec.shows : all), animated: true)
        }
        return cell
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.reload() }
    }
}

/// «المزيد» and search results: every title of a section as a poster grid.
final class GridViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private var shows: [Show]
    private var collection: UICollectionView!

    init(title: String, shows: [Show]) {
        self.shows = shows
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func update(_ list: [Show]) {
        shows = list
        collection?.reloadData()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 8
        layout.minimumLineSpacing = 16
        layout.sectionInset = UIEdgeInsets(top: 12, left: 12, bottom: 24, right: 12)
        collection = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collection.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collection.backgroundColor = .clear
        collection.register(PosterCell.self, forCellWithReuseIdentifier: PosterCell.id)
        collection.dataSource = self
        collection.delegate = self
        collection.semanticContentAttribute = .forceRightToLeft
        view.addSubview(collection)
    }

    func collectionView(_ c: UICollectionView, numberOfItemsInSection s: Int) -> Int { return shows.count }

    func collectionView(_ c: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
        let cell = c.dequeueReusableCell(withReuseIdentifier: PosterCell.id, for: ip) as! PosterCell
        cell.configure(shows[ip.item])
        return cell
    }

    func collectionView(_ c: UICollectionView, layout l: UICollectionViewLayout, sizeForItemAt ip: IndexPath) -> CGSize {
        let cols: CGFloat = view.bounds.width > 700 ? 5 : 3
        let w = floor((view.bounds.width - 24 - 8 * (cols - 1)) / cols)
        return CGSize(width: w, height: PosterCell.height(forWidth: w))
    }

    func collectionView(_ c: UICollectionView, didSelectItemAt ip: IndexPath) {
        Router.details(shows[ip.item], from: self)
    }
}

/// Search over every title (Arabic letters normalised the same way as the Android app).
final class SearchViewController: UIViewController, UISearchResultsUpdating, UISearchBarDelegate {
    private let results = GridViewController(title: "", shows: [])
    private let hint = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "البحث"
        view.backgroundColor = Theme.bg
        addChild(results)
        results.view.frame = view.bounds
        results.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(results.view)
        results.didMove(toParent: self)
        hint.text = "ابحث عن فيلم أو مسلسل بالعربي أو الإنجليزي"
        hint.textColor = Theme.text3
        hint.font = Theme.font(15, .medium)
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.frame = view.bounds.insetBy(dx: 30, dy: 0)
        hint.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(hint)
        let sc = UISearchController(searchResultsController: nil)
        sc.searchResultsUpdater = self
        sc.obscuresBackgroundDuringPresentation = false
        sc.searchBar.placeholder = "اسم الفيلم أو المسلسل"
        sc.searchBar.barStyle = .black
        sc.searchBar.delegate = self
        navigationItem.searchController = sc
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true
    }

    func updateSearchResults(for sc: UISearchController) {
        let q = sc.searchBar.text ?? ""
        let list = CatalogStore.shared.search(q)
        results.update(list)
        hint.isHidden = !q.trimmingCharacters(in: .whitespaces).isEmpty
        if !hint.isHidden { hint.text = "ابحث عن فيلم أو مسلسل بالعربي أو الإنجليزي" }
        else if list.isEmpty { hint.isHidden = false; hint.text = "لا توجد نتائج لـ «" + q + "»" }
    }
}

/// One place that opens a title: details, or straight to the player.
enum Router {
    static func details(_ s: Show, from vc: UIViewController) {
        let d = DetailsViewController(show: s)
        if let nav = vc.navigationController { nav.pushViewController(d, animated: true) }
        else { vc.present(UINavigationController(rootViewController: d), animated: true) }
    }

    static func play(_ s: Show, episode: CatalogItem?, from vc: UIViewController) {
        let p = PlayerViewController(show: s, episode: episode ?? (s.isSeries ? s.episodes.last : nil))
        p.modalPresentationStyle = .fullScreen
        vc.present(p, animated: true)
    }
}
