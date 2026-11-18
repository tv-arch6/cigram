import UIKit
import AVFoundation
import AVKit
import CryptoKit
import PhotosUI
import QuickLook
import UserNotifications

// «الدعم والتواصل» — the private conversation between ONE signed-in viewer and the Cigram team.
//
// It is the very same chat the Android app uses (cigram-ads-api ChatHub: one thread per account, encrypted on the server,
// WebSocket for live events + HTTP for everything else, chunked media uploads). Only an account signed in with an e-mail
// can open it (the server identifies the thread by the account token). What the iPhone keeps:
//  - the last 60 messages + the unsent text queue, sealed with AES-GCM (CryptoKit) under a key that lives in the Keychain
//    of this device only, and tied to the account (another account on the same iPhone starts empty);
//  - nothing else: signed media links expire after an hour and are asked again.

// MARK: - API

enum SupportAPI {
    static let base = "https://cigram-ads-api.wwq-mixtv.workers.dev"
    static let wsURL = URL(string: "wss://cigram-ads-api.wwq-mixtv.workers.dev/chat/ws")!

    struct Result {
        var ok = false
        var code = 0
        var json: [String: Any] = [:]
        var error = ""
        var message = ""
        var offline = false
        /// The server still refuses the account after a renewal (or nothing can renew it): the viewer must sign in again.
        var authExpired = false
        /// A 401 that is NOT a lost session (the chat server could not verify it right now): kept and retried.
        var authTransient = false
        var transient: Bool { return offline || code == 0 || code == 408 || code == 429 || code >= 500 || authTransient }
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        return URLSession(configuration: c)
    }()

    static func get(_ path: String, auth: Bool = true, _ done: @escaping (Result) -> Void) {
        request("GET", path, body: nil, contentType: nil, auth: auth, done)
    }

    static func post(_ path: String, _ body: [String: Any], auth: Bool = true, _ done: ((Result) -> Void)? = nil) {
        let data = (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8)
        request("POST", path, body: data, contentType: "application/json; charset=utf-8", auth: auth) { done?($0) }
    }

    /// Every call. With `auth`, a 401 gets ONE shared silent renewal and ONE retry of the very same body (a message keeps
    /// its client_id, so the server can never store it twice); then the auth service itself is asked before the session
    /// is called «ended» (the Android rules of AdsAuthGateway). `done` always runs on the main queue.
    static func request(_ method: String, _ path: String, body: Data?, contentType: String?, auth: Bool, _ done: @escaping (Result) -> Void) {
        let finish: (Result) -> Void = { r in DispatchQueue.main.async { done(r) } }
        guard auth else { once(method, path, body, contentType, token: nil, finish); return }
        guard let token = Account.shared.token, !token.isEmpty else {
            var r = Result()
            r.code = 401
            r.authExpired = true
            r.message = "سجّل الدخول ببريدك الإلكتروني أولاً."
            finish(r)
            return
        }
        once(method, path, body, contentType, token: token) { r in
            guard r.code == 401 && !r.offline else { finish(r); return }
            freshToken(after: token) { fresh in
                if let f = fresh, f != token {
                    once(method, path, body, contentType, token: f) { r2 in
                        if r2.code == 401 { classify(r2, token: f, renewedAndRefused: true, finish) } else { finish(r2) }
                    }
                } else {
                    classify(r, token: token, renewedAndRefused: false, finish)
                }
            }
        }
    }

    /// A token newer than `used` (another request renewed the session meanwhile), else one silent renewal.
    static func freshToken(after used: String, _ done: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            if let cur = Account.shared.token, !cur.isEmpty, cur != used { done(cur); return }
            Account.shared.renew(done)
        }
    }

    private static func classify(_ r: Result, token: String, renewedAndRefused: Bool, _ done: @escaping (Result) -> Void) {
        DispatchQueue.main.async {
            Account.shared.probe(token) { p in
                var out = r
                if p == .valid {
                    out.authTransient = true
                    out.message = "خدمة الدعم لا تستطيع التحقق من حسابك الآن (المشكلة من الخادم وليست من حسابك). رسائلك محفوظة وسنعيد المحاولة تلقائياً."
                } else if Account.shared.canRenew && !renewedAndRefused {
                    out.authTransient = true
                    out.message = "تعذّر تجديد الجلسة الآن. حاول بعد قليل — حسابك ما زال مسجّلاً."
                } else {
                    out.authExpired = true
                    out.message = "انتهت جلسة حسابك. سجّل الدخول من جديد لمتابعة المحادثة؛ رسائلك المحفوظة باقية."
                }
                done(out)
            }
        }
    }

    private static func once(_ method: String, _ path: String, _ body: Data?, _ contentType: String?, token: String?,
                             _ done: @escaping (Result) -> Void) {
        guard let url = URL(string: base + path) else {
            var r = Result()
            r.message = "طلب غير صالح."
            done(r)
            return
        }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: method == "PUT" ? 90 : 30)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("Cigram-iOS/" + AppInfo.version, forHTTPHeaderField: "X-Cigram-Client")
        if let t = token { req.setValue("Bearer " + t, forHTTPHeaderField: "Authorization") }
        if let b = body {
            req.httpBody = b
            req.setValue(contentType ?? "application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        session.dataTask(with: req) { data, resp, err in
            var r = Result()
            if let http = resp as? HTTPURLResponse {
                r.code = http.statusCode
                let obj = data.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } ?? [:]
                r.json = obj
                r.ok = (200..<300).contains(r.code) && ((obj["ok"] as? Bool) ?? true)
                r.error = (obj["error"] as? String) ?? ""
                r.message = (obj["message"] as? String) ?? ""
                if !r.ok && r.message.isEmpty { r.message = friendly(r.code, r.error) }
            } else {
                r.offline = true
                let code = (err as NSError?)?.code ?? 0
                if code == NSURLErrorNotConnectedToInternet || code == NSURLErrorNetworkConnectionLost {
                    r.message = "لا يوجد اتصال بالإنترنت. تحقق من الشبكة ثم حاول مجدداً."
                } else if code == NSURLErrorTimedOut {
                    r.message = "انتهت مهلة الاتصال. حاول مرة أخرى."
                } else {
                    r.message = "تعذّر الاتصال بالخادم. حاول مرة أخرى بعد قليل."
                }
            }
            done(r)
        }.resume()
    }

    static func friendly(_ code: Int, _ error: String) -> String {
        switch code {
        case 401: return "سجّل الدخول أولاً."
        case 403: return error == "banned" ? "تم تقييد المحادثة من قبل الإدارة." : "غير مسموح."
        case 404: return "غير موجود."
        case 413: return "الملف أكبر من الحجم المسموح."
        case 415: return "نوع الملف غير مدعوم."
        case 429: return "طلبات كثيرة. انتظر قليلاً ثم حاول مجدداً."
        case 500...599: return "خادم الدعم مشغول الآن. سنعيد المحاولة تلقائياً."
        default: return "تعذّر إتمام الطلب."
        }
    }
}

// MARK: - the developer's public identity (name / picture / last seen — never an internal id)

enum SupportDeveloper {
    private static let d = UserDefaults.standard
    static var name: String { let n = d.string(forKey: "cg.support.dev.name") ?? ""; return n.isEmpty ? "فريق سيكرام" : n }
    static var avatar: URL? { return (d.string(forKey: "cg.support.dev.avatar")).flatMap { $0.hasPrefix("https://") ? URL(string: $0) : nil } }
    static var lastActive: Double { return d.double(forKey: "cg.support.dev.last") }

    static func learn(_ o: [String: Any]?) {
        guard let o = o else { return }
        let n = clean((o["name"] as? String) ?? (o["display_name"] as? String) ?? "")
        if !n.isEmpty { d.set(n, forKey: "cg.support.dev.name") }
        let a = (o["avatar"] as? String) ?? (o["avatar_url"] as? String) ?? ""
        if a.hasPrefix("https://") { d.set(a, forKey: "cg.support.dev.avatar") }
        if let la = (o["last_active"] as? NSNumber)?.doubleValue, la > 0 { d.set(la, forKey: "cg.support.dev.last") }
    }

    private static func clean(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "\u{202A}\u{202B}\u{202C}\u{202D}\u{202E}\u{2066}\u{2067}\u{2068}\u{2069}").union(.controlCharacters)
        let t = String(s.unicodeScalars.filter { !bad.contains($0) }).trimmingCharacters(in: .whitespacesAndNewlines)
        return String(t.prefix(40))
    }
}

// MARK: - time (24-hour clock, the phone's time zone; the server stores UTC milliseconds)

enum SupportTime {
    private static let hm: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm"
        return f
    }()
    private static let months = ["يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"]

    static func time(_ ms: Double) -> String { return hm.string(from: Date(timeIntervalSince1970: ms / 1000)) }

    static func day(_ ms: Double) -> String {
        let d = Date(timeIntervalSince1970: ms / 1000)
        let cal = Calendar.current
        if cal.isDateInToday(d) { return "اليوم" }
        if cal.isDateInYesterday(d) { return "أمس" }
        let c = cal.dateComponents([.day, .month, .year], from: d)
        var s = String(c.day ?? 1) + " " + months[max(0, min(11, (c.month ?? 1) - 1))]
        if c.year != cal.component(.year, from: Date()) { s += " " + String(c.year ?? 0) }
        return s
    }

    static func when(_ ms: Double) -> String { return day(ms) + " " + time(ms) }

    static func lastSeen(_ ms: Double) -> String { return ms > 0 ? "آخر ظهور " + when(ms) : "" }

    static func duration(_ ms: Int) -> String {
        let s = max(0, ms / 1000)
        return String(s / 60) + ":" + String(format: "%02d", s % 60)
    }

    static func size(_ bytes: Int64) -> String {
        let b = Double(bytes)
        if b >= 1_073_741_824 { return String(format: "%.1f GB", b / 1_073_741_824) }
        if b >= 1_048_576 { return String(format: "%.1f MB", b / 1_048_576) }
        if b >= 1024 { return String(format: "%.0f KB", b / 1024) }
        return String(bytes) + " B"
    }
}

// MARK: - one message

final class SupportMessage {
    enum State { case server, sending, failed, waiting }

    var seq: Int64 = 0
    var id = ""
    var from = "user"
    var type = "text"
    var text = ""
    var clientId = ""
    var ts: Double = 0
    var delivered: Double = 0
    var read: Double = 0
    var deleted = false
    var media: [String: Any]?
    var reply: [String: Any]?
    var quote: [String: Any]?
    var order: [String: Any]?
    var dev: [String: Any]?
    var state: State = .server
    var failMessage = ""
    var progress: Float = -1
    var replyTo: Int64 = 0
    var prepared: SupportUpload.Prepared?
    var upload: SupportUpload?
    var localImage: UIImage?

    var mine: Bool { return from == "user" }
    var isSystem: Bool { return type == "system" || from == "system" }
    var mediaKind: String { return (media?["kind"] as? String) ?? type }
    var mediaId: String { return (media?["id"] as? String) ?? "" }
    var mediaURL: URL? { return (media?["url"] as? String).flatMap { URL(string: $0) } }
    var mediaW: CGFloat { return CGFloat((media?["w"] as? NSNumber)?.doubleValue ?? 0) }
    var mediaH: CGFloat { return CGFloat((media?["h"] as? NSNumber)?.doubleValue ?? 0) }
    var mediaDur: Int { return (media?["dur"] as? NSNumber)?.intValue ?? 0 }
    var mediaSize: Int64 { return (media?["size"] as? NSNumber)?.int64Value ?? 0 }
    var mediaName: String { return (media?["name"] as? String) ?? "ملف" }
    var mediaMime: String { return (media?["mime"] as? String) ?? "" }
    var wave: [Int] { return ((media?["wave"] as? [Any]) ?? []).compactMap { ($0 as? NSNumber)?.intValue } }
    var isImage: Bool { return mediaKind == "image" || (type == "file" && mediaMime.hasPrefix("image/")) }

    /// What a reply / notification shows for this message.
    var preview: String {
        if deleted { return "رسالة محذوفة" }
        switch type {
        case "image": return "📷 صورة"
        case "video": return "🎬 فيديو"
        case "voice": return "🎤 رسالة صوتية"
        case "file": return "📎 " + mediaName
        default: return text
        }
    }

    static func from(_ o: [String: Any]) -> SupportMessage {
        let m = SupportMessage()
        m.seq = (o["seq"] as? NSNumber)?.int64Value ?? 0
        m.id = (o["id"] as? String) ?? ""
        m.from = (o["from"] as? String) ?? "user"
        m.type = (o["type"] as? String) ?? "text"
        m.text = (o["text"] as? String) ?? ""
        m.ts = (o["ts"] as? NSNumber)?.doubleValue ?? 0
        m.delivered = (o["delivered"] as? NSNumber)?.doubleValue ?? 0
        m.read = (o["read"] as? NSNumber)?.doubleValue ?? 0
        m.deleted = (o["deleted"] as? Bool) ?? false
        m.media = o["media"] as? [String: Any]
        m.reply = o["reply"] as? [String: Any]
        m.quote = o["quote"] as? [String: Any]
        m.order = o["order"] as? [String: Any]
        m.dev = o["dev"] as? [String: Any]
        m.clientId = (o["client_id"] as? String) ?? ""
        m.replyTo = (o["reply_to"] as? NSNumber)?.int64Value ?? 0
        return m
    }

    /// The saved copy of a server message (the signed media link is dropped: it expires, a fresh one is asked at open).
    func cacheJSON() -> [String: Any] {
        var o: [String: Any] = ["seq": NSNumber(value: seq), "id": id, "from": from, "type": type, "text": text, "ts": ts,
                                "delivered": delivered, "read": read, "deleted": deleted]
        if !clientId.isEmpty { o["client_id"] = clientId }
        if var md = media { md.removeValue(forKey: "url"); o["media"] = md }
        if let r = reply { o["reply"] = r }
        if let q = quote { o["quote"] = q }
        if let x = order { o["order"] = x }
        if let d = dev { o["dev"] = d }
        return o
    }

    /// A text that is waiting to be sent (kept across launches).
    func pendingJSON() -> [String: Any] {
        var o: [String: Any] = ["client_id": clientId, "type": type, "text": text, "ts": ts]
        if replyTo > 0 { o["reply_to"] = NSNumber(value: replyTo) }
        if let r = reply { o["reply"] = r }
        return o
    }

    static func newClientId() -> String {
        return String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(24))
    }
}

// MARK: - the sealed local copy

enum SupportStore {
    private static let keep = 60

    private static var file: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Cigram", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("support-chat.sealed")
    }

    private static func key() -> SymmetricKey? {
        if let b = Keychain.get("support.key"), let d = Data(base64Encoded: b), d.count == 32 { return SymmetricKey(data: d) }
        let k = SymmetricKey(size: .bits256)
        let raw = k.withUnsafeBytes { Data($0) }
        Keychain.set(raw.base64EncodedString(), for: "support.key")
        // the Keychain write can fail (device locked before first unlock): never write plain text in that case
        guard Keychain.get("support.key") != nil else { return nil }
        return k
    }

    private static var owner: String { return (Account.shared.userId ?? "") + "|" + Account.shared.email.lowercased() }

    static func load() -> (messages: [SupportMessage], pending: [SupportMessage]) {
        guard let k = key(), let sealed = try? Data(contentsOf: file),
              let box = try? AES.GCM.SealedBox(combined: sealed), let plain = try? AES.GCM.open(box, using: k),
              let o = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any],
              (o["owner"] as? String) == owner else { return ([], []) }
        let ms = ((o["messages"] as? [[String: Any]]) ?? []).map { SupportMessage.from($0) }
        let pd: [SupportMessage] = ((o["pending"] as? [[String: Any]]) ?? []).compactMap { p in
            let m = SupportMessage.from(p)
            guard !m.clientId.isEmpty, !m.text.isEmpty else { return nil }
            m.seq = 0
            m.from = "user"
            m.state = .waiting
            return m
        }
        return (ms, pd)
    }

    static func save(_ items: [SupportMessage]) {
        let server = items.filter { $0.seq > 0 }.suffix(keep).map { $0.cacheJSON() }
        let pending = items.filter { $0.seq == 0 && $0.prepared == nil && $0.type == "text" && !$0.clientId.isEmpty && $0.state != .server }
            .map { $0.pendingJSON() }
        let o: [String: Any] = ["owner": owner, "messages": Array(server), "pending": pending, "v": 1]
        DispatchQueue.global(qos: .utility).async {
            guard let k = key(), let plain = try? JSONSerialization.data(withJSONObject: o),
                  let sealed = try? AES.GCM.seal(plain, using: k).combined else { return }
            try? sealed.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    static func clear() {
        try? FileManager.default.removeItem(at: file)
    }
}

// MARK: - unread count, tab badge, background check

final class SupportCenter {
    static let shared = SupportCenter()
    static let changed = Notification.Name("cigram.support.changed")

    private(set) var unread = 0
    private(set) var lastSeq: Int64 = 0
    var chatVisible = false
    private var timer: Timer?
    private var asking = false

    func start() {
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.refreshUnread()
            self?.schedule()
        }
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.timer?.invalidate()
        }
        nc.addObserver(forName: Account.changed, object: nil, queue: .main) { [weak self] _ in
            if !Account.shared.isLoggedIn { self?.set(0) } else { self?.refreshUnread() }
        }
        refreshUnread()
        schedule()
    }

    private func schedule() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 75, repeats: true) { [weak self] _ in self?.refreshUnread() }
    }

    func refreshUnread() {
        guard Account.shared.isLoggedIn else { set(0); return }
        if asking || chatVisible { return }
        asking = true
        SupportAPI.get("/chat/unread") { [weak self] r in
            guard let self = self else { return }
            self.asking = false
            guard r.ok else { return }
            let n = (r.json["unread"] as? NSNumber)?.intValue ?? (r.json["unread_user"] as? NSNumber)?.intValue ?? 0
            self.lastSeq = (r.json["last_seq"] as? NSNumber)?.int64Value ?? self.lastSeq
            self.set(self.chatVisible ? 0 : n)
        }
    }

    func set(_ n: Int) {
        let v = max(0, n)
        if v == unread { return }
        unread = v
        NotificationCenter.default.post(name: SupportCenter.changed, object: nil)
    }

    func signedOut() {
        SupportStore.clear()
        UserDefaults.standard.removeObject(forKey: "cg.support.notified")
        set(0)
    }

    /// Background refresh (Notifier): a new reply from the team becomes a notification «رد جديد من الدعم» — once per message.
    func backgroundCheck(_ done: @escaping () -> Void) {
        guard Account.shared.isLoggedIn else { done(); return }
        SupportAPI.get("/chat/unread") { r in
            guard r.ok else { done(); return }
            let n = (r.json["unread"] as? NSNumber)?.intValue ?? 0
            let last = (r.json["last_seq"] as? NSNumber)?.int64Value ?? 0
            self.set(n)
            let told = Int64(UserDefaults.standard.integer(forKey: "cg.support.notified"))
            guard n > 0, last > told else { done(); return }
            SupportAPI.get("/chat/messages?after=" + String(max(0, last - 5)) + "&limit=5") { r2 in
                defer { done() }
                let list = ((r2.json["messages"] as? [[String: Any]]) ?? []).map { SupportMessage.from($0) }
                guard let m = list.last(where: { !$0.mine && !$0.isSystem && $0.seq > told }) else { return }
                UserDefaults.standard.set(Int(last), forKey: "cg.support.notified")
                let c = UNMutableNotificationContent()
                c.title = "رد جديد من " + SupportDeveloper.name
                c.body = String(m.preview.prefix(180))
                c.sound = .default
                c.userInfo = ["support": true]
                c.threadIdentifier = "cigram-support"
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "support-" + String(m.seq), content: c, trigger: nil))
            }
        }
    }
}

// MARK: - live events (WebSocket)

/// The live channel of the conversation (messages, read receipts, typing, presence). Auth goes in the handshake header,
/// never in the link. A heartbeat every 25 s; reconnects with a growing delay; the HTTP polling covers any gap.
final class SupportSocket: NSObject, URLSessionWebSocketDelegate {
    var onEvent: (([String: Any]) -> Void)?
    var onOpenChange: ((Bool) -> Void)?
    private(set) var isOpen = false
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var pingTimer: Timer?
    private var retry: TimeInterval = 2
    private var active = false
    private var generation = 0
    private var reconnectPending = false

    func start() {
        guard !active else { return }
        active = true
        connect()
    }

    func stop() {
        active = false
        generation += 1
        pingTimer?.invalidate()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
        setOpen(false)
    }

    func send(_ o: [String: Any]) {
        guard isOpen, let t = task, let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return }
        t.send(.string(s)) { _ in }
    }

    private func connect() {
        reconnectPending = false
        guard active, let token = Account.shared.token, !token.isEmpty else { return }
        generation += 1
        let gen = generation
        if session == nil { session = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue.main) }
        var req = URLRequest(url: SupportAPI.wsURL)
        req.timeoutInterval = 20
        req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        guard let t = session?.webSocketTask(with: req) else { return }
        task = t
        t.resume()
        receive(t, gen)
    }

    private func receive(_ t: URLSessionWebSocketTask, _ gen: Int) {
        t.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation else { return }
                switch result {
                case .success(let message):
                    if !self.isOpen { self.setOpen(true); self.retry = 2; self.startPing() }
                    var text: String?
                    switch message {
                    case .string(let s): text = s
                    case .data(let d): text = String(data: d, encoding: .utf8)
                    @unknown default: text = nil
                    }
                    if let s = text, s.hasPrefix("{"), let d = s.data(using: .utf8),
                       let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                        self.onEvent?(o)
                    }
                    self.receive(t, gen)
                case .failure:
                    self.dropped()
                }
            }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol proto: String?) {
        guard webSocketTask === task else { return }
        setOpen(true)
        retry = 2
        startPing()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        guard webSocketTask === task else { return }
        dropped()
    }

    private func startPing() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 25, repeats: true) { [weak self] _ in
            self?.task?.send(.string("ping")) { _ in }
        }
    }

    private func dropped() {
        setOpen(false)
        pingTimer?.invalidate()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        guard active, !reconnectPending else { return }
        reconnectPending = true
        let wait = retry
        retry = min(60, retry * 2)
        generation += 1
        let gen = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self = self, self.active, gen == self.generation else { return }
            self.connect()
        }
    }

    private func setOpen(_ open: Bool) {
        if isOpen == open { return }
        isOpen = open
        onOpenChange?(open)
    }
}

// MARK: - chunked upload (the server's /chat/upload protocol: init → parts of 1 MB → complete; resumable)

final class SupportUpload {
    struct Prepared {
        let file: URL
        let kind: String           // image / video / voice / file
        let name: String
        let size: Int64
        let w: Int
        let h: Int
        let dur: Int               // milliseconds
        let wave: [Int]            // voice: 64 levels 0…255
        let preview: UIImage?
    }

    static let maxVideo: Int64 = 1024 * 1024 * 1024
    static let maxOther: Int64 = 25 * 1024 * 1024

    private(set) var mediaId: String?
    private var partSize = 1024 * 1024
    private var parts = 0
    private(set) var cancelled = false

    func cancel() { cancelled = true }

    /// `done(mediaId, error)` on the main queue.
    func run(_ p: Prepared, progress: @escaping (Float) -> Void, done: @escaping (String?, String) -> Void) {
        cancelled = false
        if let id = mediaId, parts > 0 {
            // «إعادة المحاولة»: the same session continues; only what the server is missing is sent again
            SupportAPI.get("/chat/upload/status?id=" + id) { r in
                if r.ok, let have = r.json["have"] as? [Any] {
                    var got = Set<Int>()
                    have.forEach { if let n = ($0 as? NSNumber)?.intValue { got.insert(n) } }
                    self.send(p, have: got, progress: progress, done: done)
                } else {
                    self.mediaId = nil
                    self.begin(p, progress: progress, done: done)
                }
            }
            return
        }
        begin(p, progress: progress, done: done)
    }

    private func begin(_ p: Prepared, progress: @escaping (Float) -> Void, done: @escaping (String?, String) -> Void) {
        var body: [String: Any] = ["name": p.name, "kind": p.kind, "size": NSNumber(value: p.size), "dur": p.dur, "w": p.w, "h": p.h]
        if !p.wave.isEmpty { body["wave"] = p.wave }
        SupportAPI.post("/chat/upload/init", body) { r in
            guard r.ok, let id = r.json["media_id"] as? String, !id.isEmpty else {
                done(nil, r.code == 413 ? "خادم المحادثة لا يقبل حالياً ملفاً بهذا الحجم." : (r.message.isEmpty ? "تعذّر بدء الرفع." : r.message))
                return
            }
            self.mediaId = id
            self.partSize = max(64 * 1024, (r.json["part_size"] as? NSNumber)?.intValue ?? 1024 * 1024)
            let computed = Int((p.size + Int64(self.partSize) - 1) / Int64(self.partSize))
            self.parts = max(1, (r.json["parts"] as? NSNumber)?.intValue ?? computed)
            self.send(p, have: [], progress: progress, done: done)
        }
    }

    private func send(_ p: Prepared, have: Set<Int>, progress: @escaping (Float) -> Void, done: @escaping (String?, String) -> Void) {
        guard let id = mediaId else { done(nil, "تعذّر الرفع."); return }
        let missing = (0..<parts).filter { !have.contains($0) }
        var sent = parts - missing.count
        func next(_ i: Int, attempt: Int) {
            if cancelled { done(nil, "أُلغي الرفع."); return }
            guard i < missing.count else { complete(p, progress: progress, done: done, again: true); return }
            let n = missing[i]
            guard let chunk = SupportUpload.read(p.file, offset: Int64(n) * Int64(partSize), length: partSize) else {
                done(nil, "الملف تغيّر أثناء الرفع.")
                return
            }
            SupportAPI.request("PUT", "/chat/upload/part?id=" + id + "&n=" + String(n), body: chunk,
                               contentType: "application/octet-stream", auth: true) { r in
                if r.ok {
                    sent += 1
                    progress(Float(sent) / Float(max(1, self.parts)))
                    next(i + 1, attempt: 0)
                } else if [400, 403, 404, 413, 415].contains(r.code) {
                    done(nil, r.code == 415 ? "نوع الملف غير مدعوم." : (r.message.isEmpty ? "الملف غير مقبول." : r.message))
                } else if attempt < 3 && (r.transient || r.code == 409) {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8 * Double(attempt + 1)) { next(i, attempt: attempt + 1) }
                } else {
                    done(nil, r.message.isEmpty ? "تعذّر رفع الملف. تحقق من اتصالك وحاول مجدداً." : r.message)
                }
            }
        }
        next(0, attempt: 0)
    }

    private func complete(_ p: Prepared, progress: @escaping (Float) -> Void, done: @escaping (String?, String) -> Void, again: Bool) {
        guard let id = mediaId else { done(nil, "تعذّر الرفع."); return }
        SupportAPI.post("/chat/upload/complete?id=" + id, [:]) { r in
            if r.ok {
                let fid = (r.json["media_id"] as? String) ?? id
                self.mediaId = nil
                self.parts = 0
                done(fid, "")
                return
            }
            guard again else { done(nil, r.message.isEmpty ? "تعذّر إكمال الرفع." : r.message); return }
            // missing parts on the server: ask which, send them once more, complete again
            SupportAPI.get("/chat/upload/status?id=" + id) { st in
                var got = Set<Int>()
                ((st.json["have"] as? [Any]) ?? []).forEach { if let n = ($0 as? NSNumber)?.intValue { got.insert(n) } }
                if st.ok && got.count < self.parts {
                    self.send(p, have: got, progress: progress) { fid, err in
                        if fid != nil { done(fid, "") } else { done(nil, err) }
                    }
                } else {
                    self.complete(p, progress: progress, done: done, again: false)
                }
            }
        }
    }

    static func read(_ file: URL, offset: Int64, length: Int) -> Data? {
        guard let h = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { h.closeFile() }
        h.seek(toFileOffset: UInt64(offset))
        let d = h.readData(ofLength: length)
        return d.isEmpty ? nil : d
    }

    // MARK: preparing what the viewer picked

    private static var dir: URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("cigram-support", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func cleanOld() {
        let fm = FileManager.default
        guard let list = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        for u in list {
            let t = (try? u.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
            if Date().timeIntervalSince(t) > 2 * 86_400 { try? fm.removeItem(at: u) }
        }
    }

    /// A photo: HEIC / PNG / anything → JPEG (the server accepts JPEG, PNG, GIF, WebP), at most 2048 px, EXIF orientation applied.
    static func prepareImage(_ image: UIImage) -> Prepared? {
        let longest = max(image.size.width * image.scale, image.size.height * image.scale)
        let factor = min(1, 2048 / max(1, longest))
        let size = CGSize(width: floor(image.size.width * image.scale * factor), height: floor(image.size.height * image.scale * factor))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let out = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = out.jpegData(compressionQuality: 0.82) else { return nil }
        let file = dir.appendingPathComponent("IMG_" + SupportMessage.newClientId() + ".jpg")
        guard (try? data.write(to: file)) != nil else { return nil }
        return Prepared(file: file, kind: "image", name: file.lastPathComponent, size: Int64(data.count), w: Int(size.width),
                        h: Int(size.height), dur: 0, wave: [], preview: out)
    }

    /// A video: re-encoded to H.264 MP4 at 720p (the server reads MP4 / WebM), duration and size measured.
    static func prepareVideo(_ source: URL, _ done: @escaping (Prepared?, String) -> Void) {
        let asset = AVURLAsset(url: source)
        let preset = AVAssetExportSession.exportPresets(compatibleWith: asset).contains(AVAssetExportPreset1280x720)
            ? AVAssetExportPreset1280x720 : AVAssetExportPresetMediumQuality
        guard let ex = AVAssetExportSession(asset: asset, presetName: preset) else { done(nil, "تعذّر تجهيز الفيديو."); return }
        let out = dir.appendingPathComponent("VID_" + SupportMessage.newClientId() + ".mp4")
        ex.outputURL = out
        ex.outputFileType = .mp4
        ex.shouldOptimizeForNetworkUse = true
        ex.exportAsynchronously {
            DispatchQueue.main.async {
                guard ex.status == .completed else { done(nil, "تعذّر تجهيز الفيديو."); return }
                let size = ((try? FileManager.default.attributesOfItem(atPath: out.path))?[.size] as? NSNumber)?.int64Value ?? 0
                if size > SupportUpload.maxVideo { try? FileManager.default.removeItem(at: out); done(nil, "حجم الفيديو أكبر من 1 غيغابايت."); return }
                var w = 0, h = 0
                if let track = AVURLAsset(url: out).tracks(withMediaType: .video).first {
                    let s = track.naturalSize.applying(track.preferredTransform)
                    w = Int(abs(s.width))
                    h = Int(abs(s.height))
                }
                let gen = AVAssetImageGenerator(asset: AVURLAsset(url: out))
                gen.appliesPreferredTrackTransform = true
                gen.maximumSize = CGSize(width: 480, height: 480)
                let thumb = (try? gen.copyCGImage(at: CMTime(seconds: 0.5, preferredTimescale: 600), actualTime: nil)).map { UIImage(cgImage: $0) }
                let dur = Int(CMTimeGetSeconds(asset.duration) * 1000)
                done(Prepared(file: out, kind: "video", name: out.lastPathComponent, size: size, w: w, h: h,
                              dur: max(0, dur), wave: [], preview: thumb), "")
            }
        }
    }

    /// A document from Files: the server takes PDF, ZIP and Word (and pictures / videos).
    static func prepareFile(_ source: URL) -> (Prepared?, String) {
        let ext = source.pathExtension.lowercased()
        let target = dir.appendingPathComponent(SupportMessage.newClientId() + "_" + source.lastPathComponent)
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        try? FileManager.default.removeItem(at: target)
        guard (try? FileManager.default.copyItem(at: source, to: target)) != nil else { return (nil, "تعذّر قراءة الملف.") }
        let size = ((try? FileManager.default.attributesOfItem(atPath: target.path))?[.size] as? NSNumber)?.int64Value ?? 0
        if size <= 0 { return (nil, "الملف فارغ.") }
        if ["jpg", "jpeg", "png", "heic", "heif", "gif", "webp"].contains(ext) {
            if ext == "gif" || ext == "webp" || ext == "png" || ext == "jpg" || ext == "jpeg" {
                guard size <= maxOther else { return (nil, "الملف أكبر من 25MB.") }
                let img = UIImage(contentsOfFile: target.path)
                return (Prepared(file: target, kind: "image", name: source.lastPathComponent, size: size,
                                 w: Int((img?.size.width ?? 0) * (img?.scale ?? 1)), h: Int((img?.size.height ?? 0) * (img?.scale ?? 1)),
                                 dur: 0, wave: [], preview: img), "")
            }
            if let img = UIImage(contentsOfFile: target.path), let p = prepareImage(img) { return (p, "") }
            return (nil, "تعذّر قراءة الصورة.")
        }
        guard size <= maxOther else { return (nil, "الملف أكبر من 25MB.") }
        guard ["pdf", "zip", "doc", "docx"].contains(ext) else {
            return (nil, "يمكن إرسال الصور والفيديو وملفات PDF وZIP وWord فقط.")
        }
        return (Prepared(file: target, kind: "file", name: source.lastPathComponent, size: size, w: 0, h: 0, dur: 0, wave: [], preview: nil), "")
    }
}

// MARK: - voice messages

/// Records a voice message: AAC in an M4A file (what the server accepts as «voice»), level meter for the waveform,
/// at most 5 minutes. The app's audio session goes back to playback afterwards (the players keep their sound).
final class SupportRecorder: NSObject, AVAudioRecorderDelegate {
    private var recorder: AVAudioRecorder?
    private var levels: [Float] = []
    private var meter: Timer?
    private(set) var startedAt: Date?
    var onTick: ((TimeInterval, Float) -> Void)?
    var onLimit: (() -> Void)?
    static let limit: TimeInterval = 300

    var isRecording: Bool { return recorder?.isRecording ?? false }

    func start(_ done: @escaping (Bool, String) -> Void) {
        let s = AVAudioSession.sharedInstance()
        s.requestRecordPermission { granted in
            DispatchQueue.main.async {
                guard granted else { done(false, "اسمح لسيكرام باستخدام الميكروفون من الإعدادات لإرسال رسالة صوتية."); return }
                do {
                    try s.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetooth])
                    try s.setActive(true)
                    let file = FileManager.default.temporaryDirectory.appendingPathComponent("voice_" + SupportMessage.newClientId() + ".m4a")
                    let settings: [String: Any] = [AVFormatIDKey: Int(kAudioFormatMPEG4AAC), AVSampleRateKey: 22050,
                                                   AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32000,
                                                   AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue]
                    let r = try AVAudioRecorder(url: file, settings: settings)
                    r.delegate = self
                    r.isMeteringEnabled = true
                    guard r.record(forDuration: SupportRecorder.limit) else { done(false, "تعذّر بدء التسجيل."); return }
                    self.recorder = r
                    self.levels = []
                    self.startedAt = Date()
                    self.meter?.invalidate()
                    self.meter = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in self?.tick() }
                    done(true, "")
                } catch {
                    done(false, "تعذّر بدء التسجيل.")
                }
            }
        }
    }

    private func tick() {
        guard let r = recorder, r.isRecording else { return }
        r.updateMeters()
        let db = r.averagePower(forChannel: 0)                // -160 … 0
        let level = max(0, min(1, (db + 50) / 50))
        levels.append(level)
        onTick?(r.currentTime, level)
    }

    /// Stops; `keep` = send it. Returns the prepared voice message (nil when cancelled or shorter than a second).
    func stop(keep: Bool) -> SupportUpload.Prepared? {
        meter?.invalidate()
        guard let r = recorder else { restoreSession(); return nil }
        // after the 5-minute limit the recorder has already stopped (currentTime is 0 then): measure from the start
        let elapsed = startedAt.map { Date().timeIntervalSince($0) } ?? 0
        let dur = r.isRecording ? r.currentTime : min(SupportRecorder.limit, elapsed)
        r.stop()
        recorder = nil
        restoreSession()
        let file = r.url
        guard keep, dur >= 1 else { try? FileManager.default.removeItem(at: file); return nil }
        let size = ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? 0
        guard size > 0 else { return nil }
        return SupportUpload.Prepared(file: file, kind: "voice", name: file.lastPathComponent, size: size, w: 0, h: 0,
                                      dur: Int(dur * 1000), wave: SupportRecorder.wave(levels, bars: 64), preview: nil)
    }

    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        if self.recorder != nil && (startedAt.map { Date().timeIntervalSince($0) } ?? 0) >= SupportRecorder.limit - 1 { onLimit?() }
    }

    private func restoreSession() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
    }

    /// `bars` levels 0…255 (peak of each slice of the recording).
    static func wave(_ levels: [Float], bars: Int) -> [Int] {
        guard !levels.isEmpty else { return Array(repeating: 20, count: bars) }
        var out: [Int] = []
        for i in 0..<bars {
            let a = i * levels.count / bars
            let b = max(a + 1, (i + 1) * levels.count / bars)
            let peak = levels[a..<min(b, levels.count)].max() ?? 0
            out.append(Int(max(0.06, peak) * 255))
        }
        return out
    }
}

/// Plays one voice message at a time (the cells show its progress).
final class SupportAudio: NSObject {
    static let shared = SupportAudio()
    static let changed = Notification.Name("cigram.support.audio")
    private var player: AVPlayer?
    private var observer: Any?
    private(set) var playingKey = ""
    private(set) var progress: Double = 0
    private(set) var isPlaying = false

    func toggle(key: String, url: URL) {
        if key == playingKey, let p = player {
            if isPlaying { p.pause(); isPlaying = false } else { p.play(); isPlaying = true }
            post()
            return
        }
        stop()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [])
        try? AVAudioSession.sharedInstance().setActive(true)
        let p = AVPlayer(url: url)
        player = p
        playingKey = key
        isPlaying = true
        progress = 0
        observer = p.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.1, preferredTimescale: 600), queue: .main) { [weak self] t in
            guard let self = self, let item = p.currentItem else { return }
            let total = CMTimeGetSeconds(item.duration)
            self.progress = total.isFinite && total > 0 ? min(1, CMTimeGetSeconds(t) / total) : 0
            if self.progress >= 0.999 { self.finish() } else { self.post() }
        }
        NotificationCenter.default.addObserver(self, selector: #selector(ended(_:)), name: .AVPlayerItemDidPlayToEndTime, object: p.currentItem)
        p.play()
        post()
    }

    @objc private func ended(_ n: Notification) { finish() }

    private func finish() {
        stop()
    }

    func stop() {
        let wasPlaying = player != nil
        if let o = observer, let p = player { p.removeTimeObserver(o) }
        if let item = player?.currentItem { NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: item) }
        observer = nil
        player?.pause()
        player = nil
        playingKey = ""
        isPlaying = false
        progress = 0
        post()
        // synchronous (setCategory is quick): a recording that starts right after must not be overtaken by this reset
        if wasPlaying { try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: []) }
    }

    private func post() { NotificationCenter.default.post(name: SupportAudio.changed, object: nil) }
}
