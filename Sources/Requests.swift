import UIKit

/// «اطلب فيلماً أو مسلسلاً أو قناة» + «طلباتي» — the same server and statuses as the Android app (cigram-user-api /requests).
/// Local first: a request is saved on the iPhone at once, then sent (retried later when offline). Its status comes from
/// the admin (GET /requests/mine), or by itself: a film / series request becomes «تمت الإضافة» as soon as the catalogue
/// has a title with that name, and opens it.
final class RequestsStore {
    static let shared = RequestsStore()
    static let changed = Notification.Name("cigram.requests.changed")
    static let base = "https://cigram-user-api.wwq-mixtv.workers.dev"

    struct Item: Codable {
        var localId: String
        var id: String
        var kind: String          // movie / series / channel
        var title: String
        var note: String
        var status: String        // pending / in_progress / added / unavailable
        var adminNote: String
        var created: Double
        var updated: Double
        var showKey: String
    }

    private let key = "cg.requests.v1"
    private(set) var items: [Item] = []
    private var refreshing = false
    private var lastRefresh = Date.distantPast

    init() {
        if let d = UserDefaults.standard.data(forKey: key), let list = try? JSONDecoder().decode([Item].self, from: d) { items = list }
    }

    private func save() {
        if let d = try? JSONEncoder().encode(Array(items.prefix(60))) { UserDefaults.standard.set(d, forKey: key) }
        NotificationCenter.default.post(name: RequestsStore.changed, object: nil)
    }

    static var clientId: String {
        if let s = UserDefaults.standard.string(forKey: "cg.client.id"), !s.isEmpty { return s }
        let s = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        UserDefaults.standard.set(s, forKey: "cg.client.id")
        return s
    }

    static func kindLabel(_ k: String) -> String { return k == "series" ? "مسلسل" : k == "channel" ? "قناة" : "فيلم" }

    static func statusLabel(_ s: String) -> String {
        switch s {
        case "in_progress": return "قيد العمل"
        case "added": return "تمت الإضافة"
        case "unavailable": return "غير متوفر"
        default: return "قيد المراجعة"
        }
    }

    static func statusColor(_ s: String) -> UIColor {
        switch s {
        case "in_progress": return UIColor(red: 0.16, green: 0.47, blue: 0.96, alpha: 1)
        case "added": return Theme.good
        case "unavailable": return UIColor(white: 0.45, alpha: 1)
        default: return UIColor(red: 0.95, green: 0.6, blue: 0.13, alpha: 1)
        }
    }

    @discardableResult
    func add(kind: String, title: String, note: String) -> Item {
        let now = Date().timeIntervalSince1970 * 1000
        let it = Item(localId: UUID().uuidString, id: "", kind: kind, title: title, note: note, status: "pending", adminNote: "",
                      created: now, updated: now, showKey: "")
        items.insert(it, at: 0)
        save()
        push()
        return it
    }

    func remove(_ localId: String) {
        items.removeAll { $0.localId == localId }
        save()
    }

    /// Sends every request that has no server id yet.
    func push() {
        for it in items where it.id.isEmpty { send(it, withToken: true) }
    }

    private func send(_ it: Item, withToken: Bool) {
        guard let url = URL(string: RequestsStore.base + "/requests") else { return }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        req.httpMethod = "POST"
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        var body: [String: Any] = ["kind": it.kind, "title": it.title, "note": it.note, "client_id": RequestsStore.clientId,
                                   "app_version": "ios-" + AppInfo.version]
        let token = withToken ? (Account.shared.token ?? "") : ""
        if !token.isEmpty {
            req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
            // the admin reads «يريد #الاسم فيلم»: the display name only (no e-mail, no id)
            if !Account.shared.name.isEmpty { body["user_name"] = Account.shared.name }
        }
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: req) { data, resp, _ in
            let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let json = data.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } ?? [:]
            DispatchQueue.main.async {
                if status == 401 && !token.isEmpty { self.send(it, withToken: false); return }   // expired token: sent as a guest
                guard (json["ok"] as? Bool) == true, let id = (json["id"] as? String) ?? (json["id"] as? NSNumber)?.stringValue, !id.isEmpty,
                      let i = self.items.firstIndex(where: { $0.localId == it.localId }) else { return }
                self.items[i].id = id
                self.items[i].updated = Date().timeIntervalSince1970 * 1000
                self.save()
            }
        }.resume()
    }

    /// Push what is waiting, pull the admin's statuses, match the catalogue. Quietly; at most every 2 minutes unless forced.
    func refresh(force: Bool = false, _ done: (() -> Void)? = nil) {
        if items.isEmpty || refreshing || (!force && Date().timeIntervalSince(lastRefresh) < 120) { matchCatalog(); done?(); return }
        refreshing = true
        lastRefresh = Date()
        push()
        let ids = items.filter { !$0.id.isEmpty && !($0.status == "added" && !$0.showKey.isEmpty) }.map { $0.id }
        guard !ids.isEmpty, let q = ids.joined(separator: ",").addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: RequestsStore.base + "/requests/mine?ids=" + q) else {
            refreshing = false
            matchCatalog()
            done?()
            return
        }
        URLSession.shared.dataTask(with: URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)) { data, _, _ in
            let json = data.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } ?? [:]
            DispatchQueue.main.async {
                self.refreshing = false
                if (json["ok"] as? Bool) == true {
                    for s in (json["requests"] as? [[String: Any]]) ?? [] {
                        let sid = (s["id"] as? String) ?? (s["id"] as? NSNumber)?.stringValue ?? ""
                        guard let i = self.items.firstIndex(where: { $0.id == sid && !sid.isEmpty }) else { continue }
                        let st = (s["status"] as? String) ?? "pending"
                        // a local catalogue match («added») is never downgraded by the server
                        if self.items[i].status == "added" && st != "added" { continue }
                        self.items[i].status = st
                        self.items[i].adminNote = (s["admin_note"] as? String) ?? ""
                        self.items[i].updated = Date().timeIntervalSince1970 * 1000
                    }
                }
                self.matchCatalog()
                self.save()
                done?()
            }
        }.resume()
    }

    /// A film / series request is «تمت الإضافة» when the catalogue has a title of that name and kind.
    private func matchCatalog() {
        let shows = CatalogStore.shared.shows
        guard !shows.isEmpty else { return }
        var changed = false
        for i in items.indices where items[i].kind != "channel" && items[i].showKey.isEmpty {
            let want = CatalogueParser.normalize(items[i].title)
            guard want.count >= 2 else { continue }
            let series = items[i].kind == "series"
            if let hit = shows.first(where: { $0.isSeries == series &&
                (CatalogueParser.normalize($0.title) == want || CatalogueParser.normalize($0.head.originalTitle) == want) }) {
                items[i].status = "added"
                items[i].showKey = hit.key
                items[i].updated = Date().timeIntervalSince1970 * 1000
                changed = true
            }
        }
        if changed {
            if let d = try? JSONEncoder().encode(Array(items.prefix(60))) { UserDefaults.standard.set(d, forKey: key) }
            NotificationCenter.default.post(name: RequestsStore.changed, object: nil)
        }
    }
}

/// The form at the top of «طلباتي».
final class RequestFormView: UIView, UITextFieldDelegate {
    let kind = UISegmentedControl(items: ["فيلم", "مسلسل", "قناة"])
    let titleField = LoginViewController.field("اسم الفيلم أو المسلسل أو القناة", secure: false)
    let noteField = LoginViewController.field("ملاحظة (اختياري): السنة، الموسم، اللغة…", secure: false)
    let send = UIButton(type: .system)
    var onSend: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        let card = UIView()
        card.backgroundColor = Theme.surface
        card.layer.cornerRadius = 18
        card.translatesAutoresizingMaskIntoConstraints = false
        addSubview(card)
        let head = AccountHeaderView.label("ما الذي تريد مشاهدته؟", 18, .heavy, .white)
        let sub = AccountHeaderView.label("اكتب الاسم وسيصل طلبك لفريق سيكرام. نخبرك هنا عندما يُضاف.", 13, .regular, Theme.text2)
        sub.numberOfLines = 0
        kind.selectedSegmentIndex = 0
        kind.selectedSegmentTintColor = Theme.red
        kind.setTitleTextAttributes([.foregroundColor: UIColor.white, .font: Theme.font(14, .bold)], for: .normal)
        kind.heightAnchor.constraint(equalToConstant: 36).isActive = true
        titleField.autocapitalizationType = .words
        titleField.returnKeyType = .next
        titleField.delegate = self
        noteField.returnKeyType = .send
        noteField.delegate = self
        send.setTitle("إرسال الطلب", for: .normal)
        send.setTitleColor(.white, for: .normal)
        send.titleLabel?.font = Theme.font(16, .bold)
        send.backgroundColor = Theme.red
        send.layer.cornerRadius = 13
        send.heightAnchor.constraint(equalToConstant: 50).isActive = true
        send.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [head, sub, kind, titleField, noteField, send])
        stack.axis = .vertical
        stack.spacing = 10
        stack.setCustomSpacing(14, after: sub)
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            card.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            card.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6),
            card.leftAnchor.constraint(equalTo: leftAnchor, constant: 16),
            card.rightAnchor.constraint(equalTo: rightAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -16),
            stack.leftAnchor.constraint(equalTo: card.leftAnchor, constant: 14),
            stack.rightAnchor.constraint(equalTo: card.rightAnchor, constant: -14),
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func textFieldShouldReturn(_ f: UITextField) -> Bool {
        if f === titleField { noteField.becomeFirstResponder() } else { sendTapped() }
        return true
    }

    @objc private func sendTapped() { onSend?() }

    var kindValue: String { return ["movie", "series", "channel"][max(0, min(2, kind.selectedSegmentIndex))] }

    func height(for width: CGFloat) -> CGFloat {
        frame = CGRect(x: 0, y: 0, width: width, height: 10)
        let s = systemLayoutSizeFitting(CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
                                        withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel)
        return ceil(s.height)
    }
}

final class RequestCell: UITableViewCell {
    static let id = "RequestCell"
    private let title = UILabel()
    private let sub = UILabel()
    private let note = UILabel()
    private let pill = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = Theme.surface
        let sel = UIView()
        sel.backgroundColor = Theme.surfaceHi
        selectedBackgroundView = sel
        title.font = Theme.font(15.5, .bold)
        title.textColor = .white
        title.textAlignment = .right
        sub.font = Theme.font(12.5)
        sub.textColor = Theme.text3
        sub.textAlignment = .right
        note.font = Theme.font(12.5, .medium)
        note.textColor = Theme.text2
        note.textAlignment = .right
        note.numberOfLines = 2
        pill.font = Theme.font(11.5, .heavy)
        pill.textColor = .white
        pill.textAlignment = .center
        pill.layer.cornerRadius = 10
        pill.clipsToBounds = true
        [title, sub, note, pill].forEach { contentView.addSubview($0) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    static func height(_ it: RequestsStore.Item) -> CGFloat { return it.adminNote.isEmpty ? 64 : 92 }

    func configure(_ it: RequestsStore.Item) {
        title.text = it.title
        let date = SupportTime.day(it.created)
        sub.text = RequestsStore.kindLabel(it.kind) + " · " + date + (it.id.isEmpty ? " · لم يُرسل بعد" : "")
        note.text = it.adminNote.isEmpty ? nil : "ردّ الإدارة: " + it.adminNote
        note.isHidden = it.adminNote.isEmpty
        pill.text = RequestsStore.statusLabel(it.status)
        pill.backgroundColor = RequestsStore.statusColor(it.status)
        accessoryType = it.status == "added" && !it.showKey.isEmpty ? .disclosureIndicator : .none
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = contentView.bounds.width
        let pw = SupportBubbleLayout.textWidth(pill.text ?? "", font: pill.font, max: 140) + 18
        pill.frame = CGRect(x: 12, y: 13, width: pw, height: 20)
        title.frame = CGRect(x: pw + 22, y: 11, width: w - pw - 38, height: 22)
        sub.frame = CGRect(x: 12, y: 36, width: w - 28, height: 17)
        note.frame = CGRect(x: 12, y: 57, width: w - 28, height: 32)
    }
}

final class RequestsViewController: UITableViewController {
    private let form = RequestFormView()
    private var formWidth: CGFloat = 0

    init() {
        super.init(style: .insetGrouped)
        hidesBottomBarWhenPushed = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "اطلب عنواناً"
        view.backgroundColor = Theme.bg
        tableView.backgroundColor = Theme.bg
        tableView.separatorColor = UIColor.white.withAlphaComponent(0.08)
        tableView.keyboardDismissMode = .onDrag
        tableView.register(RequestCell.self, forCellReuseIdentifier: RequestCell.id)
        form.onSend = { [weak self] in self?.submit() }
        refreshControl = UIRefreshControl()
        refreshControl?.tintColor = .white
        refreshControl?.addTarget(self, action: #selector(pull), for: .valueChanged)
        NotificationCenter.default.addObserver(self, selector: #selector(changed), name: RequestsStore.changed, object: nil)
        RequestsStore.shared.refresh(force: true)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = tableView.bounds.width
        guard w > 0, abs(w - formWidth) > 0.5 else { return }
        formWidth = w
        form.frame.size.height = form.height(for: w)
        form.frame.size.width = w
        tableView.tableHeaderView = form
    }

    @objc private func pull() { RequestsStore.shared.refresh(force: true) { [weak self] in self?.refreshControl?.endRefreshing() } }

    @objc private func changed() { tableView.reloadData() }

    private func submit() {
        view.endEditing(true)
        let t = (form.titleField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.count >= 2 else { flash("اكتب اسم العنوان أولاً"); return }
        let note = (form.noteField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if RequestsStore.shared.items.contains(where: { CatalogueParser.normalize($0.title) == CatalogueParser.normalize(t) && $0.status != "unavailable" }) {
            flash("طلبت هذا العنوان من قبل · ستجده في «طلباتي»")
            return
        }
        RequestsStore.shared.add(kind: form.kindValue, title: String(t.prefix(120)), note: String(note.prefix(400)))
        form.titleField.text = ""
        form.noteField.text = ""
        flash("وصل طلبك ✓ سنخبرك هنا عندما يُضاف")
    }

    private func flash(_ text: String) {
        let a = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        present(a, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { a.dismiss(animated: true) }
    }

    override func numberOfSections(in tableView: UITableView) -> Int { return 1 }
    override func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { return RequestsStore.shared.items.count }
    override func tableView(_ t: UITableView, titleForHeaderInSection s: Int) -> String? {
        return RequestsStore.shared.items.isEmpty ? nil : "طلباتي"
    }
    override func tableView(_ t: UITableView, titleForFooterInSection s: Int) -> String? {
        return RequestsStore.shared.items.isEmpty ? "لم ترسل أي طلب بعد." : "يتغير الحال تلقائياً عندما يراجع الفريق طلبك."
    }
    override func tableView(_ t: UITableView, willDisplayHeaderView view: UIView, forSection section: Int) {
        (view as? UITableViewHeaderFooterView)?.textLabel?.textAlignment = .right
    }
    override func tableView(_ t: UITableView, willDisplayFooterView view: UIView, forSection section: Int) {
        (view as? UITableViewHeaderFooterView)?.textLabel?.textAlignment = .right
    }
    override func tableView(_ t: UITableView, heightForRowAt ip: IndexPath) -> CGFloat {
        return RequestCell.height(RequestsStore.shared.items[ip.row])
    }

    override func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let c = t.dequeueReusableCell(withIdentifier: RequestCell.id, for: ip) as! RequestCell
        c.configure(RequestsStore.shared.items[ip.row])
        return c
    }

    override func tableView(_ t: UITableView, didSelectRowAt ip: IndexPath) {
        t.deselectRow(at: ip, animated: true)
        let it = RequestsStore.shared.items[ip.row]
        if let s = CatalogStore.shared.show(forKey: it.showKey) { Router.details(s, from: self) }
    }

    override func tableView(_ t: UITableView, trailingSwipeActionsConfigurationForRowAt ip: IndexPath) -> UISwipeActionsConfiguration? {
        let it = RequestsStore.shared.items[ip.row]
        let del = UIContextualAction(style: .destructive, title: "إزالة") { _, _, done in
            RequestsStore.shared.remove(it.localId)
            done(true)
        }
        return UISwipeActionsConfiguration(actions: [del])
    }
}
