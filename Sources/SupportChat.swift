import UIKit
import AVFoundation
import AVKit
import PhotosUI
import QuickLook
import UserNotifications

// MARK: - layout of one bubble (computed once, used for the height AND the frames, so both always agree)

struct SupportBubbleLayout {
    var height: CGFloat = 0
    var bubble = CGRect.zero
    var name = CGRect.zero
    var reply = CGRect.zero
    var media = CGRect.zero
    var voice = CGRect.zero
    var file = CGRect.zero
    var text = CGRect.zero
    var meta = CGRect.zero
    var fail = CGRect.zero
    var system = CGRect.zero

    static let textFont = Theme.font(16)
    static let metaFont = Theme.font(11, .medium)
    static let nameFont = Theme.font(12, .bold)
    static let replyFont = Theme.font(12.5)

    static func textHeight(_ s: String, width: CGFloat, font: UIFont) -> CGFloat {
        if s.isEmpty { return 0 }
        let r = (s as NSString).boundingRect(with: CGSize(width: width, height: .greatestFiniteMagnitude),
                                             options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font], context: nil)
        return ceil(r.height)
    }

    static func textWidth(_ s: String, font: UIFont, max: CGFloat) -> CGFloat {
        if s.isEmpty { return 0 }
        let r = (s as NSString).boundingRect(with: CGSize(width: max, height: .greatestFiniteMagnitude),
                                             options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font], context: nil)
        return ceil(min(max, r.width))
    }

    static func make(_ m: SupportMessage, showName: Bool, width: CGFloat) -> SupportBubbleLayout {
        var l = SupportBubbleLayout()
        if m.isSystem {
            let tw = textWidth(m.text, font: Theme.font(12.5, .medium), max: width - 80)
            let th = textHeight(m.text, width: tw, font: Theme.font(12.5, .medium))
            l.system = CGRect(x: (width - tw - 24) / 2, y: 6, width: tw + 24, height: th + 12)
            l.height = l.system.maxY + 6
            return l
        }
        let maxBubble = min(width * 0.78, 340)
        var y: CGFloat = 3
        if showName && !m.mine {
            l.name = CGRect(x: 0, y: y, width: maxBubble, height: 16)
            y += 18
        }
        let body = m.deleted ? "تم حذف هذه الرسالة" : m.text
        let hasMedia = !m.deleted && ["image", "video"].contains(m.type == "file" && m.isImage ? "image" : m.type)
        let isVoice = !m.deleted && m.type == "voice"
        let isFile = !m.deleted && m.type == "file" && !m.isImage
        let showText = !body.isEmpty && !(hasMedia && body == m.preview) && !isVoice && !(isFile && body == m.mediaName)
        var contentW: CGFloat = 0
        var cy: CGFloat = 0
        // reply preview
        var replyH: CGFloat = 0
        if let r = m.reply, let p = r["preview"] as? String, !p.isEmpty, !m.deleted {
            replyH = 40
            contentW = max(contentW, min(maxBubble - 16, max(140, textWidth(p, font: replyFont, max: maxBubble - 40) + 30)))
        }
        // media
        var mediaSize = CGSize.zero
        if hasMedia {
            var w = m.mediaW, h = m.mediaH
            if let img = m.localImage, w <= 0 || h <= 0 { w = img.size.width; h = img.size.height }
            let aspect = (w > 0 && h > 0) ? h / w : 0.75
            let mw = min(maxBubble - 8, 250)
            let mh = max(110, min(320, mw * aspect))
            mediaSize = CGSize(width: mw, height: mh)
            contentW = max(contentW, mw + 8 - 24)
        }
        if isVoice { contentW = max(contentW, min(maxBubble - 24, 220)) }
        if isFile { contentW = max(contentW, min(maxBubble - 24, 230)) }
        var textSize = CGSize.zero
        if showText {
            let tw = textWidth(body, font: textFont, max: maxBubble - 24)
            textSize = CGSize(width: tw, height: textHeight(body, width: tw, font: textFont))
            contentW = max(contentW, tw)
        }
        let metaW = textWidth(SupportBubbleCell.metaText(m).string, font: metaFont, max: 160) + 4
        contentW = max(contentW, metaW, 56)
        let bw = min(maxBubble, contentW + 24)
        cy = 8
        if replyH > 0 { l.reply = CGRect(x: 8, y: cy, width: bw - 16, height: replyH); cy += replyH + 6 }
        if hasMedia {
            l.media = CGRect(x: 4, y: replyH > 0 ? cy : 4, width: bw - 8, height: mediaSize.height)
            cy = l.media.maxY + 6
        }
        if isVoice { l.voice = CGRect(x: 10, y: cy, width: bw - 20, height: 42); cy += 46 }
        if isFile { l.file = CGRect(x: 10, y: cy, width: bw - 20, height: 46); cy += 50 }
        if showText {
            l.text = CGRect(x: 12, y: cy, width: bw - 24, height: textSize.height)
            cy += textSize.height + 2
        }
        l.meta = CGRect(x: 10, y: cy, width: bw - 20, height: 15)
        cy += 15 + 6
        let bx = m.mine ? width - 10 - bw : 10
        l.bubble = CGRect(x: bx, y: y, width: bw, height: cy)
        if !l.name.isEmpty { l.name.origin.x = bx + 6 }
        y = l.bubble.maxY
        if m.state == .failed {
            l.fail = CGRect(x: 10, y: y + 2, width: width - 20, height: 17)
            y += 20
        }
        l.height = y + 3
        return l
    }
}

// MARK: - cells

/// The play / pause button of a voice message with its waveform.
final class SupportWaveView: UIView {
    var levels: [Int] = [] { didSet { setNeedsDisplay() } }
    var progress: CGFloat = 0 { didSet { setNeedsDisplay() } }
    var color: UIColor = .white

    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; backgroundColor = .clear; contentMode = .redraw }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let bars = levels.isEmpty ? Array(repeating: 40, count: 32) : levels
        let n = min(bars.count, Int(bounds.width / 4))
        guard n > 0 else { return }
        let step = bounds.width / CGFloat(n)
        for i in 0..<n {
            let v = CGFloat(bars[i * bars.count / n]) / 255
            let h = max(3, bounds.height * v)
            // right-to-left: the first bar is at the right edge, progress fills from the right
            let x = bounds.width - CGFloat(i + 1) * step
            let r = CGRect(x: x + 0.5, y: (bounds.height - h) / 2, width: max(1.5, step - 1.5), height: h)
            let played = CGFloat(i) / CGFloat(n) < progress
            ctx.setFillColor((played ? color : color.withAlphaComponent(0.38)).cgColor)
            ctx.addPath(UIBezierPath(roundedRect: r, cornerRadius: 1).cgPath)
            ctx.fillPath()
        }
    }
}

final class SupportBubbleCell: UITableViewCell {
    static let id = "SupportBubbleCell"
    private let bubble = UIView()
    private let nameLabel = UILabel()
    private let replyBox = UIView()
    private let replyBar = UIView()
    private let replyLabel = UILabel()
    private let mediaView = UIImageView()
    private let playGlyph = UIImageView(image: UIImage(systemName: "play.circle.fill"))
    private let mediaInfo = UILabel()
    private let voiceButton = UIButton(type: .system)
    private let wave = SupportWaveView()
    private let voiceTime = UILabel()
    private let fileIcon = UIImageView(image: UIImage(systemName: "doc.fill"))
    private let fileName = UILabel()
    private let fileSize = UILabel()
    private let body = UILabel()
    private let meta = UILabel()
    private let fail = UILabel()
    private let systemLabel = UILabel()
    private let progressBack = UIView()
    private let progressBar = UIView()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private var message: SupportMessage?
    private var shownKey = ""
    var onMedia: ((SupportMessage) -> Void)?
    var onVoice: ((SupportMessage) -> Void)?
    var onRetry: ((SupportMessage) -> Void)?

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        bubble.layer.cornerRadius = 18
        bubble.clipsToBounds = true
        nameLabel.font = SupportBubbleLayout.nameFont
        nameLabel.textColor = Theme.text3
        nameLabel.textAlignment = .left
        replyBox.backgroundColor = UIColor.white.withAlphaComponent(0.07)
        replyBox.layer.cornerRadius = 8
        replyBox.clipsToBounds = true
        replyBar.backgroundColor = Theme.red
        replyLabel.font = SupportBubbleLayout.replyFont
        replyLabel.textColor = Theme.text2
        replyLabel.numberOfLines = 2
        replyLabel.textAlignment = .right
        replyBox.addSubview(replyBar)
        replyBox.addSubview(replyLabel)
        mediaView.contentMode = .scaleAspectFill
        mediaView.clipsToBounds = true
        mediaView.layer.cornerRadius = 14
        mediaView.backgroundColor = UIColor(white: 0.12, alpha: 1)
        mediaView.isUserInteractionEnabled = true
        mediaView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(mediaTapped)))
        playGlyph.tintColor = .white
        playGlyph.contentMode = .scaleAspectFit
        playGlyph.layer.shadowColor = UIColor.black.cgColor
        playGlyph.layer.shadowOpacity = 0.5
        playGlyph.layer.shadowRadius = 6
        playGlyph.layer.shadowOffset = .zero
        mediaInfo.font = Theme.font(11, .bold)
        mediaInfo.textColor = .white
        mediaInfo.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        mediaInfo.textAlignment = .center
        mediaInfo.layer.cornerRadius = 6
        mediaInfo.clipsToBounds = true
        mediaView.addSubview(playGlyph)
        mediaView.addSubview(mediaInfo)
        voiceButton.tintColor = .white
        voiceButton.addTarget(self, action: #selector(voiceTapped), for: .touchUpInside)
        voiceTime.font = Theme.font(11.5, .semibold)
        voiceTime.textColor = Theme.text2
        voiceTime.textAlignment = .left
        fileIcon.tintColor = .white
        fileIcon.contentMode = .scaleAspectFit
        fileName.font = Theme.font(14, .semibold)
        fileName.textColor = .white
        fileName.textAlignment = .right
        fileName.lineBreakMode = .byTruncatingMiddle
        fileSize.font = Theme.font(12)
        fileSize.textColor = Theme.text3
        fileSize.textAlignment = .right
        body.font = SupportBubbleLayout.textFont
        body.textColor = .white
        body.numberOfLines = 0
        meta.font = SupportBubbleLayout.metaFont
        fail.font = Theme.font(12, .semibold)
        fail.textColor = Theme.red
        fail.isUserInteractionEnabled = true
        fail.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(retryTapped)))
        systemLabel.font = Theme.font(12.5, .medium)
        systemLabel.textColor = Theme.text2
        systemLabel.textAlignment = .center
        systemLabel.numberOfLines = 0
        systemLabel.backgroundColor = UIColor.white.withAlphaComponent(0.06)
        systemLabel.layer.cornerRadius = 10
        systemLabel.clipsToBounds = true
        progressBack.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        progressBack.layer.cornerRadius = 2
        progressBar.backgroundColor = .white
        progressBar.layer.cornerRadius = 2
        progressBack.addSubview(progressBar)
        spinner.color = .white
        spinner.hidesWhenStopped = true
        mediaView.addSubview(progressBack)
        mediaView.addSubview(spinner)
        [replyBox, mediaView, voiceButton, wave, voiceTime, fileIcon, fileName, fileSize, body, meta].forEach { bubble.addSubview($0) }
        [nameLabel, bubble, fail, systemLabel].forEach { contentView.addSubview($0) }
        bubble.isUserInteractionEnabled = true
        let tapFile = UITapGestureRecognizer(target: self, action: #selector(bubbleTapped))
        tapFile.cancelsTouchesInView = false
        bubble.addGestureRecognizer(tapFile)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    static func metaText(_ m: SupportMessage) -> NSAttributedString {
        let t = NSMutableAttributedString(string: SupportTime.time(m.ts > 0 ? m.ts : Date().timeIntervalSince1970 * 1000),
                                          attributes: [.font: SupportBubbleLayout.metaFont, .foregroundColor: UIColor.white.withAlphaComponent(0.55)])
        guard m.mine else { return t }
        var tick = ""
        var color = UIColor.white.withAlphaComponent(0.55)
        switch m.state {
        case .sending, .waiting: tick = "  🕓"
        case .failed: tick = "  ⚠︎"; color = Theme.red
        case .server:
            if m.read > 0 { tick = "  ✓✓"; color = UIColor(red: 0.35, green: 0.75, blue: 1, alpha: 1) }
            else if m.delivered > 0 { tick = "  ✓✓" }
            else { tick = "  ✓" }
        }
        t.append(NSAttributedString(string: tick, attributes: [.font: SupportBubbleLayout.metaFont, .foregroundColor: color]))
        return t
    }

    static func startsRtl(_ s: String) -> Bool {
        for u in s.unicodeScalars {
            let v = u.value
            if (0x0590...0x08FF).contains(v) || (0xFB1D...0xFDFF).contains(v) || (0xFE70...0xFEFF).contains(v) { return true }
            if CharacterSet.letters.contains(u) { return false }
        }
        return true
    }

    func configure(_ m: SupportMessage, showName: Bool, width: CGFloat) {
        message = m
        let l = SupportBubbleLayout.make(m, showName: showName, width: width)
        let all: [UIView] = [nameLabel, bubble, fail, systemLabel, replyBox, mediaView, voiceButton, wave, voiceTime, fileIcon, fileName, fileSize, body, meta]
        all.forEach { $0.isHidden = true }
        if m.isSystem {
            systemLabel.isHidden = false
            systemLabel.text = m.text
            systemLabel.frame = l.system
            return
        }
        bubble.isHidden = false
        bubble.frame = l.bubble
        bubble.backgroundColor = m.mine ? UIColor(red: 0x26 / 255.0, green: 0x26 / 255.0, blue: 0x29 / 255.0, alpha: 1)
                                        : UIColor(red: 0x16 / 255.0, green: 0x16 / 255.0, blue: 0x19 / 255.0, alpha: 1)
        bubble.layer.borderWidth = m.mine ? 0 : 1
        bubble.layer.borderColor = UIColor.white.withAlphaComponent(0.12).cgColor
        if !l.name.isEmpty {
            nameLabel.isHidden = false
            nameLabel.frame = l.name
            nameLabel.text = (m.dev?["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? SupportDeveloper.name
        }
        if !l.reply.isEmpty {
            replyBox.isHidden = false
            replyBox.frame = l.reply
            replyBar.frame = CGRect(x: l.reply.width - 3, y: 0, width: 3, height: l.reply.height)
            replyLabel.frame = CGRect(x: 8, y: 2, width: l.reply.width - 18, height: l.reply.height - 4)
            let who = (m.reply?["from"] as? String) == "user" ? "أنت" : SupportDeveloper.name
            replyLabel.text = who + ": " + ((m.reply?["preview"] as? String) ?? "")
        }
        if !l.media.isEmpty {
            mediaView.isHidden = false
            mediaView.frame = l.media
            let video = m.type == "video" || m.mediaKind == "video"
            playGlyph.isHidden = !video || m.state != .server
            playGlyph.frame = CGRect(x: (l.media.width - 52) / 2, y: (l.media.height - 52) / 2, width: 52, height: 52)
            var info: [String] = []
            if video && m.mediaDur > 0 { info.append(SupportTime.duration(m.mediaDur)) }
            if video && m.mediaSize > 0 { info.append(SupportTime.size(m.mediaSize)) }
            mediaInfo.isHidden = info.isEmpty
            mediaInfo.text = info.joined(separator: " · ")
            let iw = SupportBubbleLayout.textWidth(mediaInfo.text ?? "", font: mediaInfo.font, max: 200) + 12
            mediaInfo.frame = CGRect(x: 8, y: l.media.height - 26, width: iw, height: 18)
            loadMedia(m, video: video)
        }
        if !l.voice.isEmpty {
            [voiceButton, wave, voiceTime].forEach { $0.isHidden = false }
            let v = l.voice
            voiceButton.frame = CGRect(x: v.maxX - 36, y: v.minY + 3, width: 36, height: 36)
            wave.frame = CGRect(x: v.minX + 44, y: v.minY + 6, width: v.width - 86, height: 26)
            voiceTime.frame = CGRect(x: v.minX, y: v.minY + 12, width: 42, height: 16)
            wave.levels = m.wave
            refreshVoice()
        }
        if !l.file.isEmpty {
            [fileIcon, fileName, fileSize].forEach { $0.isHidden = false }
            let f = l.file
            fileIcon.frame = CGRect(x: f.maxX - 34, y: f.minY + 7, width: 30, height: 32)
            fileName.frame = CGRect(x: f.minX, y: f.minY + 4, width: f.width - 44, height: 20)
            fileSize.frame = CGRect(x: f.minX, y: f.minY + 25, width: f.width - 44, height: 16)
            fileName.text = m.mediaName
            fileSize.text = m.mediaSize > 0 ? SupportTime.size(m.mediaSize) + " · اضغط للفتح" : "ملف"
        }
        if !l.text.isEmpty {
            body.isHidden = false
            body.frame = l.text
            let s = m.deleted ? "تم حذف هذه الرسالة" : m.text
            body.text = s
            body.textAlignment = SupportBubbleCell.startsRtl(s) ? .right : .left
            body.textColor = m.deleted ? Theme.text3 : .white
            body.font = m.deleted ? UIFont.italicSystemFont(ofSize: 15) : SupportBubbleLayout.textFont
        }
        meta.isHidden = false
        meta.frame = l.meta
        meta.attributedText = SupportBubbleCell.metaText(m)
        meta.textAlignment = m.mine ? .left : .right
        if !l.fail.isEmpty {
            fail.isHidden = false
            fail.frame = l.fail
            fail.textAlignment = m.mine ? .right : .left
            fail.text = (m.failMessage.isEmpty ? "لم تُرسل" : m.failMessage) + " · اضغط لإعادة المحاولة"
        }
        refreshProgress()
        accessibilityLabel = (m.mine ? "أنت: " : SupportDeveloper.name + ": ") + m.preview + "، " + SupportTime.time(m.ts)
    }

    private func loadMedia(_ m: SupportMessage, video: Bool) {
        let key = m.mediaId.isEmpty ? "local:" + m.clientId : "chat:" + m.mediaId
        if shownKey != key { mediaView.image = nil }
        shownKey = key
        if let img = m.localImage { mediaView.image = img; return }
        guard let url = m.mediaURL else { return }
        if video {
            SupportThumbs.shared.thumb(url, key: key) { [weak self] img in
                guard let self = self, self.shownKey == key, let img = img else { return }
                self.mediaView.image = img
            }
        } else {
            ImageLoader.shared.load(url, maxPixel: 320, key: key) { [weak self] img in
                guard let self = self, self.shownKey == key else { return }
                self.mediaView.image = img
            }
        }
    }

    func refreshProgress() {
        guard let m = message, !mediaView.isHidden || !voiceButton.isHidden || !fileIcon.isHidden else {
            progressBack.isHidden = true
            spinner.stopAnimating()
            return
        }
        let uploading = m.state == .sending && m.progress >= 0
        progressBack.isHidden = !uploading || mediaView.isHidden
        if uploading {
            let w = mediaView.bounds.width - 24
            progressBack.frame = CGRect(x: 12, y: mediaView.bounds.height - 12, width: w, height: 4)
            progressBar.frame = CGRect(x: w - w * CGFloat(m.progress), y: 0, width: w * CGFloat(m.progress), height: 4)
            spinner.center = CGPoint(x: mediaView.bounds.midX, y: mediaView.bounds.midY)
            spinner.startAnimating()
        } else {
            spinner.stopAnimating()
        }
        if m.state == .sending && m.type == "file" { fileSize.text = "يرفع… " + String(Int(max(0, m.progress) * 100)) + "%" }
        if m.state == .sending && m.type == "voice" { voiceTime.text = String(Int(max(0, m.progress) * 100)) + "%" }
    }

    func refreshVoice() {
        guard let m = message, m.type == "voice" else { return }
        let key = m.mediaId.isEmpty ? m.clientId : m.mediaId
        let a = SupportAudio.shared
        let mine = a.playingKey == key
        voiceButton.setImage(UIImage(systemName: mine && a.isPlaying ? "pause.circle.fill" : "play.circle.fill",
                                     withConfiguration: UIImage.SymbolConfiguration(pointSize: 30)), for: .normal)
        wave.progress = mine ? CGFloat(a.progress) : 0
        wave.color = m.mine ? .white : Theme.red
        if m.state != .sending { voiceTime.text = SupportTime.duration(m.mediaDur > 0 ? m.mediaDur : m.prepared?.dur ?? 0) }
    }

    @objc private func mediaTapped() { if let m = message, m.state == .server { onMedia?(m) } }
    @objc private func voiceTapped() { if let m = message { onVoice?(m) } }
    @objc private func retryTapped() { if let m = message { onRetry?(m) } }
    @objc private func bubbleTapped(_ g: UITapGestureRecognizer) {
        guard let m = message else { return }
        if m.state == .failed { onRetry?(m); return }
        if m.type == "file" && !m.isImage && m.state == .server { onMedia?(m); return }
        // a link in a text message opens in Safari
        if m.type == "text", let d = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue),
           let hit = d.firstMatch(in: m.text, options: [], range: NSRange(location: 0, length: (m.text as NSString).length)),
           let u = hit.url, ["http", "https"].contains(u.scheme?.lowercased() ?? "") {
            UIApplication.shared.open(u)
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        message = nil
        spinner.stopAnimating()
    }
}

final class SupportDayCell: UITableViewCell {
    static let id = "SupportDayCell"
    private let pill = UILabel()
    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        pill.font = Theme.font(12, .bold)
        pill.textColor = Theme.text2
        pill.textAlignment = .center
        pill.backgroundColor = UIColor.white.withAlphaComponent(0.08)
        pill.layer.cornerRadius = 11
        pill.clipsToBounds = true
        contentView.addSubview(pill)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    func set(_ text: String) {
        pill.text = text
        let w = SupportBubbleLayout.textWidth(text, font: pill.font, max: 220) + 26
        pill.frame = CGRect(x: (contentView.bounds.width - w) / 2, y: 9, width: w, height: 22)
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        pill.center.x = contentView.bounds.midX
    }
}

/// The first screen of an empty conversation: who answers, what to send.
final class SupportWelcomeCell: UITableViewCell {
    static let id = "SupportWelcomeCell"
    private let avatar = UIImageView()
    private let title = UILabel()
    private let text = UILabel()
    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        avatar.contentMode = .scaleAspectFill
        avatar.clipsToBounds = true
        avatar.layer.cornerRadius = 36
        avatar.backgroundColor = Theme.surfaceHi
        avatar.tintColor = Theme.red
        title.font = Theme.font(18, .heavy)
        title.textColor = .white
        title.textAlignment = .center
        text.font = Theme.font(14)
        text.textColor = Theme.text2
        text.textAlignment = .center
        text.numberOfLines = 0
        [avatar, title, text].forEach { contentView.addSubview($0) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    static let body = "اكتب مشكلتك أو اقتراحك أو طلبك، وسيرد عليك فريق سيكرام هنا مباشرة.\nيمكنك إرسال صور وفيديو ورسائل صوتية وملفات PDF.\nمحادثتك خاصة ومشفّرة."
    static func height(width: CGFloat) -> CGFloat {
        return 40 + 72 + 14 + 26 + 8 + SupportBubbleLayout.textHeight(body, width: width - 60, font: Theme.font(14)) + 24
    }
    func configure() {
        avatar.image = UIImage(systemName: "bubble.left.and.bubble.right.fill")
        avatar.contentMode = .center
        if let u = SupportDeveloper.avatar {
            ImageLoader.shared.load(u, maxPixel: 72) { [weak self] img in
                if let img = img { self?.avatar.contentMode = .scaleAspectFill; self?.avatar.image = img }
            }
        }
        title.text = "مرحباً بك في الدعم والتواصل"
        text.text = SupportWelcomeCell.body
        setNeedsLayout()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let w = contentView.bounds.width
        avatar.frame = CGRect(x: (w - 72) / 2, y: 40, width: 72, height: 72)
        title.frame = CGRect(x: 20, y: avatar.frame.maxY + 14, width: w - 40, height: 26)
        let th = SupportBubbleLayout.textHeight(text.text ?? "", width: w - 60, font: text.font)
        text.frame = CGRect(x: 30, y: title.frame.maxY + 8, width: w - 60, height: th)
    }
}

/// Video thumbnails from the signed link (the server answers byte ranges: only the start of the file is read).
final class SupportThumbs {
    static let shared = SupportThumbs()
    private let cache = NSCache<NSString, UIImage>()
    private var waiting: [String: [(UIImage?) -> Void]] = [:]
    private var working: [String: AVAssetImageGenerator] = [:]     // kept alive until it answers

    func thumb(_ url: URL, key: String, _ done: @escaping (UIImage?) -> Void) {
        if let i = cache.object(forKey: key as NSString) { done(i); return }
        if waiting[key] != nil { waiting[key]?.append(done); return }
        waiting[key] = [done]
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 520, height: 520)
        let t = NSValue(time: CMTime(seconds: 0.6, preferredTimescale: 600))
        working[key] = gen
        gen.generateCGImagesAsynchronously(forTimes: [t]) { [weak self] _, cg, _, _, _ in
            let img = cg.map { UIImage(cgImage: $0) }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.working.removeValue(forKey: key)
                if let i = img { self.cache.setObject(i, forKey: key as NSString) }
                let w = self.waiting.removeValue(forKey: key) ?? []
                w.forEach { $0(img) }
            }
        }
    }
}

// MARK: - the title (who answers, online / typing / last seen)

final class SupportTitleView: UIView {
    let avatar = UIImageView()
    let name = UILabel()
    let sub = UILabel()
    let dot = UIView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        avatar.layer.cornerRadius = 17
        avatar.clipsToBounds = true
        avatar.contentMode = .scaleAspectFill
        avatar.backgroundColor = Theme.surfaceHi
        avatar.tintColor = Theme.red
        name.font = Theme.font(15.5, .bold)
        name.textColor = .white
        name.textAlignment = .right
        sub.font = Theme.font(11.5, .medium)
        sub.textColor = Theme.text3
        sub.textAlignment = .right
        dot.backgroundColor = Theme.good
        dot.layer.cornerRadius = 5
        dot.layer.borderWidth = 2
        dot.layer.borderColor = Theme.bg.cgColor
        dot.isHidden = true
        [avatar, name, sub, dot].forEach { addSubview($0) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var intrinsicContentSize: CGSize { return CGSize(width: 240, height: 40) }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        avatar.frame = CGRect(x: w - 34, y: 3, width: 34, height: 34)
        dot.frame = CGRect(x: w - 12, y: 27, width: 10, height: 10)
        let hasSub = !(sub.text ?? "").isEmpty
        name.frame = CGRect(x: 0, y: hasSub ? 2 : 10, width: w - 44, height: 20)
        sub.frame = CGRect(x: 0, y: 22, width: w - 44, height: 15)
    }
}

// MARK: - the composer (text, attach, voice, suggestions, reply)

final class SupportComposer: UIView, UITextViewDelegate {
    let suggestions = UIScrollView()
    private let chipRow = UIStackView()
    let replyBar = UIView()
    let replyLabel = UILabel()
    let replyClose = UIButton(type: .system)
    let recordBar = UIView()
    let recordDot = UIView()
    let recordTime = UILabel()
    let recordCancel = UIButton(type: .system)
    let recordSend = UIButton(type: .system)
    let inputRow = UIView()
    let text = UITextView()
    let placeholder = UILabel()
    let attach = UIButton(type: .system)
    let action = UIButton(type: .system)          // mic when empty, send when there is text
    private var textHeight: NSLayoutConstraint!
    private let stack = UIStackView()
    var onTextChange: (() -> Void)?
    var onChip: ((String) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(red: 0.03, green: 0.03, blue: 0.035, alpha: 0.97)
        let hair = UIView()
        hair.backgroundColor = UIColor.white.withAlphaComponent(0.1)
        hair.translatesAutoresizingMaskIntoConstraints = false
        addSubview(hair)
        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        suggestions.showsHorizontalScrollIndicator = false
        suggestions.alwaysBounceHorizontal = true
        chipRow.axis = .horizontal
        chipRow.spacing = 8
        chipRow.translatesAutoresizingMaskIntoConstraints = false
        suggestions.addSubview(chipRow)
        suggestions.heightAnchor.constraint(equalToConstant: 46).isActive = true
        NSLayoutConstraint.activate([
            chipRow.topAnchor.constraint(equalTo: suggestions.contentLayoutGuide.topAnchor, constant: 6),
            chipRow.bottomAnchor.constraint(equalTo: suggestions.contentLayoutGuide.bottomAnchor, constant: -4),
            chipRow.leadingAnchor.constraint(equalTo: suggestions.contentLayoutGuide.leadingAnchor, constant: 10),
            chipRow.trailingAnchor.constraint(equalTo: suggestions.contentLayoutGuide.trailingAnchor, constant: -10),
            chipRow.heightAnchor.constraint(equalTo: suggestions.frameLayoutGuide.heightAnchor, constant: -10),
        ])

        replyBar.backgroundColor = UIColor.white.withAlphaComponent(0.05)
        let replyLine = UIView()
        replyLine.backgroundColor = Theme.red
        replyLine.translatesAutoresizingMaskIntoConstraints = false
        replyLabel.font = Theme.font(13)
        replyLabel.textColor = Theme.text2
        replyLabel.textAlignment = .right
        replyLabel.translatesAutoresizingMaskIntoConstraints = false
        replyClose.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        replyClose.tintColor = Theme.text3
        replyClose.translatesAutoresizingMaskIntoConstraints = false
        [replyLine, replyLabel, replyClose].forEach { replyBar.addSubview($0) }
        NSLayoutConstraint.activate([
            replyBar.heightAnchor.constraint(equalToConstant: 44),
            replyLine.rightAnchor.constraint(equalTo: replyBar.rightAnchor, constant: -14),
            replyLine.widthAnchor.constraint(equalToConstant: 3),
            replyLine.topAnchor.constraint(equalTo: replyBar.topAnchor, constant: 8),
            replyLine.bottomAnchor.constraint(equalTo: replyBar.bottomAnchor, constant: -8),
            replyLabel.rightAnchor.constraint(equalTo: replyLine.leftAnchor, constant: -8),
            replyLabel.leftAnchor.constraint(equalTo: replyClose.rightAnchor, constant: 8),
            replyLabel.centerYAnchor.constraint(equalTo: replyBar.centerYAnchor),
            replyClose.leftAnchor.constraint(equalTo: replyBar.leftAnchor, constant: 10),
            replyClose.centerYAnchor.constraint(equalTo: replyBar.centerYAnchor),
            replyClose.widthAnchor.constraint(equalToConstant: 32),
            replyClose.heightAnchor.constraint(equalToConstant: 32),
        ])
        replyBar.isHidden = true

        recordDot.backgroundColor = Theme.red
        recordDot.layer.cornerRadius = 6
        recordTime.font = UIFont.monospacedDigitSystemFont(ofSize: 16, weight: .semibold)
        recordTime.textColor = .white
        recordTime.textAlignment = .right
        recordCancel.setTitle("إلغاء", for: .normal)
        recordCancel.setTitleColor(Theme.text2, for: .normal)
        recordCancel.titleLabel?.font = Theme.font(15, .semibold)
        recordSend.setImage(UIImage(systemName: "arrow.up.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 32)), for: .normal)
        recordSend.tintColor = Theme.red
        [recordDot, recordTime, recordCancel, recordSend].forEach { $0.translatesAutoresizingMaskIntoConstraints = false; recordBar.addSubview($0) }
        NSLayoutConstraint.activate([
            recordBar.heightAnchor.constraint(equalToConstant: 56),
            recordDot.rightAnchor.constraint(equalTo: recordBar.rightAnchor, constant: -18),
            recordDot.centerYAnchor.constraint(equalTo: recordBar.centerYAnchor),
            recordDot.widthAnchor.constraint(equalToConstant: 12),
            recordDot.heightAnchor.constraint(equalToConstant: 12),
            recordTime.rightAnchor.constraint(equalTo: recordDot.leftAnchor, constant: -10),
            recordTime.centerYAnchor.constraint(equalTo: recordBar.centerYAnchor),
            recordCancel.centerXAnchor.constraint(equalTo: recordBar.centerXAnchor),
            recordCancel.centerYAnchor.constraint(equalTo: recordBar.centerYAnchor),
            recordSend.leftAnchor.constraint(equalTo: recordBar.leftAnchor, constant: 10),
            recordSend.centerYAnchor.constraint(equalTo: recordBar.centerYAnchor),
            recordSend.widthAnchor.constraint(equalToConstant: 44),
            recordSend.heightAnchor.constraint(equalToConstant: 44),
        ])
        recordBar.isHidden = true

        text.font = Theme.font(16)
        text.textColor = .white
        text.backgroundColor = Theme.surfaceHi
        text.layer.cornerRadius = 19
        text.textContainerInset = UIEdgeInsets(top: 9, left: 10, bottom: 9, right: 10)
        text.textAlignment = .right
        text.keyboardAppearance = .dark
        text.delegate = self
        text.isScrollEnabled = false
        text.translatesAutoresizingMaskIntoConstraints = false
        placeholder.text = "اكتب رسالتك هنا"
        placeholder.font = Theme.font(16)
        placeholder.textColor = Theme.text3
        placeholder.textAlignment = .right
        placeholder.isUserInteractionEnabled = false
        placeholder.translatesAutoresizingMaskIntoConstraints = false
        attach.setImage(UIImage(systemName: "plus.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 27)), for: .normal)
        attach.tintColor = Theme.text2
        attach.accessibilityLabel = "إرفاق صورة أو فيديو أو ملف"
        attach.translatesAutoresizingMaskIntoConstraints = false
        action.tintColor = Theme.red
        action.translatesAutoresizingMaskIntoConstraints = false
        [text, placeholder, attach, action].forEach { inputRow.addSubview($0) }
        textHeight = text.heightAnchor.constraint(equalToConstant: 38)
        NSLayoutConstraint.activate([
            attach.rightAnchor.constraint(equalTo: inputRow.rightAnchor, constant: -8),
            attach.bottomAnchor.constraint(equalTo: inputRow.bottomAnchor, constant: -8),
            attach.widthAnchor.constraint(equalToConstant: 40),
            attach.heightAnchor.constraint(equalToConstant: 40),
            action.leftAnchor.constraint(equalTo: inputRow.leftAnchor, constant: 8),
            action.bottomAnchor.constraint(equalTo: inputRow.bottomAnchor, constant: -8),
            action.widthAnchor.constraint(equalToConstant: 40),
            action.heightAnchor.constraint(equalToConstant: 40),
            text.rightAnchor.constraint(equalTo: attach.leftAnchor, constant: -6),
            text.leftAnchor.constraint(equalTo: action.rightAnchor, constant: 6),
            text.topAnchor.constraint(equalTo: inputRow.topAnchor, constant: 8),
            text.bottomAnchor.constraint(equalTo: inputRow.bottomAnchor, constant: -9),
            textHeight,
            placeholder.rightAnchor.constraint(equalTo: text.rightAnchor, constant: -15),
            placeholder.leftAnchor.constraint(equalTo: text.leftAnchor, constant: 15),
            placeholder.centerYAnchor.constraint(equalTo: text.topAnchor, constant: 19),
        ])

        [suggestions, replyBar, recordBar, inputRow].forEach { stack.addArrangedSubview($0) }
        NSLayoutConstraint.activate([
            hair.topAnchor.constraint(equalTo: topAnchor),
            hair.leftAnchor.constraint(equalTo: leftAnchor),
            hair.rightAnchor.constraint(equalTo: rightAnchor),
            hair.heightAnchor.constraint(equalToConstant: 0.5),
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.leftAnchor.constraint(equalTo: leftAnchor),
            stack.rightAnchor.constraint(equalTo: rightAnchor),
            stack.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),
        ])
        refreshAction()
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    var trimmed: String { return text.text.trimmingCharacters(in: .whitespacesAndNewlines) }

    func refreshAction() {
        let empty = trimmed.isEmpty
        placeholder.isHidden = !text.text.isEmpty
        action.setImage(UIImage(systemName: empty ? "mic.fill" : "arrow.up.circle.fill",
                                withConfiguration: UIImage.SymbolConfiguration(pointSize: empty ? 22 : 32)), for: .normal)
        action.tintColor = empty ? Theme.text2 : Theme.red
        action.accessibilityLabel = empty ? "تسجيل رسالة صوتية" : "إرسال"
    }

    func setChips(_ items: [(String, String, String)]) {
        chipRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        // Arabic reads from the right: the first suggestion sits at the right end, the row opens scrolled there
        for it in items.reversed() {
            let b = UIButton(type: .system)
            b.setTitle("  " + it.1, for: .normal)
            b.setImage(UIImage(systemName: it.0), for: .normal)
            b.tintColor = .white
            b.setTitleColor(.white, for: .normal)
            b.titleLabel?.font = Theme.font(13.5, .semibold)
            b.semanticContentAttribute = .forceRightToLeft
            b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 13, bottom: 0, right: 13)
            b.layer.cornerRadius = 17
            b.layer.borderWidth = 1
            b.layer.borderColor = UIColor.white.withAlphaComponent(0.28).cgColor
            b.backgroundColor = .black
            b.accessibilityHint = "يكتب: " + it.2
            b.writeOnTap(it.2) { [weak self] s in self?.onChip?(s) }
            chipRow.addArrangedSubview(b)
        }
        suggestions.layoutIfNeeded()
        let x = max(0, suggestions.contentSize.width - suggestions.bounds.width)
        suggestions.setContentOffset(CGPoint(x: x, y: 0), animated: false)
    }

    func textViewDidChange(_ t: UITextView) {
        let fit = t.sizeThatFits(CGSize(width: t.bounds.width, height: .greatestFiniteMagnitude)).height
        let h = min(124, max(38, ceil(fit)))
        t.isScrollEnabled = fit > 124
        if abs(textHeight.constant - h) > 0.5 { textHeight.constant = h }
        let first = t.text.unicodeScalars.first { CharacterSet.letters.contains($0) }
        if let f = first { t.textAlignment = SupportBubbleCell.startsRtl(String(Character(f))) ? .right : .left } else { t.textAlignment = .right }
        refreshAction()
        onTextChange?()
    }

    func clear() {
        text.text = ""
        textViewDidChange(text)
    }
}

private extension UIButton {
    /// A chip that writes its sentence into the message field (nothing is sent on the viewer's behalf).
    func writeOnTap(_ sentence: String, _ go: @escaping (String) -> Void) {
        let box = ChipAction(sentence: sentence, go: go)
        objc_setAssociatedObject(self, &ChipAction.key, box, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        addTarget(box, action: #selector(ChipAction.fire), for: .touchUpInside)
    }
}

private final class ChipAction: NSObject {
    static var key: UInt8 = 0
    let sentence: String
    let go: (String) -> Void
    init(sentence: String, go: @escaping (String) -> Void) { self.sentence = sentence; self.go = go }
    @objc func fire() { go(sentence) }
}

// MARK: - the conversation

final class SupportChatViewController: UIViewController, UITableViewDataSource, UITableViewDelegate,
    UIImagePickerControllerDelegate, UINavigationControllerDelegate, UIDocumentPickerDelegate, QLPreviewControllerDataSource {

    private enum Row { case welcome, day(String), msg(SupportMessage) }

    private let table = UITableView(frame: .zero, style: .plain)
    private let composer = SupportComposer()
    private let titleView = SupportTitleView(frame: CGRect(x: 0, y: 0, width: 240, height: 40))
    private let banner = UIView()
    private let bannerLabel = UILabel()
    private let bannerButton = UIButton(type: .system)
    private var bannerHeight: NSLayoutConstraint!
    private var composerBottom: NSLayoutConstraint!
    private let jump = UIButton(type: .system)

    private var items: [SupportMessage] = []
    private var rows: [Row] = []
    private var lastSeq: Int64 = 0
    private var loaded = false
    private var hasMore = true
    private var loadingOlder = false
    private var banned = false
    private var adminOnline = false
    private var adminTyping = false
    private var presenceText = ""
    private var replyTo: SupportMessage?
    private var socket = SupportSocket()
    private var pollTimer: Timer?
    private var presenceTimer: Timer?
    private var typingOff: Timer?
    private var typingStop: Timer?
    private var typingSent = false
    private var retryTimer: Timer?
    private var retryDelay: TimeInterval = 2
    private var bootstrapDelay: TimeInterval = 3
    private var consentAsked = false
    private var visible = false
    private let recorder = SupportRecorder()
    private var previewURL: URL?
    private var layoutCache: [String: CGFloat] = [:]

    init() {
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    deinit {
        socket.stop()
        [pollTimer, presenceTimer, typingOff, typingStop, retryTimer].forEach { $0?.invalidate() }
        NotificationCenter.default.removeObserver(self)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        navigationItem.titleView = titleView
        navigationItem.largeTitleDisplayMode = .never

        table.backgroundColor = .black
        table.separatorStyle = .none
        table.dataSource = self
        table.delegate = self
        table.keyboardDismissMode = .interactive
        table.estimatedRowHeight = 0
        table.estimatedSectionHeaderHeight = 0
        table.estimatedSectionFooterHeight = 0
        table.register(SupportBubbleCell.self, forCellReuseIdentifier: SupportBubbleCell.id)
        table.register(SupportDayCell.self, forCellReuseIdentifier: SupportDayCell.id)
        table.register(SupportWelcomeCell.self, forCellReuseIdentifier: SupportWelcomeCell.id)
        table.contentInset = UIEdgeInsets(top: 6, left: 0, bottom: 8, right: 0)
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)

        banner.backgroundColor = UIColor(red: 0.25, green: 0.08, blue: 0.07, alpha: 1)
        banner.clipsToBounds = true
        banner.translatesAutoresizingMaskIntoConstraints = false
        bannerLabel.font = Theme.font(12.5, .semibold)
        bannerLabel.textColor = .white
        bannerLabel.numberOfLines = 2
        bannerLabel.textAlignment = .right
        bannerLabel.translatesAutoresizingMaskIntoConstraints = false
        bannerButton.setTitleColor(.white, for: .normal)
        bannerButton.titleLabel?.font = Theme.font(13, .heavy)
        bannerButton.backgroundColor = Theme.red
        bannerButton.layer.cornerRadius = 8
        bannerButton.contentEdgeInsets = UIEdgeInsets(top: 4, left: 10, bottom: 4, right: 10)
        bannerButton.translatesAutoresizingMaskIntoConstraints = false
        bannerButton.addTarget(self, action: #selector(bannerAction), for: .touchUpInside)
        banner.addSubview(bannerLabel)
        banner.addSubview(bannerButton)
        view.addSubview(banner)

        composer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(composer)

        jump.setImage(UIImage(systemName: "chevron.down.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 34)), for: .normal)
        jump.tintColor = Theme.surfaceHi
        jump.backgroundColor = .white
        jump.layer.cornerRadius = 19
        jump.alpha = 0
        jump.translatesAutoresizingMaskIntoConstraints = false
        jump.addTarget(self, action: #selector(jumpTapped), for: .touchUpInside)
        view.addSubview(jump)

        bannerHeight = banner.heightAnchor.constraint(equalToConstant: 0)
        composerBottom = composer.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        NSLayoutConstraint.activate([
            banner.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            banner.leftAnchor.constraint(equalTo: view.leftAnchor),
            banner.rightAnchor.constraint(equalTo: view.rightAnchor),
            bannerHeight,
            bannerButton.leftAnchor.constraint(equalTo: banner.leftAnchor, constant: 12),
            bannerButton.centerYAnchor.constraint(equalTo: banner.centerYAnchor),
            bannerLabel.rightAnchor.constraint(equalTo: banner.rightAnchor, constant: -14),
            bannerLabel.leftAnchor.constraint(equalTo: bannerButton.rightAnchor, constant: 10),
            bannerLabel.centerYAnchor.constraint(equalTo: banner.centerYAnchor),
            table.topAnchor.constraint(equalTo: banner.bottomAnchor),
            table.leftAnchor.constraint(equalTo: view.leftAnchor),
            table.rightAnchor.constraint(equalTo: view.rightAnchor),
            table.bottomAnchor.constraint(equalTo: composer.topAnchor),
            composer.leftAnchor.constraint(equalTo: view.leftAnchor),
            composer.rightAnchor.constraint(equalTo: view.rightAnchor),
            composerBottom,
            jump.leftAnchor.constraint(equalTo: view.leftAnchor, constant: 14),
            jump.bottomAnchor.constraint(equalTo: composer.topAnchor, constant: -12),
            jump.widthAnchor.constraint(equalToConstant: 38),
            jump.heightAnchor.constraint(equalToConstant: 38),
        ])

        composer.attach.addTarget(self, action: #selector(attachTapped), for: .touchUpInside)
        composer.action.addTarget(self, action: #selector(actionTapped), for: .touchUpInside)
        composer.replyClose.addTarget(self, action: #selector(cancelReply), for: .touchUpInside)
        composer.recordCancel.addTarget(self, action: #selector(cancelRecording), for: .touchUpInside)
        composer.recordSend.addTarget(self, action: #selector(sendRecording), for: .touchUpInside)
        composer.onTextChange = { [weak self] in self?.typed() }
        composer.onChip = { [weak self] s in
            guard let self = self else { return }
            self.composer.text.text = s
            self.composer.textViewDidChange(self.composer.text)
            self.composer.text.becomeFirstResponder()
        }
        recorder.onTick = { [weak self] t, _ in self?.composer.recordTime.text = SupportTime.duration(Int(t * 1000)) }
        recorder.onLimit = { [weak self] in self?.sendRecording() }

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(keyboard(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        nc.addObserver(self, selector: #selector(keyboard(_:)), name: UIResponder.keyboardWillHideNotification, object: nil)
        nc.addObserver(self, selector: #selector(becameActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(audioChanged), name: SupportAudio.changed, object: nil)
        nc.addObserver(self, selector: #selector(accountChanged), name: Account.changed, object: nil)

        socket.onEvent = { [weak self] e in self?.onEvent(e) }
        socket.onOpenChange = { [weak self] open in self?.socketChanged(open) }

        SupportUpload.cleanOld()
        restoreCache()
        updateTitle()
        refreshChips()
        bootstrap()
        loadPresence()
        socket.start()
        schedulePolling()
        presenceTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in self?.loadPresence() }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        visible = true
        SupportCenter.shared.chatVisible = true
        SupportCenter.shared.set(0)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        markRead()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        visible = false
        SupportCenter.shared.chatVisible = false
        stopTyping()
        if recorder.isRecording { _ = recorder.stop(keep: false); showRecording(false) }
        if isMovingFromParent {
            socket.stop()
            pollTimer?.invalidate()
            presenceTimer?.invalidate()
            SupportAudio.shared.stop()
        }
    }

    // MARK: rows

    private func rebuild() {
        var out: [Row] = []
        let real = items.filter { !$0.isSystem }
        if real.isEmpty && loaded { out.append(.welcome) }
        var lastDay = ""
        for m in items {
            let d = SupportTime.day(m.ts > 0 ? m.ts : Date().timeIntervalSince1970 * 1000)
            if d != lastDay { out.append(.day(d)); lastDay = d }
            out.append(.msg(m))
        }
        rows = out
        layoutCache.removeAll()
    }

    private func showName(_ i: Int) -> Bool {
        guard case .msg(let m) = rows[i], !m.mine, !m.isSystem else { return false }
        if i == 0 { return true }
        if case .msg(let p) = rows[i - 1], !p.mine, !p.isSystem, m.ts - p.ts < 10 * 60 * 1000 { return false }
        return true
    }

    private var atBottom: Bool {
        let h = table.contentSize.height + table.adjustedContentInset.bottom
        return table.contentOffset.y + table.bounds.height >= h - 80
    }

    private func reloadTable(scroll: Bool) {
        rebuild()
        table.reloadData()
        if scroll { scrollToEnd(animated: false) }
        updateJump()
    }

    private func scrollToEnd(animated: Bool) {
        guard !rows.isEmpty else { return }
        table.layoutIfNeeded()
        table.scrollToRow(at: IndexPath(row: rows.count - 1, section: 0), at: .bottom, animated: animated)
    }

    private func updateJump() {
        let show = !atBottom && rows.count > 8
        UIView.animate(withDuration: 0.2) { self.jump.alpha = show ? 1 : 0 }
    }

    @objc private func jumpTapped() { scrollToEnd(animated: true) }

    // MARK: data

    private func restoreCache() {
        let c = SupportStore.load()
        items = c.messages
        lastSeq = items.map { $0.seq }.max() ?? 0
        // a pending copy whose server copy is already stored (same client id) is a leftover: dropped
        let known = Set(items.filter { $0.mine }.map { $0.clientId }.filter { !$0.isEmpty })
        items.append(contentsOf: c.pending.filter { !known.contains($0.clientId) })
        reloadTable(scroll: true)
    }

    private func persist() { SupportStore.save(items) }

    private func bootstrap() {
        SupportAPI.get("/chat/thread") { [weak self] r in
            guard let self = self else { return }
            if !r.ok {
                self.loaded = true
                if r.error == "consent_required" { self.askConsent(nil); self.reloadTable(scroll: false); return }
                if r.authExpired { self.sessionBanner() }
                else {
                    self.setBanner(r.offline ? "لا يوجد اتصال — تعرض آخر محادثة محفوظة" : r.message, button: "إعادة")
                    if r.transient {
                        // a passing failure (offline, busy server, the chat server unable to verify the account): retried
                        let wait = self.bootstrapDelay
                        self.bootstrapDelay = min(60, self.bootstrapDelay * 2)
                        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
                            guard let self = self, self.isViewLoaded, self.view.window != nil else { return }
                            self.bootstrap()
                        }
                    }
                }
                self.reloadTable(scroll: false)
                return
            }
            let t = r.json["thread"] as? [String: Any]
            self.banned = (t?["banned"] as? Bool) ?? false
            self.bootstrapDelay = 3
            self.setBanner(nil)
            self.updateTitle()
            SupportAPI.get("/chat/messages?limit=40") { [weak self] r2 in
                guard let self = self else { return }
                self.loaded = true
                guard r2.ok else {
                    if r2.authExpired { self.sessionBanner() } else { self.setBanner(r2.message, button: "إعادة") }
                    self.reloadTable(scroll: false)
                    return
                }
                let list = (r2.json["messages"] as? [[String: Any]]) ?? []
                self.apply(list, replace: true)
                self.hasMore = list.count >= 40
                self.scrollToEnd(animated: false)
                self.markRead()
                self.flushPending()
            }
        }
    }

    private func schedulePolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: socket.isOpen ? 25 : 4, repeats: true) { [weak self] _ in self?.fetchNew() }
    }

    private func socketChanged(_ open: Bool) {
        schedulePolling()
        if open { fetchNew() }
    }

    private func fetchNew() {
        guard loaded, UIApplication.shared.applicationState == .active else { return }
        SupportAPI.get("/chat/messages?after=" + String(lastSeq) + "&limit=50") { [weak self] r in
            guard let self = self, r.ok else { return }
            if let t = r.json["thread"] as? [String: Any] { self.banned = (t["banned"] as? Bool) ?? self.banned; self.updateTitle() }
            if let on = r.json["admin_online"] as? Bool { self.adminOnline = on; self.updateTitle() }
            let list = (r.json["messages"] as? [[String: Any]]) ?? []
            if !list.isEmpty { self.apply(list, replace: false) }
            self.flushPending()
        }
    }

    private func loadOlder() {
        guard let first = items.first(where: { $0.seq > 0 })?.seq, first > 1, hasMore, !loadingOlder else { hasMore = false; return }
        loadingOlder = true
        SupportAPI.get("/chat/messages?before=" + String(first) + "&limit=40") { [weak self] r in
            guard let self = self else { return }
            self.loadingOlder = false
            guard r.ok else { return }
            let list = (r.json["messages"] as? [[String: Any]]) ?? []
            if list.count < 40 { self.hasMore = false }
            guard !list.isEmpty else { return }
            let before = self.table.contentSize.height
            let offset = self.table.contentOffset.y
            self.apply(list, replace: false, keepPosition: true)
            self.table.layoutIfNeeded()
            self.table.contentOffset.y = offset + (self.table.contentSize.height - before)
        }
    }

    private func loadPresence() {
        SupportAPI.get("/chat/presence", auth: false) { [weak self] r in
            guard let self = self, r.ok else { return }
            let st = (r.json["state"] as? String) ?? ""
            self.adminOnline = st == "online"
            if let dv = r.json["developer"] as? [String: Any] { SupportDeveloper.learn(dv) }
            let la = (r.json["last_active"] as? NSNumber)?.doubleValue ?? 0
            if la > 0 { SupportDeveloper.learn(["last_active": la]) }
            let seen = SupportTime.lastSeen(la > 0 ? la : SupportDeveloper.lastActive)
            self.presenceText = st == "off_hours" ? "خارج ساعات العمل — سنرد عند عودتنا"
                : st == "offline" ? (seen.isEmpty ? "غير متصل — اترك رسالتك وسنرد عليك" : seen) : ""
            self.updateTitle()
        }
    }

    /// Merges server messages: a message replaces its own pending copy (same client id), duplicates are replaced by seq.
    private func apply(_ list: [[String: Any]], replace: Bool, keepPosition: Bool = false) {
        let wasAtBottom = atBottom
        var server = replace ? [] : items.filter { $0.seq > 0 }
        var pending = items.filter { $0.seq == 0 }
        var incoming = false
        for o in list {
            let n = SupportMessage.from(o)
            guard n.seq > 0 else { continue }
            if n.mine && !n.clientId.isEmpty { pending.removeAll { $0.clientId == n.clientId } }
            if let i = server.firstIndex(where: { $0.seq == n.seq }) {
                if n.clientId.isEmpty { n.clientId = server[i].clientId }
                server[i] = n
            } else {
                server.append(n)
                if !n.mine && n.seq > lastSeq { incoming = true }
            }
            if !n.mine, let d = n.dev { SupportDeveloper.learn(d) }
            lastSeq = max(lastSeq, n.seq)
        }
        server.sort { $0.seq < $1.seq }
        items = server + pending
        persist()
        reloadTable(scroll: false)
        refreshChips()
        if keepPosition { return }
        if replace || wasAtBottom { scrollToEnd(animated: !replace) }
        if incoming {
            adminTyping = false
            updateTitle()
            if visible && wasAtBottom { markRead() }
            if !wasAtBottom { updateJump() }
        }
    }

    private func onEvent(_ e: [String: Any]) {
        let t = (e["t"] as? String) ?? ""
        switch t {
        case "msg":
            if let m = e["msg"] as? [String: Any] { apply([m], replace: false) }
        case "read", "delivered":
            let upto = (e["upto"] as? NSNumber)?.int64Value ?? 0
            let ts = (e["ts"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970 * 1000
            let side = (e["side"] as? String) ?? "admin"
            guard side != "user" else { return }
            var changed = false
            for m in items where m.mine && m.seq > 0 && m.seq <= upto {
                if t == "read" && m.read == 0 { m.read = ts; changed = true }
                if m.delivered == 0 { m.delivered = ts; changed = true }
            }
            if changed { table.reloadData() }
        case "typing":
            adminTyping = (e["on"] as? Bool) ?? false
            updateTitle()
            typingOff?.invalidate()
            if adminTyping {
                typingOff = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in self?.adminTyping = false; self?.updateTitle() }
            }
        case "presence":
            if (e["side"] as? String) == "admin" {
                adminOnline = (e["online"] as? Bool) ?? false
                if adminOnline { presenceText = "" } else { loadPresence() }
                updateTitle()
            }
        case "thread":
            banned = (e["banned"] as? Bool) ?? false
            updateTitle()
        case "deleted":
            let seq = (e["seq"] as? NSNumber)?.int64Value ?? 0
            for m in items where m.seq == seq { m.deleted = true; m.media = nil; m.text = "" }
            persist()
            reloadTable(scroll: false)
        case "thread_gone":
            items.removeAll()
            lastSeq = 0
            persist()
            reloadTable(scroll: false)
        default: break
        }
    }

    // MARK: title / banner / chips

    private func updateTitle() {
        titleView.name.text = SupportDeveloper.name
        var s = ""
        var color = Theme.text3
        if banned { s = "المحادثة مقيّدة" }
        else if adminTyping { s = "يكتب الآن…"; color = .white }
        else if adminOnline { s = "متصل الآن"; color = Theme.good }
        else { s = presenceText.isEmpty ? SupportTime.lastSeen(SupportDeveloper.lastActive) : presenceText }
        titleView.sub.text = s
        titleView.sub.textColor = color
        titleView.dot.isHidden = !adminOnline
        if let u = SupportDeveloper.avatar {
            ImageLoader.shared.load(u, maxPixel: 34) { [weak self] img in if let img = img { self?.titleView.avatar.image = img } }
        } else {
            titleView.avatar.image = UIImage(systemName: "bubble.left.and.bubble.right.fill")
            titleView.avatar.contentMode = .center
        }
        titleView.setNeedsLayout()
        composer.text.isEditable = !banned
        composer.attach.isEnabled = !banned
        composer.action.isEnabled = !banned
        composer.placeholder.text = banned ? "تم تقييد المحادثة من قبل الإدارة" : "اكتب رسالتك هنا"
        refreshChips()
    }

    private enum BannerKind { case retry, login }
    private var bannerKind: BannerKind = .retry

    private func setBanner(_ text: String?, button: String? = nil, kind: BannerKind = .retry) {
        bannerKind = kind
        let show = !(text ?? "").isEmpty
        bannerLabel.text = text
        bannerButton.setTitle(button, for: .normal)
        bannerButton.isHidden = button == nil
        bannerHeight.constant = show ? 52 : 0
        UIView.animate(withDuration: 0.2) { self.view.layoutIfNeeded() }
    }

    private func sessionBanner() {
        setBanner("انتهت جلسة حسابك. سجّل الدخول لمتابعة المحادثة؛ رسائلك المحفوظة باقية.", button: "تسجيل الدخول", kind: .login)
    }

    @objc private func bannerAction() {
        switch bannerKind {
        case .retry:
            setBanner(nil)
            bootstrapDelay = 3
            bootstrap()
        case .login:
            let l = LoginViewController(register: false)
            l.onSuccess = { [weak self] in
                guard let self = self else { return }
                self.setBanner(nil)
                self.socket.stop()
                self.socket = SupportSocket()
                self.socket.onEvent = { [weak self] e in self?.onEvent(e) }
                self.socket.onOpenChange = { [weak self] open in self?.socketChanged(open) }
                self.socket.start()
                self.bootstrap()
            }
            navigationController?.pushViewController(l, animated: true)
        }
    }

    @objc private func accountChanged() {
        // signed out from another screen: the conversation belongs to the account, it closes
        if !Account.shared.isLoggedIn && navigationController?.topViewController === self {
            navigationController?.popViewController(animated: true)
        }
    }

    /// Ready sentences above the field (the viewer can still edit them). The row hides while typing or recording.
    private func refreshChips() {
        let typing = !composer.trimmed.isEmpty
        let hide = typing || banned || recorder.isRecording || !composer.replyBar.isHidden
        composer.suggestions.isHidden = hide
        guard !hide else { return }
        let real = items.filter { !$0.isSystem }
        if real.isEmpty {
            composer.setChips([("exclamationmark.triangle.fill", "مشكلة في التشغيل", "أواجه مشكلة في تشغيل هذا العنوان: "),
                               ("person.crop.circle.badge.exclamationmark", "مشكلة في الحساب", "أواجه مشكلة في حسابي: "),
                               ("tv", "قناة لا تعمل", "هذه القناة لا تعمل عندي: "),
                               ("film", "طلب عنوان", "أرجو إضافة هذا العنوان: "),
                               ("star.fill", "اقتراح", "لدي اقتراح لتحسين التطبيق: ")])
        } else {
            composer.setChips([("exclamationmark.triangle.fill", "مشكلة", "أواجه مشكلة وأحتاج مساعدة: "),
                               ("film", "طلب عنوان", "أرجو إضافة هذا العنوان: "),
                               ("star.fill", "اقتراح", "لدي اقتراح لتحسين التطبيق: "),
                               ("checkmark.circle.fill", "شكراً", "شكراً لكم")])
        }
    }

    // MARK: sending

    private func newPending(_ type: String, _ text: String) -> SupportMessage {
        let m = SupportMessage()
        m.clientId = SupportMessage.newClientId()
        m.type = type
        m.text = text
        m.from = "user"
        m.ts = Date().timeIntervalSince1970 * 1000
        m.state = .sending
        if let r = replyTo, r.seq > 0 {
            m.replyTo = r.seq
            m.reply = ["preview": String(r.preview.prefix(120)), "seq": NSNumber(value: r.seq), "from": r.from]
        }
        cancelReply()
        items.append(m)
        reloadTable(scroll: true)
        return m
    }

    private func dispatch(_ m: SupportMessage) {
        m.state = .sending
        m.failMessage = ""
        var b: [String: Any] = ["client_id": m.clientId, "type": m.type]
        if !m.text.isEmpty && m.type == "text" { b["text"] = m.text }
        if m.replyTo > 0 { b["reply_to"] = NSNumber(value: m.replyTo) }
        if !m.mediaId.isEmpty { b["media_id"] = m.mediaId }
        SupportAPI.post("/chat/send", b) { [weak self] r in
            guard let self = self else { return }
            if r.ok {
                var list: [[String: Any]] = []
                if var mm = r.json["message"] as? [String: Any] { mm["client_id"] = m.clientId; list.append(mm) }
                if let auto = r.json["auto"] as? [String: Any] { list.append(auto) }
                if let p = m.prepared { try? FileManager.default.removeItem(at: p.file) }
                m.state = .server
                self.retryDelay = 2
                if list.isEmpty { m.state = .server } else { self.apply(list, replace: false) }
                self.scrollToEnd(animated: true)
                return
            }
            m.state = .failed
            m.failMessage = r.message
            if r.authExpired {
                m.state = .waiting
                m.failMessage = ""
                self.sessionBanner()
            } else if r.error == "consent_required" {
                m.state = .waiting
                m.failMessage = ""
                self.askConsent(m)
            } else if r.error == "banned" {
                self.banned = true
                self.updateTitle()
            } else if r.transient {
                // no connection / busy server: stays queued (never shown as sent) and is retried with a growing delay
                m.state = .waiting
                m.failMessage = ""
                self.retryTimer?.invalidate()
                self.retryTimer = Timer.scheduledTimer(withTimeInterval: self.retryDelay, repeats: false) { [weak self] _ in self?.flushPending() }
                self.retryDelay = min(60, self.retryDelay * 2)
            }
            self.persist()
            self.reloadTable(scroll: false)
        }
    }

    private func flushPending() {
        for m in items where m.seq == 0 && m.state == .waiting {
            if m.type == "text" || !m.mediaId.isEmpty { dispatch(m) } else if m.prepared != nil { upload(m) }
        }
    }

    private func retry(_ m: SupportMessage) {
        guard m.seq == 0 else { return }
        if m.type != "text" && m.mediaId.isEmpty && m.prepared != nil { upload(m) } else { dispatch(m) }
        reloadTable(scroll: false)
    }

    @objc private func actionTapped() {
        let s = composer.trimmed
        if s.isEmpty { startRecording(); return }
        guard !banned else { return }
        composer.clear()
        stopTyping()
        let m = newPending("text", String(s.prefix(4000)))
        dispatch(m)
        refreshChips()
    }

    private func sendMedia(_ p: SupportUpload.Prepared) {
        guard !banned else { return }
        let m = newPending(p.kind, "")
        m.prepared = p
        m.localImage = p.preview
        m.media = ["kind": p.kind, "name": p.name, "size": NSNumber(value: p.size), "dur": p.dur, "w": p.w, "h": p.h, "wave": p.wave]
        m.progress = 0
        reloadTable(scroll: true)
        upload(m)
    }

    private func upload(_ m: SupportMessage) {
        guard let p = m.prepared else { return }
        let up = m.upload ?? SupportUpload()
        m.upload = up
        m.state = .sending
        m.failMessage = ""
        m.progress = 0
        up.run(p, progress: { [weak self] f in
            m.progress = f
            self?.refreshProgress(of: m)
        }, done: { [weak self] fid, err in
            guard let self = self else { return }
            m.progress = -1
            if let id = fid {
                var md = m.media ?? [:]
                md["id"] = id
                m.media = md
                self.dispatch(m)
            } else {
                m.state = .failed
                m.failMessage = err
                self.reloadTable(scroll: false)
            }
        })
    }

    private func refreshProgress(of m: SupportMessage) {
        for (i, r) in rows.enumerated() {
            if case .msg(let x) = r, x === m, let c = table.cellForRow(at: IndexPath(row: i, section: 0)) as? SupportBubbleCell {
                c.refreshProgress()
            }
        }
    }

    private func markRead() {
        guard visible, UIApplication.shared.applicationState == .active, lastSeq > 0 else { return }
        var unread = false
        let now = Date().timeIntervalSince1970 * 1000
        for m in items where !m.mine && m.seq > 0 && m.read == 0 { unread = true; m.read = now }
        SupportCenter.shared.set(0)
        guard unread else { return }
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        if socket.isOpen { socket.send(["t": "read", "upto": NSNumber(value: lastSeq)]) }
        else { SupportAPI.post("/chat/read", ["upto": NSNumber(value: lastSeq)]) }
    }

    @objc private func becameActive() {
        guard visible else { return }
        fetchNew()
        loadPresence()
        markRead()
    }

    // MARK: typing

    private func typed() {
        refreshChips()
        guard socket.isOpen, !composer.trimmed.isEmpty else { return }
        if !typingSent { typingSent = true; socket.send(["t": "typing", "on": true]) }
        typingStop?.invalidate()
        typingStop = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in self?.stopTyping() }
    }

    private func stopTyping() {
        typingStop?.invalidate()
        guard typingSent else { return }
        typingSent = false
        socket.send(["t": "typing", "on": false])
    }

    // MARK: reply

    private func startReply(_ m: SupportMessage) {
        replyTo = m
        composer.replyLabel.text = "ردّ على " + (m.mine ? "رسالتك" : SupportDeveloper.name) + ": " + m.preview
        composer.replyBar.isHidden = false
        refreshChips()
        composer.text.becomeFirstResponder()
    }

    @objc private func cancelReply() {
        replyTo = nil
        composer.replyBar.isHidden = true
        refreshChips()
    }

    // MARK: voice

    private func startRecording() {
        guard !banned else { return }
        SupportAudio.shared.stop()
        recorder.start { [weak self] ok, msg in
            guard let self = self else { return }
            if !ok { self.alert("الميكروفون", msg); return }
            self.composer.recordTime.text = "0:00"
            self.showRecording(true)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
    }

    private func showRecording(_ on: Bool) {
        composer.recordBar.isHidden = !on
        composer.inputRow.isHidden = on
        refreshChips()
        composer.recordDot.layer.removeAllAnimations()
        if on {
            let a = CABasicAnimation(keyPath: "opacity")
            a.fromValue = 1
            a.toValue = 0.2
            a.duration = 0.6
            a.autoreverses = true
            a.repeatCount = .infinity
            composer.recordDot.layer.add(a, forKey: "rec")
        }
    }

    @objc private func cancelRecording() {
        _ = recorder.stop(keep: false)
        showRecording(false)
    }

    @objc private func sendRecording() {
        let p = recorder.stop(keep: true)
        showRecording(false)
        if let p = p { sendMedia(p) } else { toastBanner("الرسالة الصوتية قصيرة جداً") }
    }

    @objc private func audioChanged() {
        for c in table.visibleCells { (c as? SupportBubbleCell)?.refreshVoice() }
    }

    // MARK: attachments

    @objc private func attachTapped() {
        guard !banned else { return }
        view.endEditing(true)
        let a = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "الصور والفيديو", style: .default) { [weak self] _ in self?.pickMedia() })
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            a.addAction(UIAlertAction(title: "الكاميرا", style: .default) { [weak self] _ in self?.openCamera() })
        }
        a.addAction(UIAlertAction(title: "ملف (PDF · ZIP · Word)", style: .default) { [weak self] _ in self?.pickFile() })
        a.addAction(UIAlertAction(title: "إلغاء", style: .cancel))
        a.popoverPresentationController?.sourceView = composer.attach
        a.popoverPresentationController?.sourceRect = composer.attach.bounds
        present(a, animated: true)
    }

    private func pickMedia() {
        if #available(iOS 14.0, *) {
            var cfg = PHPickerConfiguration()
            cfg.filter = .any(of: [.images, .videos])
            cfg.selectionLimit = 5
            let p = PHPickerViewController(configuration: cfg)
            p.delegate = self
            present(p, animated: true)
        } else {
            let p = UIImagePickerController()
            p.sourceType = .photoLibrary
            p.mediaTypes = ["public.image", "public.movie"]
            p.delegate = self
            present(p, animated: true)
        }
    }

    private func openCamera() {
        let p = UIImagePickerController()
        p.sourceType = .camera
        p.mediaTypes = ["public.image", "public.movie"]
        p.videoQuality = .typeHigh
        p.videoMaximumDuration = 600
        p.delegate = self
        present(p, animated: true)
    }

    private func pickFile() {
        let types = ["com.adobe.pdf", "public.zip-archive", "com.microsoft.word.doc", "org.openxmlformats.wordprocessingml.document",
                     "public.image", "public.movie"]
        let p = UIDocumentPickerViewController(documentTypes: types, in: .import)
        p.delegate = self
        p.allowsMultipleSelection = false
        present(p, animated: true)
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        if let url = info[.mediaURL] as? URL { prepareVideo(url) }
        else if let img = info[.originalImage] as? UIImage { prepareImage(img) }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true) }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first else { return }
        if ["mp4", "mov", "m4v", "3gp"].contains(u.pathExtension.lowercased()) { prepareVideo(u); return }
        let (p, err) = SupportUpload.prepareFile(u)
        if let p = p { sendMedia(p) } else { alert("لا يمكن إرسال الملف", err) }
    }

    fileprivate func prepareImage(_ img: UIImage) {
        toastBanner("يجهّز الصورة…")
        DispatchQueue.global(qos: .userInitiated).async {
            let p = SupportUpload.prepareImage(img)
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.setBanner(nil)
                if let p = p { self.sendMedia(p) } else { self.alert("الصورة", "تعذّر تجهيز الصورة.") }
            }
        }
    }

    fileprivate func prepareVideo(_ url: URL) {
        toastBanner("يجهّز الفيديو… قد يستغرق ذلك لحظات")
        SupportUpload.prepareVideo(url) { [weak self] p, err in
            guard let self = self else { return }
            self.setBanner(nil)
            if let p = p { self.sendMedia(p) } else { self.alert("الفيديو", err) }
        }
    }

    private func toastBanner(_ text: String) { setBanner(text) }

    // MARK: opening media

    private func open(_ m: SupportMessage) {
        let video = m.type == "video" || m.mediaKind == "video"
        if m.isImage || m.type == "image" {
            navigationController?.present(SupportImageViewer(message: m), animated: true)
        } else if video {
            guard let u = m.mediaURL ?? m.prepared?.file else { return }
            SupportAudio.shared.stop()
            let p = AVPlayerViewController()
            p.player = AVPlayer(url: u)
            p.modalPresentationStyle = .fullScreen
            present(p, animated: true) { p.player?.play() }
        } else if m.type == "file" {
            guard let u = m.mediaURL else { return }
            setBanner("يحمّل الملف…")
            URLSession.shared.downloadTask(with: u) { tmp, resp, _ in
                var dest: URL?
                if let tmp = tmp, ((resp as? HTTPURLResponse)?.statusCode ?? 0) == 200 {
                    let name = m.mediaName.replacingOccurrences(of: "/", with: "_")
                    let d = FileManager.default.temporaryDirectory.appendingPathComponent("cigram-support", isDirectory: true)
                    try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
                    let target = d.appendingPathComponent(name.isEmpty ? "file" : name)
                    try? FileManager.default.removeItem(at: target)
                    if (try? FileManager.default.moveItem(at: tmp, to: target)) != nil { dest = target }
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    self.setBanner(nil)
                    guard let f = dest else { self.alert("الملف", "تعذّر تحميل الملف. حاول مجدداً."); return }
                    self.previewURL = f
                    let q = QLPreviewController()
                    q.dataSource = self
                    self.present(q, animated: true)
                }
            }.resume()
        }
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { return previewURL == nil ? 0 : 1 }
    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        return (previewURL ?? URL(fileURLWithPath: "/")) as NSURL
    }

    private func playVoice(_ m: SupportMessage) {
        let key = m.mediaId.isEmpty ? m.clientId : m.mediaId
        guard let u = m.mediaURL ?? m.prepared?.file else { return }
        SupportAudio.shared.toggle(key: key, url: u)
    }

    // MARK: consent (the server still asks for the policy consent before the first message)

    private func askConsent(_ waiting: SupportMessage?) {
        guard !consentAsked else { return }
        consentAsked = true
        SupportAPI.get("/ads/config", auth: false) { [weak self] r in
            guard let self = self else { return }
            let cfg = r.json["settings"] as? [String: Any]
            let version = max(1, (cfg?["policies_version"] as? NSNumber)?.intValue ?? 1)
            var txt = "قبل أول رسالة نطلب موافقتك على سياسة التواصل:\n\n"
            var lines: [String] = []
            for p in (cfg?["policies"] as? [Any]) ?? [] {
                if let o = p as? [String: Any] {
                    let t = (o["title"] as? String) ?? ""
                    let b = (o["body"] as? String) ?? ""
                    let line = b.isEmpty ? t : t + ": " + b
                    if !line.trimmingCharacters(in: .whitespaces).isEmpty { lines.append("• " + line) }
                } else if let s = p as? String, !s.isEmpty { lines.append("• " + s) }
            }
            if lines.isEmpty { lines = ["• هذه المحادثة للمشاكل والاقتراحات والطلبات فقط.", "• لا ترسل كلمات مرور أو بيانات دفع.", "• تُحفظ الرسائل مشفّرة للرد عليك ومتابعة طلبك."] }
            txt += lines.joined(separator: "\n")
            let a = UIAlertController(title: "سياسة التواصل", message: txt, preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "ليس الآن", style: .cancel) { [weak self] _ in self?.consentAsked = false })
            a.addAction(UIAlertAction(title: "أوافق", style: .default) { [weak self] _ in
                SupportAPI.post("/ads/consent", ["version": version]) { [weak self] r2 in
                    guard let self = self else { return }
                    self.consentAsked = false
                    if !r2.ok { self.alert("سياسة التواصل", r2.message); return }
                    if let w = waiting { self.dispatch(w) } else { self.flushPending() }
                    if self.lastSeq == 0 { self.bootstrap() }
                }
            })
            self.present(a, animated: true)
        }
    }

    private func alert(_ title: String, _ msg: String) {
        let a = UIAlertController(title: title, message: msg, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "حسناً", style: .cancel))
        present(a, animated: true)
    }

    // MARK: keyboard

    @objc private func keyboard(_ n: Notification) {
        guard let end = (n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
        let local = view.convert(end, from: nil)
        let overlap = n.name == UIResponder.keyboardWillHideNotification ? 0 : max(0, view.bounds.maxY - local.minY)
        let stick = atBottom
        let dur = (n.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        let curve = (n.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 7
        composerBottom.constant = -overlap
        UIView.animate(withDuration: dur, delay: 0, options: UIView.AnimationOptions(rawValue: curve << 16), animations: {
            self.view.layoutIfNeeded()
        }, completion: { _ in if stick { self.scrollToEnd(animated: false) } })
    }

    // MARK: table

    func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { return rows.count }

    func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat {
        switch rows[ip.row] {
        case .welcome: return SupportWelcomeCell.height(width: t.bounds.width)
        case .day: return 40
        case .msg(let m):
            let key = String(ip.row) + "|" + String(Int(t.bounds.width))
            if let h = layoutCache[key] { return h }
            let h = SupportBubbleLayout.make(m, showName: showName(ip.row), width: t.bounds.width).height
            layoutCache[key] = h
            return h
        }
    }

    func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        switch rows[ip.row] {
        case .welcome:
            let c = t.dequeueReusableCell(withIdentifier: SupportWelcomeCell.id, for: ip) as! SupportWelcomeCell
            c.configure()
            return c
        case .day(let d):
            let c = t.dequeueReusableCell(withIdentifier: SupportDayCell.id, for: ip) as! SupportDayCell
            c.set(d)
            return c
        case .msg(let m):
            let c = t.dequeueReusableCell(withIdentifier: SupportBubbleCell.id, for: ip) as! SupportBubbleCell
            c.configure(m, showName: showName(ip.row), width: t.bounds.width)
            c.onMedia = { [weak self] m in self?.open(m) }
            c.onVoice = { [weak self] m in self?.playVoice(m) }
            c.onRetry = { [weak self] m in self?.retry(m) }
            return c
        }
    }

    func scrollViewDidScroll(_ s: UIScrollView) {
        if s.contentOffset.y < 120 && loaded && hasMore && !loadingOlder && s.isDragging { loadOlder() }
        updateJump()
    }

    func tableView(_ t: UITableView, contextMenuConfigurationForRowAt ip: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard ip.row < rows.count, case .msg(let m) = rows[ip.row], !m.isSystem, !m.deleted else { return nil }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            var acts: [UIAction] = []
            if !m.text.isEmpty {
                acts.append(UIAction(title: "نسخ", image: UIImage(systemName: "doc.on.doc")) { _ in UIPasteboard.general.string = m.text })
            }
            if m.seq > 0 && !(self?.banned ?? true) {
                acts.append(UIAction(title: "رد", image: UIImage(systemName: "arrowshape.turn.up.left")) { _ in self?.startReply(m) })
            }
            if m.seq == 0 && m.state == .failed {
                acts.append(UIAction(title: "إعادة المحاولة", image: UIImage(systemName: "arrow.clockwise")) { _ in self?.retry(m) })
            }
            if m.seq == 0 && m.state != .sending {
                acts.append(UIAction(title: "حذف", image: UIImage(systemName: "trash"), attributes: .destructive) { _ in
                    guard let self = self else { return }
                    m.upload?.cancel()
                    if let p = m.prepared { try? FileManager.default.removeItem(at: p.file) }
                    self.items.removeAll { $0 === m }
                    self.persist()
                    self.reloadTable(scroll: false)
                })
            }
            return UIMenu(title: "", children: acts)
        }
    }
}

@available(iOS 14.0, *)
extension SupportChatViewController: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        for r in results {
            let provider = r.itemProvider
            if provider.hasItemConformingToTypeIdentifier("public.movie") {
                provider.loadFileRepresentation(forTypeIdentifier: "public.movie") { url, _ in
                    // the system deletes this file when the callback returns: copy it first
                    guard let u = url else { return }
                    let copy = FileManager.default.temporaryDirectory.appendingPathComponent("pick_" + SupportMessage.newClientId() + "." + (u.pathExtension.isEmpty ? "mov" : u.pathExtension))
                    guard (try? FileManager.default.copyItem(at: u, to: copy)) != nil else { return }
                    DispatchQueue.main.async { [weak self] in self?.prepareVideo(copy) }
                }
            } else if provider.canLoadObject(ofClass: UIImage.self) {
                provider.loadObject(ofClass: UIImage.self) { obj, _ in
                    guard let img = obj as? UIImage else { return }
                    DispatchQueue.main.async { [weak self] in self?.prepareImage(img) }
                }
            }
        }
    }
}

// MARK: - full-screen photo

final class SupportImageViewer: UIViewController, UIScrollViewDelegate {
    private let message: SupportMessage
    private let scroll = UIScrollView()
    private let image = UIImageView()
    private let close = UIButton(type: .system)
    private let share = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .large)

    init(message: SupportMessage) {
        self.message = message
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalTransitionStyle = .crossDissolve
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var prefersStatusBarHidden: Bool { return true }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        scroll.frame = view.bounds
        scroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.delegate = self
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        view.addSubview(scroll)
        image.contentMode = .scaleAspectFit
        image.frame = scroll.bounds
        image.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.addSubview(image)
        let dbl = UITapGestureRecognizer(target: self, action: #selector(zoom(_:)))
        dbl.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(dbl)
        close.setImage(UIImage(systemName: "xmark.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 30)), for: .normal)
        close.tintColor = .white
        close.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        share.setImage(UIImage(systemName: "square.and.arrow.up", withConfiguration: UIImage.SymbolConfiguration(pointSize: 22)), for: .normal)
        share.tintColor = .white
        share.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        spinner.color = .white
        spinner.hidesWhenStopped = true
        [close, share, spinner].forEach { view.addSubview($0) }
        image.image = message.localImage ?? ImageLoader.shared.cached(message.mediaURL, maxPixel: 320, key: "chat:" + message.mediaId)
        if let u = message.mediaURL {
            spinner.startAnimating()
            ImageLoader.shared.load(u, maxPixel: 0, key: "chat-full:" + message.mediaId) { [weak self] img in
                self?.spinner.stopAnimating()
                if let img = img { self?.image.image = img }
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let s = view.safeAreaInsets
        close.frame = CGRect(x: 14, y: s.top + 10, width: 44, height: 44)
        share.frame = CGRect(x: view.bounds.width - 58, y: s.top + 10, width: 44, height: 44)
        spinner.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { return image }

    @objc private func zoom(_ g: UITapGestureRecognizer) {
        if scroll.zoomScale > 1 { scroll.setZoomScale(1, animated: true); return }
        let p = g.location(in: image)
        scroll.zoom(to: CGRect(x: p.x - 60, y: p.y - 60, width: 120, height: 120), animated: true)
    }

    @objc private func closeTapped() { dismiss(animated: true) }

    @objc private func shareTapped() {
        guard let img = image.image else { return }
        let a = UIActivityViewController(activityItems: [img], applicationActivities: nil)
        a.popoverPresentationController?.sourceView = share
        present(a, animated: true)
    }
}
