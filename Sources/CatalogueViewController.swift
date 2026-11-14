import UIKit

final class PosterCell: UICollectionViewCell {
    static let id = "PosterCell"
    private let img = UIImageView()
    private let label = UILabel()
    private var task: URLSessionDataTask?

    override init(frame: CGRect) {
        super.init(frame: frame)
        img.contentMode = .scaleAspectFill; img.clipsToBounds = true
        img.layer.cornerRadius = 8; img.backgroundColor = UIColor(white: 0.15, alpha: 1)
        label.font = .systemFont(ofSize: 12, weight: .medium); label.textColor = .white
        label.numberOfLines = 2; label.textAlignment = .center
        [img, label].forEach { contentView.addSubview($0) }
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let h = bounds.height - 34
        img.frame = CGRect(x: 0, y: 0, width: bounds.width, height: h)
        label.frame = CGRect(x: 0, y: h + 2, width: bounds.width, height: 32)
    }
    override func prepareForReuse() { task?.cancel(); img.image = nil }

    func configure(_ t: Title) {
        label.text = t.name
        guard let u = t.poster else { return }
        task = ImageLoader.shared.load(u) { [weak self] i in self?.img.image = i }
    }
}

final class CatalogueViewController: UIViewController, UICollectionViewDataSource,
                                     UICollectionViewDelegateFlowLayout, UISearchResultsUpdating {
    private var all: [Title] = []
    private var shown: [Title] = []
    private var collection: UICollectionView!
    private let spinner = UIActivityIndicatorView(style: .whiteLarge)

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Cigram"
        view.backgroundColor = UIColor(red: 0.05, green: 0.06, blue: 0.09, alpha: 1)
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 10; layout.minimumLineSpacing = 14
        layout.sectionInset = UIEdgeInsets(top: 12, left: 12, bottom: 20, right: 12)
        collection = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collection.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collection.backgroundColor = .clear
        collection.register(PosterCell.self, forCellWithReuseIdentifier: PosterCell.id)
        collection.dataSource = self; collection.delegate = self
        view.addSubview(collection)
        spinner.center = view.center; spinner.startAnimating(); view.addSubview(spinner)

        let sc = UISearchController(searchResultsController: nil)
        sc.searchResultsUpdater = self; sc.obscuresBackgroundDuringPresentation = false
        navigationItem.searchController = sc; definesPresentationContext = true

        CatalogueClient.shared.load { [weak self] titles, _ in
            self?.all = titles; self?.shown = titles
            self?.spinner.stopAnimating(); self?.collection.reloadData()
        }
    }

    func updateSearchResults(for sc: UISearchController) {
        let q = (sc.searchBar.text ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        shown = q.isEmpty ? all : all.filter { $0.name.lowercased().contains(q) }
        collection.reloadData()
    }

    func collectionView(_ c: UICollectionView, numberOfItemsInSection s: Int) -> Int { shown.count }
    func collectionView(_ c: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
        let cell = c.dequeueReusableCell(withReuseIdentifier: PosterCell.id, for: ip) as! PosterCell
        cell.configure(shown[ip.item]); return cell
    }
    func collectionView(_ c: UICollectionView, layout l: UICollectionViewLayout,
                        sizeForItemAt ip: IndexPath) -> CGSize {
        let cols: CGFloat = view.bounds.width > 700 ? 5 : 3
        let w = floor((view.bounds.width - 24 - 10 * (cols - 1)) / cols)
        return CGSize(width: w, height: w * 1.5 + 34)
    }
    func collectionView(_ c: UICollectionView, didSelectItemAt ip: IndexPath) {
        navigationController?.pushViewController(PlayerViewController(title: shown[ip.item]), animated: true)
    }
}
