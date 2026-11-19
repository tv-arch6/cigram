import UIKit
import UserNotifications

/// The Cigram account — the same server and the same rules as the Android app (cigram-auth-api).
final class Account {
    static let shared = Account()
    static let changed = Notification.Name("cigram.account.changed")
    static let base = "https://cigram-auth-api.wwq-mixtv.workers.dev"
    static let supportEmail = "app.cigram@gmail.com"

    private let defaults = UserDefaults.standard
    private let userKey = "cg.account.user"
    private let expiryKey = "cg.account.expires"
    private var renewing = false
    private var renewWaiters: [(String?) -> Void] = []

    var token: String? { return Keychain.get("token") }
    var isLoggedIn: Bool { return (token ?? "").isEmpty == false }
    var user: [String: Any] { return (defaults.dictionary(forKey: userKey)) ?? [:] }
    var userId: String? { return isLoggedIn ? (user["id"].map { String(describing: $0) }) : nil }
    var name: String { return (user["name"] as? String) ?? "" }
    var email: String { return (user["email"] as? String) ?? "" }
    var publicId: String { return (user["public_id"].map { String(describing: $0) }) ?? "" }
    var avatarURL: URL? {
        guard let s = user["avatar_url"] as? String, s.hasPrefix("https://") else { return nil }
        return URL(string: s)
    }
    /// «أ» for «أحمد», «JD» for «John Doe»: the avatar's letters when there is no picture.
    var initials: String {
        let parts = name.split(separator: " ").prefix(2)
        let s = parts.compactMap { $0.first }.map { String($0) }.joined()
        return s.isEmpty ? String(email.prefix(1)).uppercased() : s.uppercased()
    }
    /// When the server said the session ends (30 days after sign-in on the current auth API).
    var expiresAt: Date? {
        let t = defaults.double(forKey: expiryKey)
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }
    /// The sign-in is kept sealed in the Keychain (this device only) so an ended session renews itself silently,
    /// the same rule as the Android app (CigramSession): the viewer is never thrown out of the support chat.
    var canRenew: Bool { return Keychain.get("cred") != nil }

    typealias Done = (_ ok: Bool, _ message: String) -> Void

    func login(email: String, password: String, _ done: @escaping Done) {
        call("POST", "/login", body: ["email": email, "password": password]) { status, json in
            self.adopt(status, json, credentials: (email, password), done)
        }
    }

    func register(name: String, email: String, password: String, _ done: @escaping Done) {
        let body: [String: Any] = ["name": name, "email": email, "password": password, "gender": "unspecified",
                                   "accept_terms": "true", "terms_version": "1",
                                   "terms_accepted_at": String(Int(Date().timeIntervalSince1970 * 1000))]
        call("POST", "/register", body: body) { status, json in self.adopt(status, json, credentials: (email, password), done) }
    }

    private func adopt(_ status: Int, _ json: [String: Any]?, credentials: (String, String)?, _ done: @escaping Done) {
        let ok = (json?["success"] as? Bool) ?? (json?["ok"] as? Bool) ?? (status == 200)
        guard ok, let token = json?["token"] as? String, !token.isEmpty, let user = json?["user"] as? [String: Any] else {
            done(false, Account.message(json, status: status))
            return
        }
        Keychain.set(token, for: "token")
        if let c = credentials, let data = try? JSONSerialization.data(withJSONObject: ["e": c.0, "p": c.1]) {
            Keychain.set(String(data: data, encoding: .utf8), for: "cred")
        }
        let exp = (json?["expires_at"] as? String) ?? (json?["expiresAt"] as? String) ?? ""
        if let d = Account.isoDate(exp) { defaults.set(d.timeIntervalSince1970, forKey: expiryKey) } else { defaults.removeObject(forKey: expiryKey) }
        defaults.set(user.filter { !($0.value is NSNull) }, forKey: userKey)
        NotificationCenter.default.post(name: Account.changed, object: nil)
        done(true, "")
    }

    static func message(_ json: [String: Any]?, status: Int) -> String {
        if let m = json?["message"] as? String, !m.isEmpty { return m }
        if let m = json?["error"] as? String, !m.isEmpty, m.contains(" ") { return m }
        switch status {
        case 0: return "تعذّر الاتصال. تحقق من الإنترنت."
        case 401: return "البريد الإلكتروني أو كلمة المرور غير صحيحة."
        case 409: return "هذا البريد مسجّل مسبقاً. سجّل الدخول بدلاً من ذلك."
        case 429: return "محاولات كثيرة. انتظر قليلاً ثم حاول مجدداً."
        case 500...599: return "الخادم مشغول الآن. حاول بعد قليل."
        default: return "تعذّر تسجيل الدخول."
        }
    }

    static func isoDate(_ s: String) -> Date? {
        if s.isEmpty { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    /// Refreshes the profile; a 401 means the session ended on the server: it is renewed silently when the sign-in is
    /// kept, otherwise the account signs out on this iPhone.
    func refresh() {
        guard isLoggedIn else { return }
        call("GET", "/me", body: nil) { status, json in
            if status == 401 {
                self.renew { fresh in if fresh == nil && !self.canRenew { self.clear() } }
                return
            }
            if let u = json?["user"] as? [String: Any] {
                self.defaults.set(u.filter { !($0.value is NSNull) }, forKey: self.userKey)
                NotificationCenter.default.post(name: Account.changed, object: nil)
            }
        }
    }

    /// One silent re-login with the kept sign-in; requests failing together share it. `done(nil)` = not possible now.
    func renew(_ done: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard let raw = Keychain.get("cred"), let d = raw.data(using: .utf8),
                  let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: String],
                  let e = o["e"], let p = o["p"] else { done(nil); return }
            self.renewWaiters.append(done)
            if self.renewing { return }
            self.renewing = true
            self.call("POST", "/login", body: ["email": e, "password": p]) { status, json in
                self.adopt(status, json, credentials: (e, p)) { ok, _ in
                    // a refused password (changed on another device) is final: forget it, never try it again
                    if !ok && status == 401 { Keychain.set(nil, for: "cred") }
                    let t = ok ? self.token : nil
                    self.renewing = false
                    let w = self.renewWaiters
                    self.renewWaiters.removeAll()
                    w.forEach { $0(t) }
                }
            }
        }
    }

    enum Probe { case valid, invalid, unknown }

    /// Asks the auth service itself whether `token` is a live session (the support chat asks before calling it «ended»).
    func probe(_ token: String, _ done: @escaping (Probe) -> Void) {
        guard let url = URL(string: Account.base + "/me") else { done(.unknown); return }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: req) { _, resp, _ in
            let s = (resp as? HTTPURLResponse)?.statusCode ?? 0
            DispatchQueue.main.async { done(s == 200 ? .valid : s == 401 ? .invalid : .unknown) }
        }.resume()
    }

    func logout() {
        if isLoggedIn { call("POST", "/logout", body: [:]) { _, _ in } }
        clear()
    }

    func deleteAccount(password: String, _ done: @escaping Done) {
        call("POST", "/account/delete", body: ["password": password, "confirm": "DELETE"]) { status, json in
            if (json?["success"] as? Bool) == true || (json?["ok"] as? Bool) == true {
                self.clear()
                done(true, (json?["message"] as? String) ?? "تم حذف الحساب")
            } else {
                done(false, (json?["message"] as? String) ?? "تعذّر حذف الحساب")
            }
        }
    }

    /// Changes the display name (same endpoint as the Android «الحساب الشخصي»).
    func updateName(_ newName: String, _ done: @escaping Done) {
        call("POST", "/profile/update", body: ["name": newName]) { status, json in
            let ok = (json?["success"] as? Bool) ?? (json?["ok"] as? Bool) ?? (status == 200)
            if ok {
                var u = self.user
                if let nu = json?["user"] as? [String: Any] { u = nu.filter { !($0.value is NSNull) } } else { u["name"] = newName }
                self.defaults.set(u, forKey: self.userKey)
                NotificationCenter.default.post(name: Account.changed, object: nil)
                done(true, "")
            } else {
                done(false, Account.message(json, status: status))
            }
        }
    }

    /// «أعجبني / لم يعجبني» saved on the account (same endpoint as the Android details page).
    func react(contentId: String, value: Int) {
        guard isLoggedIn, !contentId.isEmpty else { return }
        call("POST", "/reactions", body: ["content_id": contentId, "reaction": value == 1 ? "like" : value == -1 ? "dislike" : "none"]) { _, _ in }
    }

    private func clear() {
        Keychain.set(nil, for: "token")
        Keychain.set(nil, for: "cred")
        defaults.removeObject(forKey: userKey)
        defaults.removeObject(forKey: expiryKey)
        DispatchQueue.main.async { NotificationCenter.default.post(name: Account.changed, object: nil) }
    }

    private func call(_ method: String, _ path: String, body: [String: Any]?, _ done: @escaping (Int, [String: Any]?) -> Void) {
        guard let url = URL(string: Account.base + path) else { return }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("Cigram-iOS/" + AppInfo.version, forHTTPHeaderField: "X-Cigram-Client")
        if let t = token, !t.isEmpty { req.setValue("Bearer " + t, forHTTPHeaderField: "Authorization") }
        if let b = body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: b)
        }
        URLSession.shared.dataTask(with: req) { data, resp, _ in
            let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let json = data.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
            DispatchQueue.main.async { done(status, json) }
        }.resume()
    }
}

enum AppInfo {
    static var version: String { return (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "" }
    static var build: String { return (Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "" }
}

// MARK: - حسابي

/// One row of «حسابي»: a tinted icon square at the right, the title (and a grey line under it), a red count badge, and the
/// chevron at the left. Laid out by hand from the right edge (the page is Arabic).
final class AccountRowCell: UITableViewCell {
    static let id = "AccountRowCell"
    private let iconBox = UIView()
    private let icon = UIImageView()
    let title = UILabel()
    let sub = UILabel()
    private let badge = UILabel()
    private let chevron = UIImageView(image: UIImage(systemName: "chevron.left"))
    private let lock = UIImageView(image: UIImage(systemName: "lock.fill"))
    var showsChevron = true { didSet { setNeedsLayout() } }

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = Theme.surface
        let sel = UIView()
        sel.backgroundColor = Theme.surfaceHi
        selectedBackgroundView = sel
        iconBox.layer.cornerRadius = 9
        icon.contentMode = .scaleAspectFit
        icon.tintColor = .white
        title.font = Theme.font(15.5, .semibold)
        title.textColor = .white
        title.textAlignment = .right
        sub.font = Theme.font(12.5)
        sub.textColor = Theme.text3
        sub.textAlignment = .right
        badge.font = Theme.font(12, .heavy)
        badge.textColor = .white
        badge.backgroundColor = Theme.red
        badge.textAlignment = .center
        badge.layer.cornerRadius = 10
        badge.clipsToBounds = true
        badge.isHidden = true
        chevron.tintColor = Theme.text3
        chevron.contentMode = .scaleAspectFit
        lock.tintColor = Theme.text3
        lock.contentMode = .scaleAspectFit
        lock.isHidden = true
        iconBox.addSubview(icon)
        [iconBox, title, sub, badge, chevron, lock].forEach { contentView.addSubview($0) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func set(_ text: String, sub s: String?, symbol: String, tint: UIColor, badge count: Int = 0, locked: Bool = false, destructive: Bool = false) {
        title.text = text
        title.textColor = destructive ? Theme.red : .white
        sub.text = s
        sub.isHidden = (s ?? "").isEmpty
        icon.image = UIImage(systemName: symbol)
        iconBox.backgroundColor = tint
        badge.isHidden = count <= 0
        badge.text = count > 99 ? "99+" : String(count)
        lock.isHidden = !locked
        accessibilityLabel = text + (count > 0 ? "، " + String(count) + " جديدة" : "") + (locked ? "، يتطلب تسجيل الدخول" : "")
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = contentView.bounds.width
        let h = contentView.bounds.height
        iconBox.frame = CGRect(x: w - 16 - 32, y: (h - 32) / 2, width: 32, height: 32)
        icon.frame = iconBox.bounds.insetBy(dx: 6.5, dy: 6.5)
        var left: CGFloat = 14
        chevron.isHidden = !showsChevron
        if showsChevron { chevron.frame = CGRect(x: left, y: (h - 14) / 2, width: 9, height: 14); left += 17 }
        if !lock.isHidden { lock.frame = CGRect(x: left, y: (h - 15) / 2, width: 13, height: 15); left += 21 }
        if !badge.isHidden {
            let bw = max(20, (badge.text ?? "").size(withAttributes: [.font: badge.font!]).width + 12)
            badge.frame = CGRect(x: left, y: (h - 20) / 2, width: bw, height: 20)
            left += bw + 8
        }
        let textRight = iconBox.frame.minX - 12
        let tw = max(20, textRight - left)
        if sub.isHidden {
            title.frame = CGRect(x: left, y: (h - 22) / 2, width: tw, height: 22)
        } else {
            title.frame = CGRect(x: left, y: h / 2 - 20, width: tw, height: 21)
            sub.frame = CGRect(x: left, y: h / 2 + 2, width: tw, height: 17)
        }
    }
}

/// The card at the top of «حسابي». A visitor: the welcome, what an account gives, and two clear buttons on the right side —
/// «تسجيل الدخول» and «إنشاء حساب جديد». Signed in: picture, name, e-mail, the public id, and three shortcuts.
final class AccountHeaderView: UIView {
    var onLogin: (() -> Void)?
    var onRegister: (() -> Void)?
    var onMyList: (() -> Void)?
    var onRecent: (() -> Void)?
    var onSupport: (() -> Void)?
    var onEditName: (() -> Void)?

    private let card = UIView()
    private let stack = UIStackView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        card.backgroundColor = Theme.surface
        card.layer.cornerRadius = 20
        card.layer.borderWidth = 1
        card.layer.borderColor = UIColor.white.withAlphaComponent(0.06).cgColor
        card.translatesAutoresizingMaskIntoConstraints = false
        addSubview(card)
        stack.axis = .vertical
        stack.spacing = 14
        stack.alignment = .fill
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            card.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            card.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
            card.leftAnchor.constraint(equalTo: leftAnchor, constant: 16),
            card.rightAnchor.constraint(equalTo: rightAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 18),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -18),
            stack.leftAnchor.constraint(equalTo: card.leftAnchor, constant: 16),
            stack.rightAnchor.constraint(equalTo: card.rightAnchor, constant: -16),
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// Builds the card for the current state and returns its height for `width`.
    func build(width: CGFloat) -> CGFloat {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let a = Account.shared
        if a.isLoggedIn { buildSignedIn(a) } else { buildGuest() }
        frame = CGRect(x: 0, y: 0, width: width, height: 10)
        let size = systemLayoutSizeFitting(CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
                                           withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel)
        frame.size.height = ceil(size.height)
        return frame.height
    }

    // MARK: visitor

    private func buildGuest() {
        let avatar = AccountHeaderView.avatar(image: nil, letters: nil, size: 60)
        let hello = AccountHeaderView.label("أهلاً بك في سيكرام", 21, .heavy, .white)
        let line = AccountHeaderView.label("سجّل دخولك بالبريد الإلكتروني لتفتح الدعم والتواصل وتحفظ قائمتك.", 13.5, .regular, Theme.text2)
        line.numberOfLines = 0
        let texts = UIStackView(arrangedSubviews: [hello, line])
        texts.axis = .vertical
        texts.spacing = 4
        stack.addArrangedSubview(AccountHeaderView.row(avatar, texts, spacing: 14))

        let perks = UIStackView()
        perks.axis = .vertical
        perks.spacing = 8
        for (sym, text) in [("bubble.left.and.bubble.right.fill", "محادثة خاصة مع فريق سيكرام لحل أي مشكلة"),
                            ("film.fill", "اطلب أي فيلم أو مسلسل أو قناة وتابع حالة طلبك"),
                            ("iphone", "نفس حسابك على الأندرويد والآيفون")] {
            let i = UIImageView(image: UIImage(systemName: sym))
            i.tintColor = Theme.red
            i.contentMode = .scaleAspectFit
            i.widthAnchor.constraint(equalToConstant: 20).isActive = true
            i.heightAnchor.constraint(equalToConstant: 18).isActive = true
            let l = AccountHeaderView.label(text, 13.5, .medium, Theme.text2)
            l.numberOfLines = 0
            perks.addArrangedSubview(AccountHeaderView.row(i, l, spacing: 10))
        }
        stack.addArrangedSubview(perks)

        let login = AccountHeaderView.button("تسجيل الدخول", symbol: "person.fill", filled: true)
        login.addTarget(self, action: #selector(loginTapped), for: .touchUpInside)
        let register = AccountHeaderView.button("إنشاء حساب جديد", symbol: "person.badge.plus", filled: false)
        register.addTarget(self, action: #selector(registerTapped), for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [login, register])
        buttons.axis = .vertical
        buttons.spacing = 10
        stack.addArrangedSubview(buttons)
    }

    // MARK: signed in

    private func buildSignedIn(_ a: Account) {
        let avatar = AccountHeaderView.avatar(image: a.avatarURL, letters: a.initials, size: 64)
        let name = AccountHeaderView.label(a.name.isEmpty ? "حسابي" : a.name, 20, .heavy, .white)
        name.isUserInteractionEnabled = true
        name.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(editTapped)))
        let mail = AccountHeaderView.label(a.email, 13.5, .regular, Theme.text2)
        let texts = UIStackView(arrangedSubviews: [name, mail])
        texts.axis = .vertical
        texts.spacing = 3
        if !a.publicId.isEmpty {
            let id = AccountHeaderView.label("المعرّف: " + a.publicId + "  ⧉", 12.5, .semibold, Theme.text3)
            id.isUserInteractionEnabled = true
            id.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(copyId(_:))))
            id.accessibilityHint = "اضغط لنسخ المعرّف"
            texts.addArrangedSubview(id)
        }
        stack.addArrangedSubview(AccountHeaderView.row(avatar, texts, spacing: 14))

        let unread = SupportCenter.shared.unread
        let tiles = UIStackView(arrangedSubviews: [
            tile(String(Library.shared.myListKeys.count), "قائمتي", #selector(myListTapped)),
            tile(String(Library.shared.history.count), "شاهدتها", #selector(recentTapped)),
            tile(unread > 0 ? String(unread) : "💬", unread > 0 ? "رسائل جديدة" : "الدعم", #selector(supportTapped), hot: unread > 0),
        ])
        tiles.axis = .horizontal
        tiles.spacing = 8
        tiles.distribution = .fillEqually
        stack.addArrangedSubview(tiles)
    }

    private func tile(_ value: String, _ caption: String, _ action: Selector, hot: Bool = false) -> UIView {
        let b = UIControl()
        b.backgroundColor = hot ? Theme.red.withAlphaComponent(0.18) : Theme.surfaceHi
        b.layer.cornerRadius = 12
        b.layer.borderWidth = hot ? 1 : 0
        b.layer.borderColor = Theme.red.withAlphaComponent(0.5).cgColor
        let v = AccountHeaderView.label(value, 18, .heavy, hot ? Theme.red : .white)
        v.textAlignment = .center
        let c = AccountHeaderView.label(caption, 12, .semibold, Theme.text2)
        c.textAlignment = .center
        let s = UIStackView(arrangedSubviews: [v, c])
        s.axis = .vertical
        s.spacing = 2
        s.isUserInteractionEnabled = false
        s.translatesAutoresizingMaskIntoConstraints = false
        b.addSubview(s)
        NSLayoutConstraint.activate([
            s.centerYAnchor.constraint(equalTo: b.centerYAnchor),
            s.leftAnchor.constraint(equalTo: b.leftAnchor, constant: 4),
            s.rightAnchor.constraint(equalTo: b.rightAnchor, constant: -4),
            b.heightAnchor.constraint(equalToConstant: 62),
        ])
        b.addTarget(self, action: action, for: .touchUpInside)
        b.accessibilityLabel = caption + " " + value
        b.isAccessibilityElement = true
        return b
    }

    @objc private func loginTapped() { onLogin?() }
    @objc private func registerTapped() { onRegister?() }
    @objc private func myListTapped() { onMyList?() }
    @objc private func recentTapped() { onRecent?() }
    @objc private func supportTapped() { onSupport?() }
    @objc private func editTapped() { onEditName?() }

    @objc private func copyId(_ g: UITapGestureRecognizer) {
        UIPasteboard.general.string = Account.shared.publicId
        guard let l = g.view as? UILabel else { return }
        let old = l.text
        l.text = "تم نسخ المعرّف ✓"
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { l.text = old }
    }

    // MARK: pieces

    static func label(_ text: String, _ size: CGFloat, _ weight: UIFont.Weight, _ color: UIColor) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = Theme.font(size, weight)
        l.textColor = color
        l.textAlignment = .right
        l.numberOfLines = 1
        return l
    }

    /// [lead, rest] from the right: `lead` (avatar / icon) at the right edge, `rest` fills the space to its left.
    static func row(_ lead: UIView, _ rest: UIView, spacing: CGFloat) -> UIView {
        let v = UIView()
        lead.translatesAutoresizingMaskIntoConstraints = false
        rest.translatesAutoresizingMaskIntoConstraints = false
        v.addSubview(lead)
        v.addSubview(rest)
        lead.setContentHuggingPriority(.required, for: .horizontal)
        lead.setContentCompressionResistancePriority(.required, for: .horizontal)
        NSLayoutConstraint.activate([
            lead.rightAnchor.constraint(equalTo: v.rightAnchor),
            lead.topAnchor.constraint(greaterThanOrEqualTo: v.topAnchor),
            lead.bottomAnchor.constraint(lessThanOrEqualTo: v.bottomAnchor),
            lead.centerYAnchor.constraint(equalTo: v.centerYAnchor),
            rest.rightAnchor.constraint(equalTo: lead.leftAnchor, constant: -spacing),
            rest.leftAnchor.constraint(equalTo: v.leftAnchor),
            rest.topAnchor.constraint(greaterThanOrEqualTo: v.topAnchor),
            rest.bottomAnchor.constraint(lessThanOrEqualTo: v.bottomAnchor),
            rest.centerYAnchor.constraint(equalTo: v.centerYAnchor),
        ])
        // the row is exactly as tall as its taller side (no ambiguous extra height)
        let hug = v.heightAnchor.constraint(equalToConstant: 0)
        hug.priority = UILayoutPriority(1)
        hug.isActive = true
        return v
    }

    static func avatar(image: URL?, letters: String?, size: CGFloat) -> UIView {
        let box = UIView()
        box.translatesAutoresizingMaskIntoConstraints = false
        box.widthAnchor.constraint(equalToConstant: size).isActive = true
        box.heightAnchor.constraint(equalToConstant: size).isActive = true
        box.layer.cornerRadius = size / 2
        box.clipsToBounds = true
        let ring = CAGradientLayer()
        ring.colors = [Theme.red.cgColor, UIColor(red: 0.55, green: 0.05, blue: 0.05, alpha: 1).cgColor]
        ring.frame = CGRect(x: 0, y: 0, width: size, height: size)
        box.layer.addSublayer(ring)
        let inner = UIView(frame: CGRect(x: 3, y: 3, width: size - 6, height: size - 6))
        inner.backgroundColor = Theme.surfaceHi
        inner.layer.cornerRadius = (size - 6) / 2
        inner.clipsToBounds = true
        box.addSubview(inner)
        if let l = letters, !l.isEmpty {
            let t = UILabel(frame: inner.bounds)
            t.text = l
            t.font = Theme.font(size * 0.36, .heavy)
            t.textColor = .white
            t.textAlignment = .center
            inner.addSubview(t)
        } else {
            let i = UIImageView(frame: inner.bounds.insetBy(dx: size * 0.16, dy: size * 0.16))
            i.image = UIImage(systemName: "person.fill")
            i.tintColor = Theme.text2
            i.contentMode = .scaleAspectFit
            inner.addSubview(i)
        }
        if let u = image {
            let pic = UIImageView(frame: inner.bounds)
            pic.contentMode = .scaleAspectFill
            pic.clipsToBounds = true
            inner.addSubview(pic)
            ImageLoader.shared.load(u, maxPixel: size) { img in pic.image = img }
        }
        return box
    }

    static func button(_ title: String, symbol: String, filled: Bool) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle("  " + title, for: .normal)
        b.setImage(UIImage(systemName: symbol), for: .normal)
        b.titleLabel?.font = Theme.font(16, .bold)
        b.tintColor = .white
        b.setTitleColor(.white, for: .normal)
        b.backgroundColor = filled ? Theme.red : .clear
        b.layer.cornerRadius = 13
        b.layer.borderWidth = filled ? 0 : 1.2
        b.layer.borderColor = UIColor.white.withAlphaComponent(0.35).cgColor
        b.semanticContentAttribute = .forceRightToLeft
        b.heightAnchor.constraint(equalToConstant: 50).isActive = true
        return b
    }
}

final class AccountViewController: UITableViewController {
    private enum Row { case myList, recent, downloads, support, requests, telegram, email, notifications, fonts, cache, history, terms, logout, delete }
    private var sections: [(String, [Row])] = []
    private let header = AccountHeaderView()
    private var headerWidth: CGFloat = 0
    private var notificationsOn: Bool?

    init() { super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "حسابي"
        view.backgroundColor = Theme.bg
        tableView.backgroundColor = Theme.bg
        tableView.separatorColor = UIColor.white.withAlphaComponent(0.08)
        tableView.register(AccountRowCell.self, forCellReuseIdentifier: AccountRowCell.id)
        header.onLogin = { [weak self] in self?.openLogin(register: false, then: nil) }
        header.onRegister = { [weak self] in self?.openLogin(register: true, then: nil) }
        header.onMyList = { [weak self] in self?.openMyList() }
        header.onRecent = { [weak self] in self?.openRecent() }
        header.onSupport = { [weak self] in self?.openSupport() }
        header.onEditName = { [weak self] in self?.editName() }
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(reload), name: Account.changed, object: nil)
        nc.addObserver(self, selector: #selector(reload), name: Library.changed, object: nil)
        nc.addObserver(self, selector: #selector(reload), name: SupportCenter.changed, object: nil)
        nc.addObserver(self, selector: #selector(downloadsChanged), name: DownloadManager.changed, object: nil)
        nc.addObserver(self, selector: #selector(checkNotifications), name: UIApplication.didBecomeActiveNotification, object: nil)
        reload()
        Account.shared.refresh()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        SupportCenter.shared.refreshUnread()
        checkNotifications()
    }

    /// The header is built for the table's REAL width (in 1.1.1 it was built before the page had a size: the card
    /// sat cut off at the left edge), and again whenever the width changes (rotation, iPad split view).
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = tableView.bounds.width
        if w > 0 && abs(w - headerWidth) > 0.5 { installHeader() }
    }

    private func installHeader() {
        let w = tableView.bounds.width
        guard w > 0 else { return }
        headerWidth = w
        _ = header.build(width: w)
        tableView.tableHeaderView = header
    }

    @objc private func reload() {
        if Account.shared.isLoggedIn {
            sections = [("المحتوى", [.myList, .recent, .downloads]),
                        ("الدعم والتواصل", [.support, .requests, .telegram, .email]),
                        ("الإعدادات", [.notifications, .fonts, .cache, .history]),
                        ("عن التطبيق", [.terms]),
                        ("الحساب", [.logout, .delete])]
        } else {
            // a visitor sees the welcome card, the support entry (locked: it needs an e-mail account) and the terms only
            sections = [("المحتوى", [.downloads]),
                        ("الدعم والتواصل", [.support, .requests, .telegram]),
                        ("عن التطبيق", [.terms])]
        }
        headerWidth = 0
        installHeader()
        tableView.tableFooterView = footer()
        tableView.reloadData()
    }

    /// The «التنزيلات» row shows the counts: only the table is refreshed (not the header).
    @objc private func downloadsChanged() { tableView.reloadData() }

    @objc private func checkNotifications() {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let on = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
            DispatchQueue.main.async {
                if self.notificationsOn != on { self.notificationsOn = on; if Account.shared.isLoggedIn { self.tableView.reloadData() } }
            }
        }
    }

    private func footer() -> UIView {
        let l = UILabel(frame: CGRect(x: 0, y: 0, width: view.bounds.width, height: 60))
        l.text = "سيكرام للآيفون " + AppInfo.version + " (" + AppInfo.build + ")"
        l.font = Theme.font(12)
        l.textColor = Theme.text3
        l.textAlignment = .center
        return l
    }

    // MARK: navigation

    private func openLogin(register: Bool, then: (() -> Void)?) {
        let l = LoginViewController(register: register)
        l.onSuccess = then
        navigationController?.pushViewController(l, animated: true)
    }

    private func openMyList() { navigationController?.pushViewController(GridViewController(title: "قائمتي", shows: Library.shared.myList()), animated: true) }
    private func openRecent() { navigationController?.pushViewController(GridViewController(title: "شاهدتها مؤخراً", shows: Library.shared.recent()), animated: true) }

    /// «الدعم والتواصل» needs an account signed in with an e-mail: a visitor is asked to sign in (or create one) first,
    /// and lands in the conversation right after.
    func openSupport() {
        guard Account.shared.isLoggedIn else {
            askLogin(feature: "الدعم والتواصل") { [weak self] in self?.openSupport() }
            return
        }
        navigationController?.pushViewController(SupportChatViewController(), animated: true)
    }

    private func openRequests() {
        guard Account.shared.isLoggedIn else {
            askLogin(feature: "طلب الأفلام والمسلسلات والقنوات") { [weak self] in self?.openRequests() }
            return
        }
        navigationController?.pushViewController(RequestsViewController(), animated: true)
    }

    private func askLogin(feature: String, then: @escaping () -> Void) {
        let a = UIAlertController(title: "سجّل الدخول أولاً",
                                  message: "«" + feature + "» متاح لأصحاب الحسابات فقط. سجّل الدخول ببريدك الإلكتروني، أو أنشئ حساباً جديداً خلال دقيقة.",
                                  preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "تسجيل الدخول", style: .default) { [weak self] _ in
            self?.openLogin(register: false) { DispatchQueue.main.asyncAfter(deadline: .now() + 0.55, execute: then) }
        })
        a.addAction(UIAlertAction(title: "إنشاء حساب جديد", style: .default) { [weak self] _ in
            self?.openLogin(register: true) { DispatchQueue.main.asyncAfter(deadline: .now() + 0.55, execute: then) }
        })
        a.addAction(UIAlertAction(title: "ليس الآن", style: .cancel))
        present(a, animated: true)
    }

    private func openTelegram() {
        guard let app = URL(string: "tg://resolve?domain=CigramApp"), let web = URL(string: "https://t.me/CigramApp") else { return }
        UIApplication.shared.open(app, options: [:]) { ok in if !ok { UIApplication.shared.open(web) } }
    }

    private func openEmail() {
        let a = Account.shared
        let body = "\n\n—\nسيكرام للآيفون " + AppInfo.version + " (" + AppInfo.build + ") · iOS " + UIDevice.current.systemVersion +
            (a.publicId.isEmpty ? "" : " · المعرّف " + a.publicId)
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = Account.supportEmail
        c.queryItems = [URLQueryItem(name: "subject", value: "سيكرام للآيفون — دعم"), URLQueryItem(name: "body", value: body)]
        guard let u = c.url else { return }
        UIApplication.shared.open(u, options: [:]) { [weak self] ok in
            if !ok {
                UIPasteboard.general.string = Account.supportEmail
                self?.toast("لا يوجد تطبيق بريد. نُسخ العنوان: " + Account.supportEmail)
            }
        }
    }

    private func editName() {
        let a = UIAlertController(title: "اسمك في سيكرام", message: "يظهر لفريق الدعم وفي طلباتك.", preferredStyle: .alert)
        a.addTextField { f in f.text = Account.shared.name; f.textAlignment = .right; f.placeholder = "الاسم" }
        a.addAction(UIAlertAction(title: "إلغاء", style: .cancel))
        a.addAction(UIAlertAction(title: "حفظ", style: .default) { [weak self] _ in
            let n = (a.textFields?.first?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard n.count >= 2 else { self?.toast("اكتب اسماً من حرفين على الأقل"); return }
            Account.shared.updateName(n) { ok, msg in self?.toast(ok ? "تم حفظ الاسم" : msg) }
        })
        present(a, animated: true)
    }

    // MARK: table

    override func numberOfSections(in tableView: UITableView) -> Int { return sections.count }
    override func tableView(_ t: UITableView, titleForHeaderInSection s: Int) -> String? { return sections[s].0 }
    override func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { return sections[s].1.count }
    override func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat { return 62 }

    override func tableView(_ t: UITableView, willDisplayHeaderView view: UIView, forSection section: Int) {
        guard let h = view as? UITableViewHeaderFooterView else { return }
        h.textLabel?.textAlignment = .right
        h.textLabel?.textColor = Theme.text3
        h.textLabel?.font = Theme.font(13, .bold)
    }

    override func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let c = t.dequeueReusableCell(withIdentifier: AccountRowCell.id, for: ip) as! AccountRowCell
        let logged = Account.shared.isLoggedIn
        let blue = UIColor(red: 0.16, green: 0.47, blue: 0.96, alpha: 1)
        let green = UIColor(red: 0.13, green: 0.66, blue: 0.42, alpha: 1)
        let purple = UIColor(red: 0.47, green: 0.32, blue: 0.86, alpha: 1)
        let orange = UIColor(red: 0.95, green: 0.55, blue: 0.13, alpha: 1)
        let grey = UIColor(white: 0.32, alpha: 1)
        c.showsChevron = true
        switch sections[ip.section].1[ip.row] {
        case .myList:
            c.set("قائمتي", sub: String(Library.shared.myListKeys.count) + " عنوان محفوظ", symbol: "bookmark.fill", tint: Theme.red)
        case .recent:
            c.set("شاهدتها مؤخراً", sub: String(Library.shared.history.count) + " عنوان", symbol: "clock.arrow.circlepath", tint: blue)
        case .downloads:
            let done = DownloadManager.shared.completedCount
            let busy = DownloadManager.shared.records.filter { $0.state != .completed && $0.state != .failed }.count
            var sub = "مشاهدة بدون إنترنت"
            if done > 0 || busy > 0 { sub = String(done) + " مكتمل" + (busy > 0 ? " · " + String(busy) + " قيد التنزيل" : "") }
            c.set("التنزيلات", sub: sub, symbol: "arrow.down.circle.fill", tint: green)
        case .support:
            let unread = SupportCenter.shared.unread
            c.set("الدعم والتواصل", sub: logged ? (unread > 0 ? "رد جديد من فريق سيكرام" : "المشاكل · الاقتراحات · التواصل مع المطوّر")
                                               : "سجّل الدخول بالبريد الإلكتروني لفتح المحادثة",
                  symbol: "bubble.left.and.bubble.right.fill", tint: green, badge: logged ? unread : 0, locked: !logged)
        case .requests:
            c.set("اطلب فيلماً أو مسلسلاً أو قناة", sub: logged ? "طلباتي وحالتها" : "يتطلب تسجيل الدخول",
                  symbol: "film.fill", tint: purple, locked: !logged)
        case .telegram:
            c.set("تابعنا على Telegram", sub: "قناة سيكرام الرسمية · @CigramApp", symbol: "paperplane.fill",
                  tint: UIColor(red: 0.13, green: 0.6, blue: 0.87, alpha: 1))
        case .email:
            c.set("راسلنا بالبريد", sub: Account.supportEmail, symbol: "envelope.fill", tint: orange)
        case .notifications:
            let s = notificationsOn.map { $0 ? "مفعّلة · الجديد ورسائل الدعم" : "متوقفة — اضغط لتفعيلها" } ?? ""
            c.set("الإشعارات", sub: s, symbol: "bell.badge.fill", tint: Theme.red)
        case .fonts:
            c.set("الخطوط", sub: "خط الترجمة · المعاينة · لون الخلفية · لون النص", symbol: "textformat", tint: purple)
        case .cache:
            c.set("مسح ذاكرة الصور", sub: "يحرّر المساحة؛ الصور تُحمّل من جديد", symbol: "photo.on.rectangle", tint: grey)
            c.showsChevron = false
        case .history:
            c.set("مسح سجل المشاهدة", sub: "«أكمل المشاهدة» و«شاهدتها مؤخراً»", symbol: "clock.badge.xmark", tint: grey)
            c.showsChevron = false
        case .terms:
            c.set("الشروط والأحكام", sub: "شروط استخدام سيكرام والخصوصية", symbol: "doc.text.fill", tint: grey)
        case .logout:
            c.set("تسجيل الخروج", sub: "تبقى بيانات حسابك محفوظة", symbol: "rectangle.portrait.and.arrow.right", tint: grey)
            c.showsChevron = false
        case .delete:
            c.set("حذف الحساب نهائياً", sub: "يحذف حسابك ومحادثاتك من خوادم سيكرام", symbol: "person.crop.circle.badge.xmark",
                  tint: Theme.red.withAlphaComponent(0.25), destructive: true)
            c.showsChevron = false
        }
        return c
    }

    override func tableView(_ t: UITableView, didSelectRowAt ip: IndexPath) {
        t.deselectRow(at: ip, animated: true)
        switch sections[ip.section].1[ip.row] {
        case .myList: openMyList()
        case .recent: openRecent()
        case .downloads: navigationController?.pushViewController(DownloadsViewController(), animated: true)
        case .support: openSupport()
        case .requests: openRequests()
        case .telegram: openTelegram()
        case .email: openEmail()
        case .notifications:
            UNUserNotificationCenter.current().getNotificationSettings { s in
                DispatchQueue.main.async {
                    if s.authorizationStatus == .notDetermined {
                        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
                            DispatchQueue.main.async { self.checkNotifications() }
                        }
                    } else if let u = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(u)
                    }
                }
            }
        case .fonts: navigationController?.pushViewController(FontsViewController(), animated: true)
        case .cache:
            URLCache.shared.removeAllCachedResponses()
            ImageLoader.shared.clear()
            toast("تم مسح ذاكرة الصور")
        case .history:
            confirm("مسح سجل المشاهدة", "تُحذف «أكمل المشاهدة» و«شاهدتها مؤخراً» من هذا الآيفون.", "مسح") {
                Library.shared.clearHistory()
            }
        case .terms: navigationController?.pushViewController(TermsViewController(), animated: true)
        case .logout:
            confirm("تسجيل الخروج", "تخرج من حسابك على هذا الآيفون.", "خروج") {
                SupportCenter.shared.signedOut()
                Account.shared.logout()
            }
        case .delete: askDelete()
        }
    }

    private func askDelete() {
        let a = UIAlertController(title: "حذف الحساب نهائياً",
                                  message: "يُحذف حسابك وكل بياناته من خوادم سيكرام ولا يمكن التراجع. اكتب كلمة المرور للتأكيد.",
                                  preferredStyle: .alert)
        a.addTextField { $0.isSecureTextEntry = true; $0.placeholder = "كلمة المرور"; $0.textAlignment = .right }
        a.addAction(UIAlertAction(title: "إلغاء", style: .cancel))
        a.addAction(UIAlertAction(title: "احذف", style: .destructive) { [weak self] _ in
            Account.shared.deleteAccount(password: a.textFields?.first?.text ?? "") { ok, msg in
                if ok { SupportCenter.shared.signedOut() }
                self?.toast(msg)
            }
        })
        present(a, animated: true)
    }

    private func confirm(_ title: String, _ msg: String, _ yes: String, _ go: @escaping () -> Void) {
        let a = UIAlertController(title: title, message: msg, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "إلغاء", style: .cancel))
        a.addAction(UIAlertAction(title: yes, style: .destructive) { _ in go() })
        present(a, animated: true)
    }

    func toast(_ text: String) {
        guard presentedViewController == nil else { return }
        let a = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        present(a, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { a.dismiss(animated: true) }
    }
}

// MARK: - تسجيل الدخول / إنشاء حساب

final class LoginViewController: UIViewController, UITextFieldDelegate {
    private let mode = UISegmentedControl(items: ["إنشاء حساب", "تسجيل الدخول"])
    private let nameField = LoginViewController.field("الاسم", secure: false)
    private let emailField = LoginViewController.field("البريد الإلكتروني", secure: false)
    private let passField = LoginViewController.field("كلمة المرور", secure: true)
    private let confirmField = LoginViewController.field("تأكيد كلمة المرور", secure: true)
    private let terms = UIButton(type: .system)
    private let readTerms = UIButton(type: .system)
    private let forgot = UIButton(type: .system)
    private var accepted = false
    private let submit = UIButton(type: .system)
    private let error = UILabel()
    private let lead = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private var stack: UIStackView!
    private let scroll = UIScrollView()
    private let startRegistering: Bool
    var onSuccess: (() -> Void)?

    init(register: Bool = false) {
        startRegistering = register
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    deinit { NotificationCenter.default.removeObserver(self) }

    static func field(_ placeholder: String, secure: Bool) -> UITextField {
        let f = UITextField()
        f.attributedPlaceholder = NSAttributedString(string: placeholder, attributes: [.foregroundColor: Theme.text3])
        f.textColor = .white
        f.font = Theme.font(16)
        f.textAlignment = .right
        f.backgroundColor = Theme.surface
        f.layer.cornerRadius = 12
        f.layer.borderWidth = 1
        f.layer.borderColor = UIColor.white.withAlphaComponent(0.06).cgColor
        f.isSecureTextEntry = secure
        f.autocapitalizationType = .none
        f.autocorrectionType = .no
        f.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 14, height: 10))
        f.leftViewMode = .always
        f.rightView = UIView(frame: CGRect(x: 0, y: 0, width: 14, height: 10))
        f.rightViewMode = .always
        f.heightAnchor.constraint(equalToConstant: 52).isActive = true
        return f
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = startRegistering ? "إنشاء حساب" : "تسجيل الدخول"
        view.backgroundColor = Theme.bg
        nameField.textContentType = .name
        emailField.keyboardType = .emailAddress
        emailField.textContentType = .username
        passField.textContentType = .password
        confirmField.textContentType = .password
        [nameField, emailField, passField, confirmField].forEach { $0.delegate = self }
        nameField.returnKeyType = .next
        emailField.returnKeyType = .next
        passField.returnKeyType = .go
        confirmField.returnKeyType = .go
        addEye(passField)
        addEye(confirmField)
        mode.selectedSegmentIndex = startRegistering ? 0 : 1
        mode.selectedSegmentTintColor = Theme.red
        mode.setTitleTextAttributes([.foregroundColor: UIColor.white, .font: Theme.font(14, .bold)], for: .normal)
        mode.addTarget(self, action: #selector(modeChanged), for: .valueChanged)
        mode.heightAnchor.constraint(equalToConstant: 38).isActive = true
        lead.font = Theme.font(14)
        lead.textColor = Theme.text2
        lead.numberOfLines = 0
        lead.textAlignment = .center
        terms.setTitle("☐  أوافق على الشروط والأحكام", for: .normal)
        terms.setTitleColor(Theme.text2, for: .normal)
        terms.titleLabel?.font = Theme.font(14)
        terms.contentHorizontalAlignment = .right
        terms.addTarget(self, action: #selector(toggleTerms), for: .touchUpInside)
        readTerms.setTitle("قراءة الشروط والأحكام", for: .normal)
        readTerms.setTitleColor(Theme.red, for: .normal)
        readTerms.titleLabel?.font = Theme.font(13.5, .semibold)
        readTerms.contentHorizontalAlignment = .right
        readTerms.addTarget(self, action: #selector(openTerms), for: .touchUpInside)
        forgot.setTitle("نسيت كلمة المرور؟", for: .normal)
        forgot.setTitleColor(Theme.text2, for: .normal)
        forgot.titleLabel?.font = Theme.font(13.5, .semibold)
        forgot.contentHorizontalAlignment = .right
        forgot.addTarget(self, action: #selector(forgotTapped), for: .touchUpInside)
        submit.backgroundColor = Theme.red
        submit.setTitleColor(.white, for: .normal)
        submit.titleLabel?.font = Theme.font(17, .bold)
        submit.layer.cornerRadius = 14
        submit.heightAnchor.constraint(equalToConstant: 54).isActive = true
        submit.addTarget(self, action: #selector(go), for: .touchUpInside)
        error.textColor = Theme.red
        error.font = Theme.font(13.5)
        error.numberOfLines = 0
        error.textAlignment = .right
        spinner.color = .white
        let logo = UILabel()
        logo.text = "Cigram"
        logo.font = Theme.font(34, .black)
        logo.textColor = Theme.red
        logo.textAlignment = .center
        stack = UIStackView(arrangedSubviews: [logo, lead, mode, nameField, emailField, passField, confirmField, terms, readTerms,
                                               forgot, error, submit, spinner])
        stack.axis = .vertical
        stack.spacing = 12
        stack.setCustomSpacing(6, after: logo)
        stack.setCustomSpacing(18, after: lead)
        stack.setCustomSpacing(18, after: mode)
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.keyboardDismissMode = .interactive
        scroll.alwaysBounceVertical = true
        view.addSubview(scroll)
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -20),
        ])
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(keyboard(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        nc.addObserver(self, selector: #selector(keyboard(_:)), name: UIResponder.keyboardWillHideNotification, object: nil)
        modeChanged()
    }

    private func addEye(_ f: UITextField) {
        let b = UIButton(type: .system)
        b.setImage(UIImage(systemName: "eye.slash"), for: .normal)
        b.tintColor = Theme.text3
        b.frame = CGRect(x: 0, y: 0, width: 44, height: 44)
        b.accessibilityLabel = "إظهار كلمة المرور"
        b.attachSecureToggle(to: f)
        f.leftView = b
        f.leftViewMode = .always
    }

    @objc private func keyboard(_ n: Notification) {
        guard let end = (n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
        let local = view.convert(end, from: nil)
        let overlap = n.name == UIResponder.keyboardWillHideNotification ? 0 : max(0, view.bounds.maxY - local.minY - view.safeAreaInsets.bottom)
        scroll.contentInset.bottom = overlap + 12
        scroll.verticalScrollIndicatorInsets.bottom = overlap
    }

    private var registering: Bool { return mode.selectedSegmentIndex == 0 }

    @objc private func modeChanged() {
        nameField.isHidden = !registering
        confirmField.isHidden = !registering
        terms.isHidden = !registering
        readTerms.isHidden = !registering
        forgot.isHidden = registering
        title = registering ? "إنشاء حساب" : "تسجيل الدخول"
        lead.text = registering ? "حساب واحد بالبريد الإلكتروني لكل أجهزتك: قائمتك، طلباتك، ومحادثتك مع فريق الدعم."
                                : "سجّل الدخول بنفس بريدك وكلمة مرورك على تطبيق الأندرويد."
        submit.setTitle(registering ? "إنشاء الحساب" : "تسجيل الدخول", for: .normal)
        passField.textContentType = registering ? .newPassword : .password
        error.text = ""
    }

    func textFieldShouldReturn(_ f: UITextField) -> Bool {
        if f === nameField { emailField.becomeFirstResponder() }
        else if f === emailField { passField.becomeFirstResponder() }
        else if f === passField && registering { confirmField.becomeFirstResponder() }
        else { go() }
        return true
    }

    @objc private func toggleTerms() {
        accepted.toggle()
        terms.setTitle((accepted ? "☑︎" : "☐") + "  أوافق على الشروط والأحكام", for: .normal)
        terms.setTitleColor(accepted ? .white : Theme.text2, for: .normal)
    }

    @objc private func openTerms() { navigationController?.pushViewController(TermsViewController(), animated: true) }

    @objc private func forgotTapped() {
        let a = UIAlertController(title: "نسيت كلمة المرور؟",
                                  message: "راسلنا من نفس بريدك المسجّل وسنساعدك على استعادة حسابك.",
                                  preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "إلغاء", style: .cancel))
        a.addAction(UIAlertAction(title: "مراسلة الدعم", style: .default) { _ in
            var c = URLComponents()
            c.scheme = "mailto"
            c.path = Account.supportEmail
            c.queryItems = [URLQueryItem(name: "subject", value: "استعادة حساب سيكرام")]
            if let u = c.url { UIApplication.shared.open(u) }
        })
        present(a, animated: true)
    }

    @objc private func go() {
        view.endEditing(true)
        let email = (emailField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let pass = passField.text ?? ""
        if !LoginViewController.validEmail(email) { error.text = "البريد الإلكتروني غير صحيح"; return }
        if pass.count < (registering ? 8 : 1) { error.text = registering ? "كلمة المرور يجب ألا تقل عن 8 أحرف" : "اكتب كلمة المرور"; return }
        let done: Account.Done = { [weak self] ok, msg in
            guard let self = self else { return }
            self.spinner.stopAnimating()
            self.submit.isEnabled = true
            self.submit.alpha = 1
            if ok {
                let next = self.onSuccess
                self.navigationController?.popViewController(animated: true)
                SupportCenter.shared.refreshUnread()
                next?()
            } else {
                self.error.text = msg
            }
        }
        if registering {
            let name = (nameField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if name.count < 2 { error.text = "اكتب اسمك (حرفين على الأقل)"; return }
            if pass != confirmField.text { error.text = "كلمتا المرور غير متطابقتين"; return }
            if !accepted { error.text = "يجب الموافقة على الشروط والأحكام قبل إنشاء الحساب"; return }
            start()
            Account.shared.register(name: name, email: email, password: pass, done)
        } else {
            start()
            Account.shared.login(email: email, password: pass, done)
        }
    }

    static func validEmail(_ s: String) -> Bool {
        let parts = s.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, parts[1].contains("."), !parts[1].hasPrefix("."), !parts[1].hasSuffix("."),
              !s.contains(" ") else { return false }
        return true
    }

    private func start() {
        error.text = ""
        submit.isEnabled = false
        submit.alpha = 0.6
        spinner.startAnimating()
    }
}

private extension UIButton {
    /// The eye in a password field: shows / hides the password.
    func attachSecureToggle(to field: UITextField) {
        objc_setAssociatedObject(self, &UIButton.fieldKey, field, .OBJC_ASSOCIATION_ASSIGN)
        addTarget(self, action: #selector(toggleSecure), for: .touchUpInside)
    }
    private static var fieldKey: UInt8 = 0
    @objc func toggleSecure() {
        guard let f = objc_getAssociatedObject(self, &UIButton.fieldKey) as? UITextField else { return }
        f.isSecureTextEntry.toggle()
        setImage(UIImage(systemName: f.isSecureTextEntry ? "eye.slash" : "eye"), for: .normal)
        // re-assigning the text keeps the caret at the end after the switch
        let t = f.text
        f.text = nil
        f.text = t
    }
}

// MARK: - الشروط والأحكام (same text as the Android app)

final class TermsViewController: UIViewController {
    static let sections: [(String, String)] = [
        ("قبول الشروط", "باستخدامك تطبيق Cigram أو إنشاء حساب فيه فإنك توافق على هذه الشروط. إن لم توافق عليها فلا تُنشئ حساباً ولا تستخدم الخدمة."),
        ("الحساب", "أنت مسؤول عن صحة بيانات حسابك وعن سرية كلمة المرور وعن كل ما يجري من خلال حسابك. أخبرنا فوراً إن اشتبهت في استخدام حسابك دون إذنك. يمكنك حذف حسابك من الإعدادات."),
        ("المحتوى والاستخدام", "المحتوى المعروض في Cigram للمشاهدة الشخصية غير التجارية. لا يجوز نسخه أو إعادة بثه أو بيعه، ولا محاولة تعطيل الخدمة أو الوصول إلى أجزائها بطرق غير مسموح بها."),
        ("المحادثات والملفات", "الرسائل والصور والفيديوهات والرسائل الصوتية التي ترسلها إلى فريق Cigram تُحفظ على خوادمنا للرد عليك ومتابعة طلبك. لا ترسل محتوى مخالفاً أو لا تملك حق نشره. الحد الأقصى لحجم الفيديو المرفوع 1 غيغابايت."),
        ("الخصوصية", "نجمع ما يلزم لتشغيل الخدمة فقط: بيانات الحساب، وسجل المشاهدة لاستكمال العرض، وإعدادات الإشعارات، وبيانات تقنية عن الأعطال والأداء. لا نبيع بياناتك الشخصية."),
        ("الأذونات", "يطلب التطبيق الإذن فقط عند الحاجة إليه: الإشعارات لتنبيهك بالجديد. يمكنك تغيير ذلك من إعدادات جهازك في أي وقت."),
        ("توفر الخدمة", "نعمل على أن تكون الخدمة متاحة ومستقرة، لكن قد يتوقف بعض المحتوى أو القنوات مؤقتاً أو يُزال لأسباب تقنية أو بطلب من أصحابه."),
        ("تعديل الشروط", "قد نحدّث هذه الشروط. عند تغيير جوهري سيُطلب منك الاطلاع على النسخة الجديدة، ويظهر رقم الإصدار أسفل هذه الصفحة."),
        ("التواصل", "لأي سؤال عن هذه الشروط راسلنا على البريد " + Account.supportEmail + "."),
    ]

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "الشروط والأحكام"
        view.backgroundColor = Theme.bg
        let text = UITextView()
        text.backgroundColor = Theme.bg
        text.isEditable = false
        text.textContainerInset = UIEdgeInsets(top: 16, left: 16, bottom: 30, right: 16)
        let p = NSMutableParagraphStyle()
        p.alignment = .right
        p.lineSpacing = 4
        p.paragraphSpacing = 10
        let out = NSMutableAttributedString(string: "يرجى قراءة هذه الشروط بعناية قبل إنشاء حسابك واستخدام Cigram.\n\n",
                                            attributes: [.font: Theme.font(15), .foregroundColor: Theme.text2, .paragraphStyle: p])
        for (i, s) in TermsViewController.sections.enumerated() {
            out.append(NSAttributedString(string: String(i + 1) + ". " + s.0 + "\n",
                                          attributes: [.font: Theme.font(17, .bold), .foregroundColor: UIColor.white, .paragraphStyle: p]))
            out.append(NSAttributedString(string: s.1 + "\n\n",
                                          attributes: [.font: Theme.font(15), .foregroundColor: Theme.text2, .paragraphStyle: p]))
        }
        out.append(NSAttributedString(string: "الإصدار 1 · نوفمبر 2026", attributes: [.font: Theme.font(12), .foregroundColor: Theme.text3, .paragraphStyle: p]))
        text.attributedText = out
        text.frame = view.bounds
        text.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(text)
    }
}
