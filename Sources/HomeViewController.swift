import UIKit

/// Home — same order as the Android app: the admin's carousel, the chips, «أكمل المشاهدة», «قائمتي», then the admin's
/// sections in their order (11 posters each, «المزيد» opens the whole section), and an ad at the very end.
final class HomeViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let table = UITableView(frame: .zero, style: .plain)
    private let carousel = CarouselView()
    private let chips = ChipBar(items: [("أفلام", "film"), ("مسلسلات", "tv"), ("القنوات", "dot.radiowaves.left.and.right"), ("قائمتي", "bookmark")])
    private let spinner = UIActivityIndicatorView(style: .large)
    private let empty = UILabel()
    private let refresher = UIRefreshControl()
    private var rows: [HomeSection] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.titleView = HomeViewController.wordmark()
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
        carousel.onPlay = { [weak self] s in if let self = self { Router.play(s, episode: nil, from: self) } }
        carousel.onOpen = { [weak self] s in if let self = self { Router.details(s, from: self) } }
        chips.onTap = { [weak self] i in self?.chip(i) }
        spinner.color = .white
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
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: Library.changed, object: nil)
        if CatalogStore.shared.loaded { reload() } else { spinner.startAnimating() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
            guard let self = self, !CatalogStore.shared.loaded else { return }
            self.spinner.stopAnimating()
            self.empty.isHidden = false
        }
    }

    static func wordmark() -> UIView {
        let l = UILabel()
        l.text = "Cigram"
        l.font = Theme.font(24, .black)
        l.textColor = Theme.red
        l.sizeToFit()
        return l
    }

    @objc private func reload() {
        let store = CatalogStore.shared
        guard store.loaded else { return }
        spinner.stopAnimating()
        empty.isHidden = true
        var list: [HomeSection] = []
        let recent = Library.shared.recent()
        if !recent.isEmpty { list.append(HomeSection(id: "cg_history", name: "أكمل المشاهدة", shows: Array(recent.prefix(Backend.homeRowMax)))) }
        let mine = Library.shared.myList()
        if !mine.isEmpty { list.append(HomeSection(id: "cg_mylist", name: "قائمتي", shows: Array(mine.prefix(Backend.homeRowMax)))) }
        var sections = store.sections
        if sections.isEmpty, !store.shows.isEmpty {
            sections = [HomeSection(id: "latest", name: "الأحدث", shows: Array(store.shows.prefix(Backend.homeRowMax)))]
        }
        list.append(contentsOf: sections)
        rows = list
        table.tableHeaderView = header()
        table.tableFooterView = footer()
        table.reloadData()
    }

    private func header() -> UIView {
        let w = view.bounds.width
        let ch: CGFloat = min(560, w * 1.2)
        let box = UIView(frame: CGRect(x: 0, y: 0, width: w, height: ch + 58))
        var slides = CatalogStore.shared.carousel
        if slides.isEmpty, let top = CatalogStore.shared.shows.first { slides = [CatalogStore.Slide(show: top, image: top.backdropURL)] }
        carousel.frame = CGRect(x: 0, y: 0, width: w, height: ch)
        carousel.configure(slides)
        box.addSubview(carousel)
        chips.frame = CGRect(x: 0, y: ch + 10, width: w, height: 40)
        box.addSubview(chips)
        return box
    }

    /// A little breathing room under the last row (there are no ads on the iPhone).
    private func footer() -> UIView {
        return UIView(frame: CGRect(x: 0, y: 0, width: view.bounds.width, height: 24))
    }

    private func chip(_ i: Int) {
        let store = CatalogStore.shared
        switch i {
        case 0: navigationController?.pushViewController(GridViewController(title: "أفلام", shows: store.shows.filter { !$0.isSeries }), animated: true)
        case 1: navigationController?.pushViewController(GridViewController(title: "مسلسلات", shows: store.shows.filter { $0.isSeries }), animated: true)
        case 2: tabBarController?.selectedIndex = 1
        default: navigationController?.pushViewController(GridViewController(title: "قائمتي", shows: Library.shared.myList()), animated: true)
        }
    }

    /// Scrolls past the carousel to the rows (the simulator check photographs them).
    func scrollToRows() {
        if !rows.isEmpty { table.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: false) }
    }

    @objc private func pullRefresh() {
        CatalogStore.shared.refresh(force: true) { [weak self] _ in self?.refresher.endRefreshing() }
        CatalogStore.shared.refreshSections()
        CatalogStore.shared.refreshCarousel()
    }

    private var cardWidth: CGFloat { return Theme.cardWidth(for: view.bounds.width) }

    func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { return rows.count }

    func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat { return RowCell.height(cardWidth: cardWidth) }

    func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let cell = t.dequeueReusableCell(withIdentifier: RowCell.id, for: ip) as! RowCell
        cell.configure(rows[ip.row], cardWidth: cardWidth)
        cell.onOpen = { [weak self] show in if let self = self { Router.details(show, from: self) } }
        cell.onMore = { [weak self] sec in
            guard let self = self else { return }
            let all: [Show]
            switch sec.id {
            case "cg_history": all = Library.shared.recent()
            case "cg_mylist": all = Library.shared.myList()
            default: all = CatalogStore.shared.shows.filter { $0.sectionId == sec.id }
            }
            self.navigationController?.pushViewController(GridViewController(title: sec.name, shows: all.isEmpty ? sec.shows : all), animated: true)
        }
        return cell
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.reload() }
    }
}

/// «المزيد», «أفلام», «مسلسلات», «قائمتي» and the search results: a poster grid.
final class GridViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private var shows: [Show]
    private var collection: UICollectionView!
    private let empty = UILabel()

    init(title: String, shows: [Show]) {
        self.shows = shows
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func update(_ list: [Show]) {
        shows = list
        collection?.reloadData()
        empty.isHidden = !shows.isEmpty
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
        collection.keyboardDismissMode = .onDrag
        view.addSubview(collection)
        empty.text = "لا يوجد شيء هنا بعد"
        empty.textColor = Theme.text3
        empty.font = Theme.font(15, .medium)
        empty.textAlignment = .center
        empty.frame = view.bounds
        empty.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        empty.isHidden = !shows.isEmpty
        view.addSubview(empty)
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

/// Search over every title (Arabic letters normalised like the Android app), with the same «الكل / أفلام / مسلسلات» filter.
final class SearchViewController: UIViewController, UISearchResultsUpdating {
    private let results = GridViewController(title: "", shows: [])
    private let filter = UISegmentedControl(items: ["مسلسلات", "أفلام", "الكل"])
    private let hint = UILabel()
    let searchController = UISearchController(searchResultsController: nil)

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "البحث"
        view.backgroundColor = Theme.bg
        filter.selectedSegmentIndex = 2
        filter.selectedSegmentTintColor = Theme.red
        filter.setTitleTextAttributes([.foregroundColor: UIColor.white, .font: Theme.font(13.5, .bold)], for: .normal)
        filter.addTarget(self, action: #selector(run), for: .valueChanged)
        filter.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(filter)
        addChild(results)
        results.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(results.view)
        results.didMove(toParent: self)
        NSLayoutConstraint.activate([
            filter.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            filter.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 14),
            filter.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),
            filter.heightAnchor.constraint(equalToConstant: 34),
            results.view.topAnchor.constraint(equalTo: filter.bottomAnchor, constant: 6),
            results.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            results.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            results.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        hint.textColor = Theme.text3
        hint.font = Theme.font(15, .medium)
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.frame = view.bounds.insetBy(dx: 30, dy: 0)
        hint.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        hint.isHidden = true
        view.addSubview(hint)
        searchController.searchResultsUpdater = self
        searchController.obscuresBackgroundDuringPresentation = false
        searchController.searchBar.placeholder = "اسم الفيلم أو المسلسل"
        searchController.searchBar.barStyle = .black
        searchController.searchBar.searchTextField.textAlignment = .right
        navigationItem.searchController = searchController
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true
        NotificationCenter.default.addObserver(self, selector: #selector(run), name: .cigramCatalogChanged, object: nil)
        run()
    }

    func updateSearchResults(for sc: UISearchController) { run() }

    @objc private func run() {
        let q = (searchController.searchBar.text ?? "").trimmingCharacters(in: .whitespaces)
        var list = q.isEmpty ? Array(CatalogStore.shared.shows.prefix(60)) : CatalogStore.shared.search(q)
        if filter.selectedSegmentIndex == 1 { list = list.filter { !$0.isSeries } }
        if filter.selectedSegmentIndex == 0 { list = list.filter { $0.isSeries } }
        results.update(list)
        hint.isHidden = !list.isEmpty
        hint.text = "لا توجد نتائج لـ «" + q + "»"
    }
}

/// One place that opens a title: details, or the player.
enum Router {
    static func details(_ s: Show, from vc: UIViewController) {
        let d = DetailsViewController(show: s)
        if let nav = vc.navigationController { nav.pushViewController(d, animated: true) }
        else { vc.present(UINavigationController(rootViewController: d), animated: true) }
    }

    static func play(_ s: Show, episode: CatalogItem?, from vc: UIViewController) {
        let ep = episode ?? (s.isSeries ? (Library.shared.lastEpisode(of: s) ?? s.episodes.first) : nil)
        Library.shared.record(s, episode: ep)
        let p = PlayerViewController(show: s, episode: ep)
        p.modalPresentationStyle = .fullScreen
        vc.present(p, animated: true)
    }
}
