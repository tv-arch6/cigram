import UIKit

/// The Cigram account — the same server and the same rules as the Android app (cigram-auth-api).
final class Account {
    static let shared = Account()
    static let changed = Notification.Name("cigram.account.changed")
    static let base = "https://cigram-auth-api.wwq-mixtv.workers.dev"
    static let supportEmail = "app.cigram@gmail.com"

    private let defaults = UserDefaults.standard
    private let userKey = "cg.account.user"

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

    typealias Done = (_ ok: Bool, _ message: String) -> Void

    func login(email: String, password: String, _ done: @escaping Done) {
        call("POST", "/login", body: ["email": email, "password": password]) { status, json in
            self.adopt(status, json, done)
        }
    }

    func register(name: String, email: String, password: String, _ done: @escaping Done) {
        let body: [String: Any] = ["name": name, "email": email, "password": password, "gender": "unspecified",
                                   "accept_terms": "true", "terms_version": "1",
                                   "terms_accepted_at": String(Int(Date().timeIntervalSince1970 * 1000))]
        call("POST", "/register", body: body) { status, json in self.adopt(status, json, done) }
    }

    private func adopt(_ status: Int, _ json: [String: Any]?, _ done: @escaping Done) {
        let ok = (json?["success"] as? Bool) ?? (status == 200)
        guard ok, let token = json?["token"] as? String, !token.isEmpty, let user = json?["user"] as? [String: Any] else {
            done(false, (json?["message"] as? String) ?? (status == 0 ? "تعذّر الاتصال. تحقق من الإنترنت." : "تعذّر تسجيل الدخول."))
            return
        }
        Keychain.set(token, for: "token")
        defaults.set(user.filter { !($0.value is NSNull) }, forKey: userKey)
        NotificationCenter.default.post(name: Account.changed, object: nil)
        done(true, "")
    }

    /// Refreshes the profile; a 401 means the session ended on the server (signed out everywhere).
    func refresh() {
        guard isLoggedIn else { return }
        call("GET", "/me", body: nil) { status, json in
            if status == 401 { self.clear(); return }
            if let u = json?["user"] as? [String: Any] {
                self.defaults.set(u.filter { !($0.value is NSNull) }, forKey: self.userKey)
                NotificationCenter.default.post(name: Account.changed, object: nil)
            }
        }
    }

    func logout() {
        if isLoggedIn { call("POST", "/logout", body: [:]) { _, _ in } }
        clear()
    }

    func deleteAccount(password: String, _ done: @escaping Done) {
        call("POST", "/account/delete", body: ["password": password, "confirm": "DELETE"]) { status, json in
            if (json?["success"] as? Bool) == true {
                self.clear()
                done(true, (json?["message"] as? String) ?? "تم حذف الحساب")
            } else {
                done(false, (json?["message"] as? String) ?? "تعذّر حذف الحساب")
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
        defaults.removeObject(forKey: userKey)
        DispatchQueue.main.async { NotificationCenter.default.post(name: Account.changed, object: nil) }
    }

    private func call(_ method: String, _ path: String, body: [String: Any]?, _ done: @escaping (Int, [String: Any]?) -> Void) {
        guard let url = URL(string: Account.base + path) else { return }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
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

// MARK: - حسابي

final class AccountViewController: UITableViewController {
    private enum Row { case myList, recent, notifications, cache, contact, terms, logout, delete }
    private var sections: [(String, [Row])] = []

    init() { super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "حسابي"
        view.backgroundColor = Theme.bg
        tableView.backgroundColor = Theme.bg
        tableView.separatorColor = UIColor.white.withAlphaComponent(0.08)
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "row")
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: Account.changed, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: Library.changed, object: nil)
        reload()
        Account.shared.refresh()
    }

    @objc private func reload() {
        var s: [(String, [Row])] = [("المحتوى", [.myList, .recent]),
                                    ("التطبيق", [.notifications, .cache]),
                                    ("الدعم", [.contact, .terms])]
        if Account.shared.isLoggedIn { s.append(("الحساب", [.logout, .delete])) }
        sections = s
        tableView.tableHeaderView = header()
        tableView.tableFooterView = footer()
        tableView.reloadData()
    }

    private func header() -> UIView {
        let w = view.bounds.width
        let box = UIView(frame: CGRect(x: 0, y: 0, width: w, height: 190))
        let card = UIView(frame: CGRect(x: 16, y: 12, width: w - 32, height: 166))
        card.backgroundColor = Theme.surface
        card.layer.cornerRadius = 18
        box.addSubview(card)
        let avatar = UIImageView(frame: CGRect(x: card.bounds.width - 84, y: 18, width: 68, height: 68))
        avatar.layer.cornerRadius = 34
        avatar.clipsToBounds = true
        avatar.backgroundColor = Theme.surfaceHi
        avatar.contentMode = .scaleAspectFill
        avatar.image = UIImage(systemName: "person.crop.circle.fill")
        avatar.tintColor = Theme.text3
        card.addSubview(avatar)
        let name = UILabel(frame: CGRect(x: 16, y: 22, width: card.bounds.width - 112, height: 26))
        name.font = Theme.font(19, .heavy)
        name.textColor = .white
        name.textAlignment = .right
        card.addSubview(name)
        let sub = UILabel(frame: CGRect(x: 16, y: 50, width: card.bounds.width - 112, height: 36))
        sub.font = Theme.font(13)
        sub.textColor = Theme.text2
        sub.textAlignment = .right
        sub.numberOfLines = 2
        card.addSubview(sub)
        let button = UIButton(type: .system)
        button.frame = CGRect(x: 16, y: 106, width: card.bounds.width - 32, height: 44)
        button.layer.cornerRadius = 12
        button.titleLabel?.font = Theme.font(15.5, .bold)
        card.addSubview(button)
        let a = Account.shared
        if a.isLoggedIn {
            name.text = a.name.isEmpty ? "حسابي" : a.name
            sub.text = a.email + (a.publicId.isEmpty ? "" : "\nالمعرّف: " + a.publicId)
            button.setTitle("قائمتي (" + String(Library.shared.myListKeys.count) + ")", for: .normal)
            button.setTitleColor(.white, for: .normal)
            button.backgroundColor = Theme.surfaceHi
            button.addTarget(self, action: #selector(openMyList), for: .touchUpInside)
            if let u = a.avatarURL { ImageLoader.shared.load(u) { img in if let img = img { avatar.image = img } } }
        } else {
            name.text = "أهلاً بك في سيكرام"
            sub.text = "سجّل دخولك بنفس حسابك على الأندرويد"
            button.setTitle("تسجيل الدخول / إنشاء حساب", for: .normal)
            button.setTitleColor(.white, for: .normal)
            button.backgroundColor = Theme.red
            button.addTarget(self, action: #selector(openLogin), for: .touchUpInside)
        }
        return box
    }

    private func footer() -> UIView {
        let l = UILabel(frame: CGRect(x: 0, y: 0, width: view.bounds.width, height: 54))
        let v = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? ""
        l.text = "سيكرام للآيفون " + v
        l.font = Theme.font(12)
        l.textColor = Theme.text3
        l.textAlignment = .center
        return l
    }

    @objc private func openLogin() { navigationController?.pushViewController(LoginViewController(), animated: true) }
    @objc private func openMyList() { navigationController?.pushViewController(GridViewController(title: "قائمتي", shows: Library.shared.myList()), animated: true) }

    override func numberOfSections(in tableView: UITableView) -> Int { return sections.count }
    override func tableView(_ t: UITableView, titleForHeaderInSection s: Int) -> String? { return sections[s].0 }
    override func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { return sections[s].1.count }

    override func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let c = t.dequeueReusableCell(withIdentifier: "row", for: ip)
        c.backgroundColor = Theme.surface
        c.textLabel?.textColor = .white
        c.textLabel?.font = Theme.font(15.5, .semibold)
        c.textLabel?.textAlignment = .right
        c.imageView?.tintColor = Theme.text2
        c.accessoryType = .disclosureIndicator
        let row = sections[ip.section].1[ip.row]
        switch row {
        case .myList: set(c, "قائمتي", "bookmark.fill", count: Library.shared.myListKeys.count)
        case .recent: set(c, "شاهدتها مؤخراً", "clock.arrow.circlepath", count: Library.shared.history.count)
        case .notifications: set(c, "الإشعارات", "bell.badge.fill", count: nil)
        case .cache: set(c, "مسح ذاكرة الصور", "trash", count: nil); c.accessoryType = .none
        case .contact: set(c, "تواصل معنا", "envelope.fill", count: nil)
        case .terms: set(c, "الشروط والأحكام", "doc.text.fill", count: nil)
        case .logout: set(c, "تسجيل الخروج", "rectangle.portrait.and.arrow.right", count: nil); c.accessoryType = .none
        case .delete:
            set(c, "حذف الحساب نهائياً", "person.crop.circle.badge.xmark", count: nil)
            c.textLabel?.textColor = Theme.red
            c.imageView?.tintColor = Theme.red
            c.accessoryType = .none
        }
        return c
    }

    private func set(_ c: UITableViewCell, _ text: String, _ symbol: String, count: Int?) {
        c.textLabel?.text = count.map { text + "  (" + String($0) + ")" } ?? text
        c.imageView?.image = UIImage(systemName: symbol)
    }

    override func tableView(_ t: UITableView, didSelectRowAt ip: IndexPath) {
        t.deselectRow(at: ip, animated: true)
        switch sections[ip.section].1[ip.row] {
        case .myList: openMyList()
        case .recent:
            navigationController?.pushViewController(GridViewController(title: "شاهدتها مؤخراً", shows: Library.shared.recent()), animated: true)
        case .notifications:
            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
        case .cache:
            URLCache.shared.removeAllCachedResponses()
            ImageLoader.shared.clear()
            toast("تم مسح ذاكرة الصور")
        case .contact:
            if let u = URL(string: "mailto:" + Account.supportEmail) { UIApplication.shared.open(u) }
        case .terms: navigationController?.pushViewController(TermsViewController(), animated: true)
        case .logout:
            confirm("تسجيل الخروج", "تخرج من حسابك على هذا الآيفون.", "خروج") { Account.shared.logout() }
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
            Account.shared.deleteAccount(password: a.textFields?.first?.text ?? "") { _, msg in self?.toast(msg) }
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
        let a = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        present(a, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) { a.dismiss(animated: true) }
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
    private var accepted = false
    private let submit = UIButton(type: .system)
    private let error = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private var stack: UIStackView!

    static func field(_ placeholder: String, secure: Bool) -> UITextField {
        let f = UITextField()
        f.attributedPlaceholder = NSAttributedString(string: placeholder, attributes: [.foregroundColor: Theme.text3])
        f.textColor = .white
        f.font = Theme.font(16)
        f.textAlignment = .right
        f.backgroundColor = Theme.surface
        f.layer.cornerRadius = 12
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
        title = "حسابي"
        view.backgroundColor = Theme.bg
        emailField.keyboardType = .emailAddress
        emailField.textContentType = .username
        passField.textContentType = .password
        mode.selectedSegmentIndex = 1
        mode.selectedSegmentTintColor = Theme.red
        mode.setTitleTextAttributes([.foregroundColor: UIColor.white, .font: Theme.font(14, .bold)], for: .normal)
        mode.addTarget(self, action: #selector(modeChanged), for: .valueChanged)
        terms.setTitle("☐  أوافق على الشروط والأحكام", for: .normal)
        terms.setTitleColor(Theme.text2, for: .normal)
        terms.titleLabel?.font = Theme.font(14)
        terms.contentHorizontalAlignment = .right
        terms.addTarget(self, action: #selector(toggleTerms), for: .touchUpInside)
        let read = UIButton(type: .system)
        read.setTitle("قراءة الشروط والأحكام", for: .normal)
        read.setTitleColor(Theme.red, for: .normal)
        read.titleLabel?.font = Theme.font(13.5, .semibold)
        read.contentHorizontalAlignment = .right
        read.addTarget(self, action: #selector(openTerms), for: .touchUpInside)
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
        let logo = UILabel()
        logo.text = "Cigram"
        logo.font = Theme.font(34, .black)
        logo.textColor = Theme.red
        logo.textAlignment = .center
        stack = UIStackView(arrangedSubviews: [logo, mode, nameField, emailField, passField, confirmField, terms, read, error, submit, spinner])
        stack.axis = .vertical
        stack.spacing = 12
        stack.setCustomSpacing(22, after: logo)
        stack.setCustomSpacing(18, after: mode)
        stack.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.keyboardDismissMode = .interactive
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
        modeChanged()
    }

    private var registering: Bool { return mode.selectedSegmentIndex == 0 }

    @objc private func modeChanged() {
        nameField.isHidden = !registering
        confirmField.isHidden = !registering
        terms.isHidden = !registering
        stack.arrangedSubviews[7].isHidden = !registering
        submit.setTitle(registering ? "إنشاء الحساب" : "تسجيل الدخول", for: .normal)
        error.text = ""
    }

    @objc private func toggleTerms() {
        accepted.toggle()
        terms.setTitle((accepted ? "☑︎" : "☐") + "  أوافق على الشروط والأحكام", for: .normal)
        terms.setTitleColor(accepted ? .white : Theme.text2, for: .normal)
    }

    @objc private func openTerms() { navigationController?.pushViewController(TermsViewController(), animated: true) }

    @objc private func go() {
        view.endEditing(true)
        let email = (emailField.text ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        let pass = passField.text ?? ""
        if !email.contains("@") || !email.contains(".") { error.text = "البريد الإلكتروني غير صحيح"; return }
        if pass.count < (registering ? 8 : 1) { error.text = registering ? "كلمة المرور يجب ألا تقل عن 8 أحرف" : "اكتب كلمة المرور"; return }
        let done: Account.Done = { [weak self] ok, msg in
            guard let self = self else { return }
            self.spinner.stopAnimating()
            self.submit.isEnabled = true
            if ok { self.navigationController?.popViewController(animated: true) } else { self.error.text = msg }
        }
        if registering {
            let name = (nameField.text ?? "").trimmingCharacters(in: .whitespaces)
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

    private func start() {
        error.text = ""
        submit.isEnabled = false
        spinner.startAnimating()
    }
}

// MARK: - الشروط والأحكام (same text as the Android app)

final class TermsViewController: UIViewController {
    static let sections: [(String, String)] = [
        ("قبول الشروط", "باستخدامك تطبيق Cigram أو إنشاء حساب فيه فإنك توافق على هذه الشروط. إن لم توافق عليها فلا تُنشئ حساباً ولا تستخدم الخدمة."),
        ("الحساب", "أنت مسؤول عن صحة بيانات حسابك وعن سرية كلمة المرور وعن كل ما يجري من خلال حسابك. أخبرنا فوراً إن اشتبهت في استخدام حسابك دون إذنك. يمكنك حذف حسابك من الإعدادات."),
        ("المحتوى والاستخدام", "المحتوى المعروض في Cigram للمشاهدة الشخصية غير التجارية. لا يجوز نسخه أو إعادة بثه أو بيعه، ولا محاولة تعطيل الخدمة أو الوصول إلى أجزائها بطرق غير مسموح بها."),
        ("Cigram Plus", "يُفعَّل اشتراك Cigram Plus بكود تفعيل لمدة محددة تظهر في حسابك، وينتهي تلقائياً بنهاية مدته دون تجديد آلي. تحدد خوادم Cigram صلاحية الكود وأهليته، والكود المستخدم لا يُعاد استخدامه."),
        ("الإعلانات", "قد يعرض التطبيق إعلانات من Google AdMob فقط، وفق إعدادات الموافقة المعمول بها في بلدك. مشتركو Cigram Plus لا تظهر لهم الإعلانات."),
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
