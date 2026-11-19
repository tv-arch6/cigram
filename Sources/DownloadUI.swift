import UIKit

// The screens of «التنزيلات»: the button / panel on the details page, the small button of an episode row, the actions shared by all
// of them, and the downloads list of «حسابي». All text is Arabic; the colours and fonts are Cigram's (Theme).

enum DownloadAction {
    case start, pause, resume, cancel, retry, play, delete

    var title: String {
        switch self {
        case .start: return "تنزيل"
        case .pause: return "إيقاف مؤقت"
        case .resume: return "متابعة"
        case .cancel: return "إلغاء التنزيل"
        case .retry: return "إعادة المحاولة"
        case .play: return "تشغيل من الجهاز"
        case .delete: return "حذف التنزيل"
        }
    }

    var symbol: String {
        switch self {
        case .start: return "arrow.down.circle.fill"
        case .pause: return "pause.fill"
        case .resume: return "play.fill"
        case .cancel: return "xmark"
        case .retry: return "arrow.clockwise"
        case .play: return "play.fill"
        case .delete: return "trash"
        }
    }
}

/// What a record looks like on screen (title, details, icon, bar, actions).
struct DownloadStyle {
    var title: String
    var detail: String
    var symbol: String
    var tint: UIColor
    var progress: Double?
    var primary: DownloadAction?
    var secondary: [DownloadAction]
}

enum DownloadUI {
    static let orange = UIColor(red: 0.95, green: 0.55, blue: 0.13, alpha: 1)

    static func style(for r: DownloadRecord?, speed: Double) -> DownloadStyle {
        guard let r = r else {
            return DownloadStyle(title: "تنزيل", detail: "احفظه على جهازك لمشاهدته بدون إنترنت", symbol: "arrow.down.circle.fill",
                                 tint: .white, progress: nil, primary: .start, secondary: [])
        }
        switch r.state {
        case .preparing:
            return DownloadStyle(title: "جارٍ التجهيز…", detail: "فحص المصدر والجودة المتاحة", symbol: "arrow.triangle.2.circlepath",
                                 tint: Theme.text2, progress: nil, primary: nil, secondary: [.cancel])
        case .queued:
            let offline = !NetworkMonitor.shared.isOnline
            return DownloadStyle(title: "في قائمة الانتظار", detail: offline ? "بانتظار الاتصال بالإنترنت" : "يبدأ عند توفّر مكان في القائمة",
                                 symbol: "clock", tint: Theme.text2, progress: nil, primary: nil, secondary: [.cancel])
        case .downloading:
            return DownloadStyle(title: "جارٍ التنزيل · " + DownloadFormat.percent(r.progress), detail: progressDetail(r, speed: speed),
                                 symbol: "arrow.down.circle", tint: .white, progress: r.progress, primary: .pause, secondary: [.pause, .cancel])
        case .verifying:
            return DownloadStyle(title: "جارٍ التحقق من الملف…", detail: "يُفحص الملف قبل إضافته إلى التنزيلات", symbol: "checkmark.shield",
                                 tint: Theme.text2, progress: 1, primary: nil, secondary: [])
        case .paused:
            let title = r.autoResume ? "بانتظار عودة الاتصال" : "متوقف مؤقتاً · " + DownloadFormat.percent(r.progress)
            let detail = r.autoResume ? "يتابع التنزيل تلقائياً عند عودة الإنترنت" : progressDetail(r, speed: 0)
            return DownloadStyle(title: title, detail: detail, symbol: "pause.circle.fill", tint: orange, progress: r.progress,
                                 primary: .resume, secondary: [.resume, .cancel])
        case .completed:
            var parts: [String] = []
            if !r.quality.isEmpty { parts.append(r.quality) }
            parts.append(DownloadFormat.size(r.downloadedBytes))
            parts.append(DownloadFormat.date(r.completedAt))
            return DownloadStyle(title: "تم التنزيل · اضغط للتشغيل", detail: parts.joined(separator: " · "), symbol: "checkmark.circle.fill",
                                 tint: Theme.good, progress: nil, primary: .play, secondary: [.play, .delete])
        case .failed:
            return DownloadStyle(title: "فشل التنزيل", detail: r.errorMessage.isEmpty ? DownloadMessages.unknown : r.errorMessage,
                                 symbol: "exclamationmark.triangle.fill", tint: Theme.red, progress: r.progress > 0 ? r.progress : nil,
                                 primary: .retry, secondary: [.retry, .cancel])
        }
    }

    /// «512 ميغابايت من ≈ 1.2 غيغابايت · 3.2 ميغابايت/ث · 1080p».
    static func progressDetail(_ r: DownloadRecord, speed: Double) -> String {
        var parts: [String] = []
        let est = r.expectedIsEstimate ? "≈ " : ""
        if r.expectedBytes > 0 { parts.append(DownloadFormat.size(r.downloadedBytes) + " من " + est + DownloadFormat.size(r.expectedBytes)) }
        else if r.downloadedBytes > 0 { parts.append(DownloadFormat.size(r.downloadedBytes)) }
        let sp = DownloadFormat.speed(speed)
        if !sp.isEmpty {
            parts.append(est + sp)
            let left = DownloadFormat.remaining(bytesLeft: max(0, r.expectedBytes - r.downloadedBytes), speed: speed)
            if !left.isEmpty { parts.append("متبقي " + est + left) }
        }
        if !r.quality.isEmpty { parts.append(r.quality) }
        return parts.joined(separator: " · ")
    }

    // MARK: - starting and acting

    /// The shape: ask on mobile data, start, tell why when it cannot.
    static func start(show: Show, episode: CatalogItem?, from vc: UIViewController) {
        let go: () -> Void = { [weak vc] in
            guard let vc = vc else { return }
            switch DownloadManager.shared.begin(show: show, episode: episode) {
            case .started:
                break
            case .alreadyThere:
                toast("هذا العنوان موجود في التنزيلات", in: vc)
            case .noLink:
                alert("لا يوجد رابط تنزيل", "لا يتوفر رابط لهذا العنوان الآن.", in: vc)
            case .noSpace:
                alert("المساحة غير كافية", "حرّر مساحة على الجهاز (أقل شيء 300 ميغابايت) ثم أعد المحاولة.", in: vc)
            }
        }
        if NetworkMonitor.shared.isExpensive {
            let a = UIAlertController(title: "أنت على بيانات الجوال", message: "التنزيل قد يستهلك حجماً كبيراً من باقتك. هل تريد المتابعة؟",
                                      preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "تنزيل", style: .default) { _ in go() })
            a.addAction(UIAlertAction(title: "إلغاء", style: .cancel))
            vc.present(a, animated: true)
        } else {
            go()
        }
    }

    static func act(_ action: DownloadAction, id: String, from vc: UIViewController) {
        switch action {
        case .start:
            break
        case .pause:
            DownloadManager.shared.pause(id)
        case .resume:
            DownloadManager.shared.resume(id)
        case .retry:
            DownloadManager.shared.retry(id)
        case .cancel:
            let a = UIAlertController(title: "إلغاء التنزيل", message: "يُحذف ما نُزّل حتى الآن.", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "إلغاء التنزيل", style: .destructive) { _ in DownloadManager.shared.cancel(id) })
            a.addAction(UIAlertAction(title: "رجوع", style: .cancel))
            vc.present(a, animated: true)
        case .delete:
            let a = UIAlertController(title: "حذف التنزيل", message: "يُحذف الملف المحمّل من هذا الآيفون ولا يعود متاحاً بدون إنترنت.", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "حذف", style: .destructive) { _ in DownloadManager.shared.delete(id) })
            a.addAction(UIAlertAction(title: "إلغاء", style: .cancel))
            vc.present(a, animated: true)
        case .play:
            if let r = DownloadManager.shared.record(withId: id) { play(r, from: vc) }
        }
    }

    /// An action sheet with everything the state allows.
    static func menu(for r: DownloadRecord, from vc: UIViewController, source: UIView) {
        let st = style(for: r, speed: DownloadManager.shared.speed(of: r.id))
        let a = UIAlertController(title: r.title, message: r.kindText + (r.quality.isEmpty ? "" : " · " + r.quality), preferredStyle: .actionSheet)
        for act in st.secondary {
            let title = (act == .cancel && r.state == .failed) ? "إزالة من القائمة" : act.title
            a.addAction(UIAlertAction(title: title, style: (act == .cancel || act == .delete) ? .destructive : .default) { _ in
                if r.state == .failed && act == .cancel { DownloadManager.shared.cancel(r.id) } else { DownloadUI.act(act, id: r.id, from: vc) }
            })
        }
        a.addAction(UIAlertAction(title: "إغلاق", style: .cancel))
        a.popoverPresentationController?.sourceView = source
        a.popoverPresentationController?.sourceRect = source.bounds
        vc.present(a, animated: true)
    }

    // MARK: - playing from the phone

    /// The title the record stands for: the catalogue's one when it is there, else a small stand-in built from the record (the
    /// catalogue may not be on the phone while offline).
    static func show(for r: DownloadRecord) -> (Show, CatalogItem?) {
        if let s = CatalogStore.shared.show(forKey: r.contentKey) {
            let ep = r.isEpisode ? s.episodes.first(where: { $0.season == r.season && $0.episode == r.episode }) : nil
            if !r.isEpisode || ep != nil { return (s, ep) }
        }
        let head = CatalogItem(["id": r.contentKey, "type": r.isEpisode ? "series" : "movie", "title": r.title, "poster": r.poster,
                                "series_poster": r.poster])
        let show = Show(key: r.contentKey, head: head, isSeries: r.isEpisode)
        if r.isEpisode {
            let e = CatalogItem(["id": r.itemId, "type": "episode", "title": r.episodeTitle.isEmpty ? r.title : r.episodeTitle,
                                 "series_id": r.contentKey, "season": r.season, "episode": r.episode, "poster": r.poster])
            show.episodes = [e]
            return (show, e)
        }
        return (show, nil)
    }

    static func play(_ r: DownloadRecord, from vc: UIViewController) {
        guard DownloadManager.shared.record(withId: r.id)?.state == .completed else { return }
        let (s, ep) = show(for: r)
        guard DownloadManager.shared.offlineURL(show: s, episode: ep) != nil else {
            alert("الملف غير موجود", "لم يعد ملف هذا التنزيل على الجهاز.", in: vc)
            DownloadManager.shared.becameActive()
            return
        }
        if CatalogStore.shared.show(forKey: r.contentKey) != nil { Library.shared.record(s, episode: ep) }
        let p = PlayerViewController(show: s, episode: ep)
        p.forceOffline = true
        p.modalPresentationStyle = .fullScreen
        (topViewController() ?? vc).present(p, animated: true)
    }

    // MARK: - small helpers

    static func topViewController() -> UIViewController? {
        var top = (UIApplication.shared.delegate as? AppDelegate)?.window?.rootViewController
        while let p = top?.presentedViewController { top = p }
        return top
    }

    static func alert(_ title: String, _ message: String, in vc: UIViewController) {
        let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "حسناً", style: .default))
        vc.present(a, animated: true)
    }

    static func toast(_ text: String, in vc: UIViewController) {
        let l = UILabel()
        l.text = "  " + text + "  "
        l.font = Theme.font(14, .semibold)
        l.textColor = .white
        l.backgroundColor = UIColor(white: 0.1, alpha: 0.95)
        l.textAlignment = .center
        l.layer.cornerRadius = 16
        l.clipsToBounds = true
        l.sizeToFit()
        let w = min(vc.view.bounds.width - 40, l.bounds.width + 24)
        l.frame = CGRect(x: (vc.view.bounds.width - w) / 2, y: vc.view.bounds.height - vc.view.safeAreaInsets.bottom - 90, width: w, height: 34)
        vc.view.addSubview(l)
        UIView.animate(withDuration: 0.25, delay: 1.8, options: [], animations: { l.alpha = 0 }, completion: { _ in l.removeFromSuperview() })
    }

    /// Installed once at launch: several qualities → the viewer chooses; the highest is first and marked.
    static func install() {
        DownloadManager.shared.qualityPicker = { record, options, done in
            guard let top = topViewController() else { done(options.first); return }
            let a = UIAlertController(title: "جودة التنزيل", message: record.title + "\nالجودة الأعلى تحتاج مساحة أكبر. يُحفظ بجودته الأصلية بلا تحويل.",
                                      preferredStyle: .actionSheet)
            for (i, o) in options.enumerated() {
                var t = o.label.isEmpty ? "الأصلية" : o.label
                if o.estimatedBytes > 0 { t += " · ≈ " + DownloadFormat.size(o.estimatedBytes) }
                if i == 0 { t += " (الأعلى)" }
                a.addAction(UIAlertAction(title: t, style: .default) { _ in done(o) })
            }
            a.addAction(UIAlertAction(title: "إلغاء", style: .cancel) { _ in done(nil) })
            a.popoverPresentationController?.sourceView = top.view
            a.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
            top.present(a, animated: true)
        }
    }
}

// MARK: - the panel of a film's details page

/// The film's download control: its state, progress, sizes, speed and the actions of the state. A fixed height (84), so the page above
/// it never moves while the download advances.
final class DownloadPanel: UIControl {
    static let height: CGFloat = 84
    var onAction: ((DownloadAction) -> Void)?
    private let icon = UIImageView()
    private let titleLabel = UILabel()
    private let detailLabel = UILabel()
    private let track = UIView()
    private let fill = UIView()
    private let buttonA = UIButton(type: .system)
    private let buttonB = UIButton(type: .system)
    private var style = DownloadUI.style(for: nil, speed: 0)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Theme.surface
        layer.cornerRadius = 14
        layer.borderWidth = 1
        layer.borderColor = UIColor.white.withAlphaComponent(0.1).cgColor
        icon.contentMode = .scaleAspectFit
        titleLabel.font = Theme.font(16, .bold)
        titleLabel.textColor = .white
        titleLabel.textAlignment = .right
        detailLabel.font = Theme.font(12.5)
        detailLabel.textColor = Theme.text3
        detailLabel.textAlignment = .right
        detailLabel.adjustsFontSizeToFitWidth = true
        detailLabel.minimumScaleFactor = 0.75
        track.backgroundColor = UIColor.white.withAlphaComponent(0.12)
        track.layer.cornerRadius = 2
        fill.backgroundColor = Theme.red
        fill.layer.cornerRadius = 2
        track.addSubview(fill)
        for b in [buttonA, buttonB] {
            b.tintColor = .white
            b.backgroundColor = UIColor.white.withAlphaComponent(0.1)
            b.layer.cornerRadius = 18
            b.isHidden = true
        }
        buttonA.addTarget(self, action: #selector(tappedA), for: .touchUpInside)
        buttonB.addTarget(self, action: #selector(tappedB), for: .touchUpInside)
        addTarget(self, action: #selector(tappedMain), for: .touchUpInside)
        for v in [icon, titleLabel, detailLabel, track, buttonA, buttonB] as [UIView] { addSubview(v) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func update(_ r: DownloadRecord?, speed: Double) {
        style = DownloadUI.style(for: r, speed: speed)
        icon.image = UIImage(systemName: style.symbol)
        icon.tintColor = style.tint
        titleLabel.text = style.title
        detailLabel.text = style.detail
        track.isHidden = style.progress == nil
        let acts = style.secondary
        for (i, b) in [buttonA, buttonB].enumerated() {
            if i < acts.count {
                b.isHidden = false
                b.setImage(UIImage(systemName: acts[i].symbol), for: .normal)
                b.accessibilityLabel = acts[i].title
            } else {
                b.isHidden = true
            }
        }
        accessibilityLabel = style.title + "، " + style.detail
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        let buttonsW: CGFloat = (buttonA.isHidden ? 0 : 44) + (buttonB.isHidden ? 0 : 44)
        icon.frame = CGRect(x: w - 14 - 34, y: 10, width: 34, height: 34)
        let tx = 12 + buttonsW + (buttonsW > 0 ? 4 : 0)
        titleLabel.frame = CGRect(x: tx, y: 10, width: w - tx - 14 - 34 - 10, height: 24)
        detailLabel.frame = CGRect(x: tx, y: 36, width: w - tx - 14 - 34 - 10, height: 18)
        track.frame = CGRect(x: 14, y: 64, width: w - 28, height: 4)
        fill.frame = CGRect(x: 0, y: 0, width: track.bounds.width * CGFloat(min(1, max(0, style.progress ?? 0))), height: 4)
        var x: CGFloat = 10
        for b in [buttonA, buttonB] where !b.isHidden {
            b.frame = CGRect(x: x, y: 10, width: 36, height: 36)
            x += 42
        }
    }

    @objc private func tappedMain() { if let p = style.primary { onAction?(p) } }
    @objc private func tappedA() { if style.secondary.count > 0 { onAction?(style.secondary[0]) } }
    @objc private func tappedB() { if style.secondary.count > 1 { onAction?(style.secondary[1]) } }
}

// MARK: - the button of an episode row

/// A round button with a progress ring: the episode's download state at a glance; a tap starts the download or opens the actions.
final class DownloadCellButton: UIControl {
    private let icon = UIImageView()
    private let ring = CAShapeLayer()
    private let back = CAShapeLayer()
    private(set) var record: DownloadRecord?

    override init(frame: CGRect) {
        super.init(frame: frame)
        back.fillColor = UIColor.clear.cgColor
        back.strokeColor = UIColor.white.withAlphaComponent(0.15).cgColor
        back.lineWidth = 2.5
        ring.fillColor = UIColor.clear.cgColor
        ring.strokeColor = Theme.red.cgColor
        ring.lineWidth = 2.5
        ring.lineCap = .round
        ring.strokeEnd = 0
        layer.addSublayer(back)
        layer.addSublayer(ring)
        icon.contentMode = .scaleAspectFit
        addSubview(icon)
        isAccessibilityElement = true
        accessibilityTraits = .button
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let r = bounds.insetBy(dx: 7, dy: 7)
        let path = UIBezierPath(arcCenter: CGPoint(x: bounds.midX, y: bounds.midY), radius: r.width / 2, startAngle: -.pi / 2,
                                endAngle: 1.5 * .pi, clockwise: true)
        back.path = path.cgPath
        ring.path = path.cgPath
        icon.frame = CGRect(x: bounds.midX - 9, y: bounds.midY - 9, width: 18, height: 18)
    }

    func update(_ r: DownloadRecord?) {
        record = r
        var symbol = "arrow.down"
        var tint = UIColor.white
        var p: CGFloat = 0
        var label = "تنزيل الحلقة"
        if let r = r {
            switch r.state {
            case .preparing, .queued: symbol = "ellipsis"; tint = Theme.text2; label = "قيد التجهيز"
            case .downloading: symbol = "pause.fill"; p = CGFloat(r.progress); label = "جارٍ التنزيل " + DownloadFormat.percent(r.progress)
            case .verifying: symbol = "checkmark.shield"; p = 1; label = "جارٍ التحقق"
            case .paused: symbol = "play.fill"; tint = DownloadUI.orange; p = CGFloat(r.progress); label = "متوقف مؤقتاً"
            case .completed: symbol = "checkmark"; tint = Theme.good; p = 1; label = "تم التنزيل"
            case .failed: symbol = "exclamationmark"; tint = Theme.red; label = "فشل التنزيل"
            }
        }
        icon.image = UIImage(systemName: symbol)
        icon.tintColor = tint
        ring.strokeColor = (r?.state == .completed ? Theme.good : (r?.state == .paused ? DownloadUI.orange : Theme.red)).cgColor
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        ring.strokeEnd = p
        CATransaction.commit()
        accessibilityLabel = label
    }
}

// MARK: - «التنزيلات» in «حسابي»

final class DownloadCell: UITableViewCell {
    static let id = "DownloadCell"
    private let poster = UIImageView()
    private let title = UILabel()
    private let sub = UILabel()
    private let status = UILabel()
    private let track = UIView()
    private let fill = UIView()
    private let detail = UILabel()
    private let primary = UIButton(type: .system)
    private let more = UIButton(type: .system)
    private(set) var recordId = ""
    private var shownPoster = ""
    private var progress: Double?
    var onPrimary: (() -> Void)?
    var onMore: ((UIView) -> Void)?

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = Theme.bg
        let sel = UIView()
        sel.backgroundColor = Theme.surfaceHi
        selectedBackgroundView = sel
        poster.contentMode = .scaleAspectFill
        poster.clipsToBounds = true
        poster.layer.cornerRadius = 8
        poster.backgroundColor = Theme.surface
        title.font = Theme.font(15.5, .bold)
        title.textColor = .white
        title.textAlignment = .right
        sub.font = Theme.font(12.5)
        sub.textColor = Theme.text2
        sub.textAlignment = .right
        status.font = Theme.font(12.5, .semibold)
        status.textAlignment = .right
        track.backgroundColor = UIColor.white.withAlphaComponent(0.12)
        track.layer.cornerRadius = 2
        fill.backgroundColor = Theme.red
        fill.layer.cornerRadius = 2
        track.addSubview(fill)
        detail.font = Theme.font(11.5)
        detail.textColor = Theme.text3
        detail.textAlignment = .right
        detail.adjustsFontSizeToFitWidth = true
        detail.minimumScaleFactor = 0.7
        primary.tintColor = .white
        primary.backgroundColor = UIColor.white.withAlphaComponent(0.1)
        primary.layer.cornerRadius = 20
        primary.addTarget(self, action: #selector(primaryTapped), for: .touchUpInside)
        more.setImage(UIImage(systemName: "ellipsis"), for: .normal)
        more.tintColor = Theme.text2
        more.accessibilityLabel = "خيارات التنزيل"
        more.addTarget(self, action: #selector(moreTapped), for: .touchUpInside)
        for v in [poster, title, sub, status, track, detail, primary, more] as [UIView] { contentView.addSubview(v) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = contentView.bounds.width
        poster.frame = CGRect(x: w - 14 - 62, y: 11, width: 62, height: 90)
        let tx: CGFloat = 62
        let tw = w - tx - 14 - 62 - 10
        title.frame = CGRect(x: tx, y: 10, width: tw, height: 22)
        sub.frame = CGRect(x: tx, y: 33, width: tw, height: 18)
        status.frame = CGRect(x: tx, y: 53, width: tw, height: 18)
        track.frame = CGRect(x: tx, y: 76, width: tw, height: 4)
        fill.frame = CGRect(x: 0, y: 0, width: track.bounds.width * CGFloat(min(1, max(0, progress ?? 0))), height: 4)
        detail.frame = CGRect(x: tx, y: 84, width: tw, height: 18)
        primary.frame = CGRect(x: 12, y: 18, width: 40, height: 40)
        more.frame = CGRect(x: 12, y: 62, width: 40, height: 36)
    }

    func configure(_ r: DownloadRecord, speed: Double) {
        recordId = r.id
        title.text = r.title
        var line = r.kindText
        if !r.episodeTitle.isEmpty { line += " · " + r.episodeTitle }
        sub.text = line
        let st = DownloadUI.style(for: r, speed: speed)
        status.text = st.title
        status.textColor = r.state == .failed ? Theme.red : (r.state == .completed ? Theme.good : (r.state == .paused ? DownloadUI.orange : Theme.text2))
        progress = st.progress
        track.isHidden = st.progress == nil
        fill.backgroundColor = r.state == .paused ? DownloadUI.orange : Theme.red
        detail.text = st.detail
        if let p = st.primary {
            primary.isHidden = false
            primary.setImage(UIImage(systemName: p.symbol), for: .normal)
            primary.accessibilityLabel = p.title
        } else {
            primary.isHidden = true
        }
        if shownPoster != r.id {
            shownPoster = r.id
            if let t = DownloadManager.shared.thumbnail(for: r.id) { poster.image = t }
            else {
                poster.image = nil
                if let u = URL(string: r.poster) {
                    let rid = r.id
                    ImageLoader.shared.load(u, maxPixel: 200) { [weak self] img in
                        guard let self = self, self.recordId == rid else { return }
                        self.poster.image = img
                    }
                }
            }
        }
        setNeedsLayout()
    }

    @objc private func primaryTapped() { onPrimary?() }
    @objc private func moreTapped() { onMore?(more) }
}

final class DownloadsViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let table = UITableView(frame: .zero, style: .plain)
    private let header = UIView()
    private let summary = UILabel()
    private let storage = UILabel()
    private let filter = UISegmentedControl(items: ["الكل", "قيد التنزيل", "مكتملة"])
    private let sortButton = UIButton(type: .system)
    private let empty = UILabel()
    private var sortMode = 0            // 0 newest, 1 oldest, 2 name, 3 largest
    private var used: Int64 = 0
    private var shown: [DownloadRecord] = []

    private static let sortNames = ["الأحدث", "الأقدم", "الاسم", "الأكبر حجماً"]

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "التنزيلات"
        view.backgroundColor = Theme.bg
        view.semanticContentAttribute = .forceRightToLeft
        table.backgroundColor = Theme.bg
        table.separatorColor = UIColor.white.withAlphaComponent(0.08)
        table.dataSource = self
        table.delegate = self
        table.register(DownloadCell.self, forCellReuseIdentifier: DownloadCell.id)
        table.tableFooterView = UIView()
        view.addSubview(table)

        summary.font = Theme.font(16, .bold)
        summary.textColor = .white
        summary.textAlignment = .right
        storage.font = Theme.font(12.5)
        storage.textColor = Theme.text3
        storage.textAlignment = .right
        storage.numberOfLines = 2
        filter.selectedSegmentIndex = 0
        filter.selectedSegmentTintColor = Theme.red
        filter.setTitleTextAttributes([.foregroundColor: UIColor.white, .font: Theme.font(13, .bold)], for: .normal)
        filter.semanticContentAttribute = .forceRightToLeft
        filter.addTarget(self, action: #selector(rebuild), for: .valueChanged)
        sortButton.setTitle("الترتيب: الأحدث", for: .normal)
        sortButton.setTitleColor(.white, for: .normal)
        sortButton.titleLabel?.font = Theme.font(13, .semibold)
        sortButton.backgroundColor = Theme.surface
        sortButton.layer.cornerRadius = 15
        sortButton.addTarget(self, action: #selector(chooseSort), for: .touchUpInside)
        for v in [summary, storage, filter, sortButton] as [UIView] { header.addSubview(v) }
        view.addSubview(header)

        empty.text = "لا توجد تنزيلات بعد.\nافتح فيلماً أو حلقة واضغط «تنزيل» لتشاهدها بدون إنترنت."
        empty.font = Theme.font(15, .medium)
        empty.textColor = Theme.text2
        empty.textAlignment = .center
        empty.numberOfLines = 0
        view.addSubview(empty)

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(rebuild), name: DownloadManager.changed, object: nil)
        nc.addObserver(self, selector: #selector(progressed), name: DownloadManager.progressed, object: nil)
        nc.addObserver(self, selector: #selector(rebuild), name: NetworkMonitor.changed, object: nil)
        rebuild()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        DownloadManager.shared.becameActive()
        DownloadManager.shared.diskUsage { [weak self] bytes in
            self?.used = bytes
            self?.updateHeader()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = view.bounds.width
        let top = view.safeAreaInsets.top
        header.frame = CGRect(x: 0, y: top, width: w, height: 132)
        summary.frame = CGRect(x: 16, y: 10, width: w - 32, height: 24)
        storage.frame = CGRect(x: 16, y: 36, width: w - 32, height: 34)
        // RTL: the filter on the right, the sort button on the left
        sortButton.frame = CGRect(x: 16, y: 78, width: 120, height: 34)
        filter.frame = CGRect(x: 16 + 130, y: 78, width: w - 32 - 130, height: 34)
        table.frame = CGRect(x: 0, y: header.frame.maxY, width: w, height: view.bounds.height - header.frame.maxY)
        empty.frame = CGRect(x: 30, y: header.frame.maxY + 40, width: w - 60, height: 120)
    }

    // MARK: data

    @objc private func rebuild() {
        var list = DownloadManager.shared.records
        switch filter.selectedSegmentIndex {
        case 1: list = list.filter { $0.state != .completed }
        case 2: list = list.filter { $0.state == .completed }
        default: break
        }
        switch sortMode {
        case 1: list.sort { $0.created < $1.created }
        case 2: list.sort { $0.title.localizedCompare($1.title) == .orderedAscending }
        case 3: list.sort { $0.downloadedBytes > $1.downloadedBytes }
        default: list.sort { $0.created > $1.created }
        }
        shown = list
        table.reloadData()
        empty.isHidden = !list.isEmpty
        if list.isEmpty && DownloadManager.shared.records.count > 0 { empty.text = "لا توجد عناصر في هذا القسم." }
        else if list.isEmpty { empty.text = "لا توجد تنزيلات بعد.\nافتح فيلماً أو حلقة واضغط «تنزيل» لتشاهدها بدون إنترنت." }
        updateHeader()
    }

    private func updateHeader() {
        let all = DownloadManager.shared.records
        let done = all.filter { $0.state == .completed }.count
        let busy = all.filter { $0.state != .completed && $0.state != .failed }.count
        let failed = all.filter { $0.state == .failed }.count
        var s = "التنزيلات (" + String(all.count) + ")"
        if all.count > 0 { s += " · " + String(done) + " مكتمل" }
        if busy > 0 { s += " · " + String(busy) + " قيد التنزيل" }
        if failed > 0 { s += " · " + String(failed) + " فشل" }
        summary.text = s
        storage.text = "المساحة المستخدمة: " + DownloadFormat.size(used) + "\nالمتاح على الجهاز: " + DownloadFormat.size(DiskSpace.available())
        sortButton.setTitle("الترتيب: " + DownloadsViewController.sortNames[sortMode], for: .normal)
    }

    @objc private func progressed() {
        for c in table.visibleCells {
            guard let cell = c as? DownloadCell, let r = DownloadManager.shared.record(withId: cell.recordId) else { continue }
            cell.configure(r, speed: DownloadManager.shared.speed(of: r.id))
        }
    }

    @objc private func chooseSort() {
        let a = UIAlertController(title: "ترتيب التنزيلات", message: nil, preferredStyle: .actionSheet)
        for (i, n) in DownloadsViewController.sortNames.enumerated() {
            a.addAction(UIAlertAction(title: (i == sortMode ? "✓ " : "") + n, style: .default) { [weak self] _ in
                self?.sortMode = i
                self?.rebuild()
            })
        }
        a.addAction(UIAlertAction(title: "إغلاق", style: .cancel))
        a.popoverPresentationController?.sourceView = sortButton
        a.popoverPresentationController?.sourceRect = sortButton.bounds
        present(a, animated: true)
    }

    // MARK: table

    func tableView(_ t: UITableView, numberOfRowsInSection section: Int) -> Int { return shown.count }
    func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat { return 112 }

    func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let c = t.dequeueReusableCell(withIdentifier: DownloadCell.id, for: ip) as! DownloadCell
        let r = shown[ip.row]
        c.configure(r, speed: DownloadManager.shared.speed(of: r.id))
        let id = r.id
        c.onPrimary = { [weak self] in
            guard let self = self, let rec = DownloadManager.shared.record(withId: id) else { return }
            let st = DownloadUI.style(for: rec, speed: 0)
            if let p = st.primary { DownloadUI.act(p, id: id, from: self) }
        }
        c.onMore = { [weak self] src in
            guard let self = self, let rec = DownloadManager.shared.record(withId: id) else { return }
            DownloadUI.menu(for: rec, from: self, source: src)
        }
        return c
    }

    func tableView(_ t: UITableView, didSelectRowAt ip: IndexPath) {
        t.deselectRow(at: ip, animated: true)
        guard ip.row < shown.count, let rec = DownloadManager.shared.record(withId: shown[ip.row].id) else { return }
        if rec.state == .completed { DownloadUI.play(rec, from: self) }
        else if let cell = t.cellForRow(at: ip) { DownloadUI.menu(for: rec, from: self, source: cell) }
    }

    func tableView(_ t: UITableView, trailingSwipeActionsConfigurationForRowAt ip: IndexPath) -> UISwipeActionsConfiguration? {
        guard ip.row < shown.count else { return nil }
        let id = shown[ip.row].id
        guard let rec = DownloadManager.shared.record(withId: id) else { return nil }
        let isDone = rec.state == .completed
        let a = UIContextualAction(style: .destructive, title: isDone ? "حذف" : (rec.state == .failed ? "إزالة" : "إلغاء")) { [weak self] _, _, done in
            guard let self = self else { done(false); return }
            if rec.state == .failed { DownloadManager.shared.cancel(id) } else { DownloadUI.act(isDone ? .delete : .cancel, id: id, from: self) }
            done(true)
        }
        return UISwipeActionsConfiguration(actions: [a])
    }
}
