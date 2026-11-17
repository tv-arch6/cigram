import UIKit

/// A home-row card: poster (2:3), IMDb score, one-line title and «2025 ¦ كوميديا | دراما» — same as the Android card.
final class PosterCell: UICollectionViewCell {
    static let id = "PosterCell"
    private let img = UIImageView()
    private let imdb = UILabel()
    private let name = UILabel()
    private let meta = UILabel()
    private let badge = UILabel()
    private var task: URLSessionDataTask?
    private var shownURL: URL?

    override init(frame: CGRect) {
        super.init(frame: frame)
        img.contentMode = .scaleAspectFill
        img.clipsToBounds = true
        img.layer.cornerRadius = 14
        img.backgroundColor = Theme.surface
        imdb.font = Theme.font(12.5, .bold)
        imdb.textColor = Theme.imdb
        imdb.textAlignment = .right
        name.font = Theme.font(13.5, .bold)
        name.textColor = Theme.text
        name.textAlignment = .right
        meta.font = Theme.font(11.5)
        meta.textColor = Theme.text3
        meta.textAlignment = .right
        badge.font = Theme.font(10, .bold)
        badge.textColor = .white
        badge.backgroundColor = Theme.red
        badge.textAlignment = .center
        badge.layer.cornerRadius = 6
        badge.clipsToBounds = true
        badge.isHidden = true
        [img, imdb, name, meta, badge].forEach { contentView.addSubview($0) }
        isAccessibilityElement = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        let h = floor(w * 1.5)
        img.frame = CGRect(x: 0, y: 0, width: w, height: h)
        imdb.frame = CGRect(x: 2, y: h + 6, width: w - 4, height: 16)
        name.frame = CGRect(x: 2, y: h + 23, width: w - 4, height: 18)
        meta.frame = CGRect(x: 2, y: h + 42, width: w - 4, height: 15)
        badge.frame = CGRect(x: w - 52, y: 8, width: 44, height: 18)
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        task?.cancel()
        img.image = nil
        shownURL = nil
    }

    static func height(forWidth w: CGFloat) -> CGFloat { return floor(w * 1.5) + 60 }

    func configure(_ s: Show) {
        name.text = s.title
        meta.text = s.meta
        let rating = s.head.imdb
        imdb.text = rating.isEmpty || rating.hasPrefix("tt") ? " " : "IMDb " + rating
        badge.isHidden = !(Date().timeIntervalSince1970 * 1000 - s.newest < 3 * 86_400_000)
        badge.text = s.isSeries ? "حلقة جديدة" : "جديد"
        accessibilityLabel = s.title + "، " + s.meta
        let url = s.posterURL
        shownURL = url
        task = ImageLoader.shared.load(url) { [weak self] i in
            guard let self = self, self.shownURL == url else { return }
            self.img.image = i
        }
    }
}

/// One home section: a header («المزيد» on the left, the title on the right) and a horizontal row of ≤ 11 posters.
final class RowCell: UITableViewCell, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    static let id = "RowCell"
    private let titleLabel = UILabel()
    private let more = UIButton(type: .system)
    private var collection: UICollectionView!
    private var section: HomeSection?
    var onOpen: ((Show) -> Void)?
    var onMore: ((HomeSection) -> Void)?
    var cardWidth: CGFloat = 110

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        titleLabel.font = Theme.font(20, .heavy)
        titleLabel.textColor = Theme.text
        titleLabel.textAlignment = .right
        more.setTitle("المزيد", for: .normal)
        more.setTitleColor(Theme.text2, for: .normal)
        more.titleLabel?.font = Theme.font(14, .semibold)
        more.addTarget(self, action: #selector(moreTapped), for: .touchUpInside)
        let layout = RTLFlowLayout()
        layout.scrollDirection = .horizontal
        layout.minimumLineSpacing = 8
        layout.sectionInset = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
        collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collection.backgroundColor = .clear
        collection.showsHorizontalScrollIndicator = false
        collection.register(PosterCell.self, forCellWithReuseIdentifier: PosterCell.id)
        collection.dataSource = self
        collection.delegate = self
        collection.semanticContentAttribute = .forceRightToLeft
        [titleLabel, more, collection].forEach { contentView.addSubview($0) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = contentView.bounds.width
        titleLabel.frame = CGRect(x: 90, y: 10, width: w - 104, height: 30)
        more.frame = CGRect(x: 12, y: 10, width: 70, height: 30)
        collection.frame = CGRect(x: 0, y: 48, width: w, height: PosterCell.height(forWidth: cardWidth))
    }

    static func height(cardWidth: CGFloat) -> CGFloat { return 48 + PosterCell.height(forWidth: cardWidth) + 14 }

    func configure(_ s: HomeSection, cardWidth: CGFloat) {
        section = s
        self.cardWidth = cardWidth
        titleLabel.text = s.name
        collection.reloadData()
        collection.setContentOffset(.zero, animated: false)
    }

    @objc private func moreTapped() { if let s = section { onMore?(s) } }

    func collectionView(_ c: UICollectionView, numberOfItemsInSection i: Int) -> Int { return section?.shows.count ?? 0 }

    func collectionView(_ c: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
        let cell = c.dequeueReusableCell(withReuseIdentifier: PosterCell.id, for: ip) as! PosterCell
        if let s = section { cell.configure(s.shows[ip.item]) }
        return cell
    }

    func collectionView(_ c: UICollectionView, layout l: UICollectionViewLayout, sizeForItemAt ip: IndexPath) -> CGSize {
        return CGSize(width: cardWidth, height: PosterCell.height(forWidth: cardWidth))
    }

    func collectionView(_ c: UICollectionView, didSelectItemAt ip: IndexPath) {
        if let s = section { onOpen?(s.shows[ip.item]) }
    }
}

/// The big banner at the top of Home: the newest title on its backdrop.
final class HeroView: UIView {
    private let img = UIImageView()
    private let shade = CAGradientLayer()
    private let title = UILabel()
    private let meta = UILabel()
    let play = UIButton(type: .system)
    let info = UIButton(type: .system)
    private(set) var show: Show?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        backgroundColor = Theme.surface
        img.contentMode = .scaleAspectFill
        img.clipsToBounds = true
        addSubview(img)
        shade.colors = [UIColor.clear.cgColor, Theme.bg.withAlphaComponent(0.65).cgColor, Theme.bg.cgColor]
        shade.locations = [0.35, 0.75, 1.0]
        layer.addSublayer(shade)
        title.font = Theme.font(26, .heavy)
        title.textColor = .white
        title.textAlignment = .center
        title.numberOfLines = 2
        meta.font = Theme.font(13, .medium)
        meta.textColor = Theme.text2
        meta.textAlignment = .center
        play.setTitle("▶  شاهد الآن", for: .normal)
        play.setTitleColor(.black, for: .normal)
        play.backgroundColor = .white
        play.titleLabel?.font = Theme.font(16, .bold)
        play.layer.cornerRadius = 10
        info.setTitle("التفاصيل", for: .normal)
        info.setTitleColor(.white, for: .normal)
        info.backgroundColor = UIColor.white.withAlphaComponent(0.18)
        info.titleLabel?.font = Theme.font(16, .bold)
        info.layer.cornerRadius = 10
        [title, meta, play, info].forEach { addSubview($0) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        img.frame = bounds
        shade.frame = bounds
        let w = bounds.width
        let h = bounds.height
        play.frame = CGRect(x: w / 2 + 6, y: h - 64, width: min(170, w / 2 - 24), height: 46)
        info.frame = CGRect(x: w / 2 - 6 - min(170, w / 2 - 24), y: h - 64, width: min(170, w / 2 - 24), height: 46)
        meta.frame = CGRect(x: 16, y: h - 90, width: w - 32, height: 20)
        title.frame = CGRect(x: 16, y: h - 156, width: w - 32, height: 64)
    }

    func configure(_ s: Show) {
        show = s
        title.text = s.title
        meta.text = s.meta
        ImageLoader.shared.load(s.backdropURL ?? s.posterURL) { [weak self] i in self?.img.image = i }
    }
}
