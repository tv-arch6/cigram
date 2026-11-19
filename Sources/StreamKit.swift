import Foundation

// Foundation-only building blocks of the native player. Ported from the Android app:
//   CigramStreams (links, "|headers", type detection), CigramEmbedResolver (finding the real stream behind an embed page,
//   HLS classification, the joined master playlist), CigramPlaybackError (WHY a title does not play), CigramRepairPlan
//   (the repair ladder), CigramSmartPlayback (conditions + a speed per series), CigramResume, CigramQuality (labels).
// Nothing in this file touches UIKit, so every decision here is plain, testable logic.

// MARK: - links

enum StreamKind: String {
    case hls, dash, smooth, rtsp, mp4, mkv, webm, ts, flv, audio, unknown

    /// What iOS (AVPlayer) can open by itself. DASH / MKV / WebM / FLV / RTSP / Smooth are not among them.
    var playableOnIOS: Bool {
        switch self {
        case .hls, .mp4, .audio, .unknown: return true
        default: return false
        }
    }
}

enum StreamLink {
    /// Same browser identity the web player uses (the embed CDNs are tested with it).
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

    /// Hosts known to serve player pages instead of files.
    static let embedHosts = ["vidbolt.pro", "vidbolt.xyz", "vidfast.vc", "vidfast.pro", "vidnest.fun", "videm.xyz", "vidsrc.to",
                             "vidsrc.me", "vidsrc.xyz", "vidlink.pro", "embed.su", "2embed.cc", "multiembed.mov", "autoembed.cc"]

    private static var learned: [String: StreamKind] = [:]
    private static let lock = NSLock()

    /// The link without the "|headers" part.
    static func clean(_ url: String) -> String {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let bar = u.firstIndex(of: "|") { return String(u[u.startIndex..<bar]).trimmingCharacters(in: .whitespaces) }
        return u
    }

    /// Headers written after a pipe: "|User-Agent=x&Referer=y" (values may be percent-encoded).
    static func headers(of url: String) -> [String: String] {
        guard let bar = url.firstIndex(of: "|") else { return [:] }
        var out: [String: String] = [:]
        let rest = String(url[url.index(after: bar)...])
        for part in rest.components(separatedBy: "&") {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            let k = String(part[part.startIndex..<eq]).trimmingCharacters(in: .whitespaces)
            let raw = String(part[part.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            let v = raw.removingPercentEncoding ?? raw
            if !k.isEmpty { out[k] = v }
        }
        return out
    }

    private static let unreserved: CharacterSet = {
        var s = CharacterSet.alphanumerics
        s.insert(charactersIn: "-._~")
        return s
    }()

    static func encode(_ s: String) -> String { return s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? s }

    /// "https://host[:port]" of a link ("" when it has none).
    static func origin(of url: String) -> String {
        guard let u = URL(string: clean(url)), let scheme = u.scheme, let host = u.host else { return "" }
        if let p = u.port { return scheme + "://" + host + ":" + String(p) }
        return scheme + "://" + host
    }

    static func host(of url: String) -> String { return (URL(string: clean(url))?.host ?? "").lowercased() }

    /// Adds the headers the CDN of `stream` expects (Referer / Origin of the embed site), Kodi style.
    static func withHeaders(_ stream: String, referer page: String, userAgent: String = StreamLink.userAgent) -> String {
        let o = origin(of: page)
        if o.isEmpty { return stream }
        return stream + "|Referer=" + encode(o + "/") + "&Origin=" + encode(o) + "&User-Agent=" + encode(userAgent)
    }

    static func absolute(base: String, rel: String) -> String? {
        let r = rel.trimmingCharacters(in: .whitespacesAndNewlines)
        if r.isEmpty { return nil }
        if r.hasPrefix("//") { return "https:" + r }
        if r.hasPrefix("http://") || r.hasPrefix("https://") { return r }
        guard let b = URL(string: clean(base)), let u = URL(string: r, relativeTo: b) else { return nil }
        return u.absoluteURL.absoluteString
    }

    // MARK: type detection

    /// The stream type the link itself tells, or nil when it gives no hint.
    static func kind(of url: String) -> StreamKind? {
        let full = clean(url).lowercased()
        if full.isEmpty { return nil }
        lock.lock()
        let l = learned[full]
        lock.unlock()
        if let k = l { return k }
        if full.hasPrefix("rtsp://") || full.hasPrefix("rtsps://") { return .rtsp }
        if full.hasPrefix("data:application/vnd.apple.mpegurl") { return .hls }

        var path = full
        if let q = path.firstIndex(of: "?") { path = String(path[path.startIndex..<q]) }
        if let h = path.firstIndex(of: "#") { path = String(path[path.startIndex..<h]) }

        if path.hasSuffix(".m3u8") || path.hasSuffix(".m3u") || path.contains(".m3u8/") { return .hls }
        if path.hasSuffix(".mpd") || path.contains(".mpd/") { return .dash }
        if path.hasSuffix(".ism") || path.hasSuffix(".isml") || path.contains(".ism/") || path.contains(".isml/") {
            if full.contains("format=m3u8") { return .hls }
            if full.contains("format=mpd") { return .dash }
            return .smooth
        }
        if path.hasSuffix(".mp4") || path.hasSuffix(".m4v") || path.hasSuffix(".mov") { return .mp4 }
        if path.hasSuffix(".mkv") { return .mkv }
        if path.hasSuffix(".webm") { return .webm }
        if path.hasSuffix(".ts") || path.hasSuffix(".mts") || path.hasSuffix(".m2ts") { return .ts }
        if path.hasSuffix(".flv") { return .flv }
        if path.hasSuffix(".mp3") || path.hasSuffix(".aac") { return .audio }

        if full.contains("m3u8") || full.contains("/hls/") || full.contains("type=hls") || full.contains("output=hls")
            || full.contains("extension=m3u") { return .hls }
        if full.contains("/dash/") || full.contains("format=mpd") || full.contains("type=dash") || full.contains("output=mpd")
            || full.contains("manifest.mpd") { return .dash }
        if full.contains("output=ts") || full.contains("extension=ts") { return .ts }
        return nil
    }

    static func learn(_ url: String, _ k: StreamKind) {
        lock.lock()
        learned[clean(url).lowercased()] = k
        lock.unlock()
    }

    /// True when `url` is a web page that has to be resolved first (not a stream / file link).
    static func isEmbed(_ url: String) -> Bool {
        let c = clean(url)
        let low = c.lowercased()
        guard low.hasPrefix("http://") || low.hasPrefix("https://") else { return false }
        if kind(of: c) != nil { return false }
        guard let u = URL(string: c) else { return false }
        let host = (u.host ?? "").lowercased()
        let path = u.path.lowercased()
        for h in embedHosts where host == h || host.hasSuffix("." + h) { return true }
        if host.hasPrefix("embed.") || host.hasPrefix("player.") { return true }
        if path.contains("/embed") { return true }
        return path.hasSuffix(".html") || path.hasSuffix(".htm") || path.hasSuffix(".php")
    }

    /// True when the link points at one of the known embed sites (vidbolt, vidfast, ...).
    static func isKnownHost(_ url: String) -> Bool {
        let c = clean(url)
        guard let u = URL(string: c), let h = u.host?.lowercased() else { return false }
        for e in embedHosts where h == e || h.hasSuffix("." + e) { return kind(of: c) == nil }
        return false
    }
}

// MARK: - time text

enum ClockText {
    /// 75 → "1:15", 3725 → "1:02:05".
    static func format(_ seconds: Double) -> String {
        if !seconds.isFinite || seconds < 0 { return "0:00" }
        let t = Int(seconds)
        let h = t / 3600, m = (t % 3600) / 60, s = t % 60
        if h > 0 { return String(h) + ":" + (m < 10 ? "0" : "") + String(m) + ":" + (s < 10 ? "0" : "") + String(s) }
        return String(m) + ":" + (s < 10 ? "0" : "") + String(s)
    }

    /// "90", "12.5", "1:30", "01:02:03" → seconds (-1 when it is not a time).
    static func seconds(_ raw: String) -> Double {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return -1 }
        if t.contains(":") {
            var total = 0.0
            for p in t.components(separatedBy: ":") {
                guard let v = Double(p.trimmingCharacters(in: .whitespaces)) else { return -1 }
                total = total * 60 + v
            }
            return total
        }
        return Double(t) ?? -1
    }

    /// "10-30,100-120" (seconds) → [(10,30), (100,120)].
    static func segments(_ data: String) -> [(start: Double, end: Double)] {
        var out: [(start: Double, end: Double)] = []
        for range in data.components(separatedBy: ",") {
            let p = range.trimmingCharacters(in: .whitespaces).components(separatedBy: "-")
            if p.count == 2, let a = Double(p[0].trimmingCharacters(in: .whitespaces)), let b = Double(p[1].trimmingCharacters(in: .whitespaces)), b > a {
                out.append((start: a, end: b))
            }
        }
        return out
    }
}

// MARK: - quality names

enum QualityLabel {
    /// Standard height of a track: a 3840x1600 scope film is «4K», not «1600p».
    static func classify(height: Int, width: Int) -> Int {
        let h = max(0, height)
        if width >= 3800 || h >= 2000 { return 2160 }
        if width >= 2500 || h >= 1400 { return 1440 }
        if width >= 1900 || h >= 1000 { return 1080 }
        if width >= 1270 || h >= 700 { return 720 }
        if width >= 850 || h >= 470 { return 480 }
        if h >= 350 { return 360 }
        return h
    }

    /// «4K», «2K», «1080p», «720p»; below 720p no quality name is claimed.
    static func label(height: Int, width: Int = 0) -> String {
        let h = classify(height: height, width: width)
        if h >= 2160 { return "4K" }
        if h >= 1440 { return "2K" }
        if h >= 1080 { return "1080p" }
        if h >= 720 { return "720p" }
        return "الأصلية"
    }

    /// The four names the app offers; anything smaller is never listed by name.
    static func offered(height: Int, width: Int) -> Bool { return classify(height: height, width: width) >= 720 }
}

// MARK: - why a title does not play (CigramPlaybackError)

/// How the title being played is called in messages: «الفيلم» or «الحلقة» (never «الفيديو»). Set by the player when it starts.
enum ContentNoun {
    static var isEpisode = false
    static var name: String { return isEpisode ? "الحلقة" : "الفيلم" }
}

enum PlaybackProblem: Int {
    case unknown = 0, invalidURL, unsupportedFormat, missingStream, expiredSource, engine, timeout, offline, server, unreachable

    /// Arabic sentence for a category (no technical code).
    var message: String {
        switch self {
        case .invalidURL: return "رابط " + ContentNoun.name + " غير صالح."
        case .unsupportedFormat: return "صيغة " + ContentNoun.name + " غير مدعومة على هذا الجهاز."
        case .missingStream: return "ملف " + ContentNoun.name + " غير موجود في المصدر حالياً."
        case .expiredSource: return "انتهت صلاحية رابط المصدر أو رفض الخادم الوصول إليه."
        case .engine: return "تعذّر تشغيل محرك المتصفح (WebKit) اللازم لتجهيز " + ContentNoun.name + "."
        case .timeout: return "تأخر خادم المشاهدة في الرد. حاول مرة أخرى بعد قليل."
        case .offline: return "لا يوجد اتصال بالإنترنت على هذا الجهاز."
        case .server: return "خادم المشاهدة يواجه مشكلة حالياً."
        case .unreachable: return "تعذّر الوصول إلى خادم المشاهدة."
        case .unknown: return "تعذّر تشغيل " + ContentNoun.name + "."
        }
    }

    /// True for the categories where trying the source again (re-resolving, another server) can help.
    var retryable: Bool {
        switch self {
        case .expiredSource, .timeout, .server, .unreachable, .unknown: return true
        default: return false
        }
    }

    /// Checks a link before any player is opened. `.unknown` means "may be playable".
    static func validate(_ raw: String?) -> PlaybackProblem {
        guard let raw = raw else { return .invalidURL }
        let url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let low = url.lowercased()
        if url.count < 6 || low == "null" || low == "undefined" || low == "none" { return .invalidURL }
        guard let u = URL(string: StreamLink.clean(url)) else { return .invalidURL }
        let scheme = (u.scheme ?? "").lowercased()
        if scheme.isEmpty { return .invalidURL }
        if scheme == "javascript" || scheme == "about" || scheme == "mailto" || scheme == "tel" { return .invalidURL }
        let network = scheme == "http" || scheme == "https" || scheme.hasPrefix("rtsp")
        if network && (u.host ?? "").isEmpty { return .invalidURL }
        return .unknown
    }

    /// Category of a stream-resolver failure code ("timeout", "engine …", "no stream" …).
    static func fromResolver(_ reason: String, online: Bool) -> PlaybackProblem {
        let r = reason.lowercased()
        if r.hasPrefix("engine") || r.hasPrefix("webview") { return .engine }
        if r.contains("timeout") || r.contains("timed out") { return online ? .timeout : .offline }
        if r.contains("403") || r.contains("401") || r.contains("expired") { return .expiredSource }
        if r.contains("404") || r.contains("410") || r.contains("no stream") || r.contains("not found") { return .missingStream }
        if r.contains(" 500") || r.contains(" 502") || r.contains(" 503") || r.contains(" 504") { return .server }
        return .missingStream
    }

    /// Category of a player error: NSError domain + code (and the HTTP status the loader logged, 0 when none).
    static func classify(domain: String, code: Int, http: Int, online: Bool) -> PlaybackProblem {
        if http == 401 || http == 403 { return .expiredSource }
        if http == 404 || http == 410 { return .missingStream }
        if http >= 500 && http < 600 { return .server }
        if domain == NSURLErrorDomain {
            switch code {
            case -1009: return .offline
            case -1001: return online ? .timeout : .offline
            case -1003, -1004, -1005, -1200, -1201, -1202, -1203, -1204, -1205, -1206: return online ? .unreachable : .offline
            case -1100: return .missingStream
            case -1102, -1013: return .expiredSource
            default: return online ? .unreachable : .offline
            }
        }
        if domain == "AVFoundationErrorDomain" {
            switch code {
            case -11828, -11829, -11853, -11822, -11850: return .unsupportedFormat
            case -11833, -11821, -11839, -11831, -11835: return .unsupportedFormat
            case -11849: return online ? .unreachable : .offline
            default: break
            }
        }
        if domain == "CoreMediaErrorDomain" {
            switch code {
            case -12660, -12661: return .expiredSource
            case -12938, -12889, -12885: return .missingStream
            case -12642, -12645: return .unsupportedFormat
            default: break
            }
        }
        return online ? .unknown : .offline
    }
}

// MARK: - the repair ladder (CigramRepairPlan)

/// One broken playback is an "incident". Each failed attempt of the same incident moves one step further down a ladder
/// that depends on WHAT broke, from the cheapest fix to the most thorough one (see the Android CigramRepairPlan).
enum RepairPlan {
    // what broke
    enum Kind: Int { case network = 1, httpStatus, parsing, decoder, audio, liveWindow, stall, other }
    // what to do
    enum Action: Int { case waitNetwork = 0, reopen, reopenSource, lowerQualityReopen, liveEdge, giveUp }

    /// AVFoundation / CoreMedia error → kind.
    static func kind(domain: String, code: Int, http: Int) -> Kind {
        if http > 0 { return .httpStatus }
        if domain == NSURLErrorDomain { return .network }
        if domain == "AVFoundationErrorDomain" {
            switch code {
            case -11828, -11829, -11853, -11822, -11850: return .parsing
            case -11833, -11821, -11839: return .decoder
            case -11849, -11800: return .network
            default: return .other
            }
        }
        if domain == "CoreMediaErrorDomain" {
            switch code {
            case -12660, -12661, -12938, -12889, -12885: return .httpStatus
            case -12642, -12645: return .parsing
            default: return .network
            }
        }
        return .other
    }

    /// - attempt: 0 for the first repair of this incident, 1 for the second …
    /// - hasSeparateSource: the title was opened with a link that is not the stream being played (it can be asked again)
    static func next(kind: Kind, attempt: Int, online: Bool, httpStatus: Int, hasSeparateSource: Bool, canLowerQuality: Bool) -> Action {
        let networkBound = kind == .network || kind == .httpStatus || kind == .stall || kind == .other || kind == .liveWindow
        if !online && networkBound { return .waitNetwork }
        let again: Action = hasSeparateSource ? .reopenSource : .reopen
        switch kind {
        case .liveWindow:
            return attempt < 3 ? .liveEdge : (attempt < 5 ? .reopen : .giveUp)
        case .httpStatus:
            if httpStatus == 401 || httpStatus == 403 || httpStatus == 404 || httpStatus == 410 { return attempt < 2 ? again : .giveUp }
            return attempt < 2 ? .reopen : (attempt == 2 ? again : .giveUp)
        case .network:
            return attempt < 2 ? .reopen : (attempt == 2 ? again : (attempt == 3 ? .reopen : .giveUp))
        case .parsing:
            return attempt == 0 ? again : .giveUp
        case .decoder:
            if attempt == 0 { return .reopen }
            return attempt <= 2 && canLowerQuality ? .lowerQualityReopen : .giveUp
        case .audio:
            return attempt < 2 ? .reopen : .giveUp
        case .stall:
            if attempt == 0 { return .reopen }
            if attempt == 1 { return again }
            if attempt == 2 { return canLowerQuality ? .lowerQualityReopen : .reopen }
            return .giveUp
        case .other:
            return attempt == 0 ? .reopen : (attempt == 1 ? again : .giveUp)
        }
    }

    /// Pause before the attempt (seconds): quick at first, patient later (a server that answers 429 / 5xx gets more room).
    static func delay(kind: Kind, attempt: Int, httpStatus: Int) -> Double {
        if kind == .liveWindow || kind == .decoder || kind == .audio || kind == .parsing { return attempt == 0 ? 0.3 : 1.2 }
        let busy = kind == .httpStatus && (httpStatus == 429 || httpStatus >= 500)
        let steps: [Double] = busy ? [3, 8, 20, 30] : [0.8, 3, 6, 12]
        return steps[min(attempt, steps.count - 1)]
    }

    /// The quality ceiling one step below `current` (0 = none set): 1080 → 720 → 480; 0 = cannot go lower.
    static func lowerCeiling(_ current: Int) -> Int {
        if current <= 0 || current > 1080 { return 1080 }
        if current > 720 { return 720 }
        if current > 480 { return 480 }
        return 0
    }

    static func statusText(_ action: Action, attempt: Int) -> String {
        switch action {
        case .waitNetwork: return "لا يوجد اتصال — يكمل التشغيل تلقائياً عند عودته"
        case .liveEdge: return "العودة إلى البث المباشر…"
        case .lowerQualityReopen: return "خفض الجودة لتناسب جهازك…"
        case .reopenSource: return "تجديد رابط البث… (" + String(attempt + 1) + ")"
        default: return "انقطع البث — جارٍ الإصلاح… (" + String(attempt + 1) + ")"
        }
    }

    static func giveUpText(_ kind: Kind, httpStatus: Int) -> String {
        switch kind {
        case .httpStatus:
            if httpStatus == 404 || httpStatus == 410 { return "ملف " + ContentNoun.name + " لم يعد موجوداً على الخادم." }
            if httpStatus == 401 || httpStatus == 403 { return "خادم المشاهدة يرفض الطلب (انتهت صلاحية الرابط أو المحتوى غير متاح في منطقتك)." }
            return "خادم المشاهدة لا يستجيب الآن (" + String(httpStatus) + "). حاول بعد قليل."
        case .network: return "الاتصال بالخادم ينقطع باستمرار. تحقق من الشبكة ثم أعد المحاولة."
        case .parsing: return "ملف " + ContentNoun.name + " تالف أو بصيغة لا يدعمها المشغل."
        case .decoder: return "جهازك لا يستطيع تشغيل " + ContentNoun.name + " بأي جودة متاحة."
        case .audio: return "تعذر تشغيل الصوت على هذا الجهاز. أغلق التطبيقات التي تستخدم الصوت ثم أعد المحاولة."
        case .stall: return "سرعة الاتصال لا تكفي لتشغيل " + ContentNoun.name + " الآن."
        default: return "تعذر تشغيل " + ContentNoun.name + " بعد عدة محاولات إصلاح."
        }
    }
}

// MARK: - smart playback (CigramSmartPlayback)

enum SmartPlayback {
    struct Conditions {
        var constrained = false      // Low Data Mode on the current network
        var expensive = false        // cellular / hotspot
        var lowPower = false         // Low Power Mode
        var charging = false
        var batteryPct = 100
        /// ProcessInfo.ThermalState raw value: 0 nominal … 3 critical.
        var thermal = 0
    }

    struct Decision {
        /// 0 = no cap from the conditions.
        let maxHeight: Int
        let reason: String
    }

    /// The conditions of the moment decide a cap on the AUTOMATIC quality; a quality picked by hand always wins.
    static func decide(_ c: Conditions) -> Decision {
        if c.thermal >= 3 { return Decision(maxHeight: 480, reason: "الجهاز ساخن جداً: الجودة 480p لحمايته") }
        if c.thermal == 2 { return Decision(maxHeight: 720, reason: "الجهاز ساخن: الجودة 720p حتى يبرد") }
        if c.expensive && c.constrained { return Decision(maxHeight: 720, reason: "توفير البيانات مفعّل: الجودة 720p") }
        if c.lowPower { return Decision(maxHeight: 720, reason: "وضع توفير البطارية: الجودة 720p") }
        if !c.charging && c.batteryPct > 0 && c.batteryPct < 15 { return Decision(maxHeight: 720, reason: "البطارية منخفضة: الجودة 720p") }
        return Decision(maxHeight: 0, reason: "")
    }

    // per-series memory of the speed ---------------------------------------------------------

    /// The key of a series ("" for a film: films use the global speed).
    static func seriesKey(isSeries: Bool, id: String, title: String) -> String {
        if !isSeries { return "" }
        let k = id.trimmingCharacters(in: .whitespaces).isEmpty ? title.trimmingCharacters(in: .whitespaces).lowercased() : id.trimmingCharacters(in: .whitespaces)
        return k.isEmpty ? "" : "cg.speed." + k
    }

    static func speed(forKey key: String, fallback: Float) -> Float {
        if key.isEmpty { return fallback }
        let v = UserDefaults.standard.float(forKey: key)
        return v >= 0.5 && v <= 2 ? v : fallback
    }

    static func rememberSpeed(key: String, speed: Float) {
        if key.isEmpty || !(speed >= 0.5 && speed <= 2) { return }
        if abs(speed - 1) < 0.01 { UserDefaults.standard.removeObject(forKey: key) } else { UserDefaults.standard.set(speed, forKey: key) }
    }
}

// MARK: - the player's own settings (the Android "cigram_player_prefs")

enum PlayerPrefs {
    private static let d = UserDefaults.standard

    /// The best quality the viewer wants: 0 = automatic (by the connection), else 720, 1080, 1440 (2K) or 2160 (4K). A CAP: the player
    /// still picks what the connection can carry, up to this height.
    static var preferredHeight: Int {
        get { let v = d.integer(forKey: "cg.pl.prefq"); return [0, 720, 1080, 1440, 2160].contains(v) ? v : 0 }
        set { d.set(newValue, forKey: "cg.pl.prefq") }
    }

    static func qualityName(_ h: Int) -> String {
        if h <= 0 { return "تلقائي · حسب سرعة إنترنتك" }
        if h >= 2160 { return "4K" }
        if h >= 1440 { return "2K" }
        return String(h) + "p"
    }

    /// «تخطي المشاهد المحددة» (the admin's skip_segments); on by default.
    static var skipScenes: Bool {
        get { return d.object(forKey: "cg.pl.skipscenes") == nil ? true : d.bool(forKey: "cg.pl.skipscenes") }
        set { d.set(newValue, forKey: "cg.pl.skipscenes") }
    }
    /// «بدء الحلقة التالية تلقائياً»; on by default.
    static var autoNext: Bool {
        get { return d.object(forKey: "cg.pl.autonext") == nil ? true : d.bool(forKey: "cg.pl.autonext") }
        set { d.set(newValue, forKey: "cg.pl.autonext") }
    }
    /// «تخطي المقدمة تلقائياً».
    static var autoSkipIntro: Bool {
        get { return d.bool(forKey: "cg.pl.autoskip") }
        set { d.set(newValue, forKey: "cg.pl.autoskip") }
    }
    static var speed: Float {
        get { let v = d.float(forKey: "cg.pl.speed"); return v >= 0.5 && v <= 2 ? v : 1 }
        set { d.set(newValue, forKey: "cg.pl.speed") }
    }
    static var subtitlesOn: Bool {
        get { return d.object(forKey: "cg.pl.subs") == nil ? true : d.bool(forKey: "cg.pl.subs") }
        set { d.set(newValue, forKey: "cg.pl.subs") }
    }
    /// 0 small … 3 largest.
    static var subtitleSize: Int {
        get { return d.object(forKey: "cg.pl.subsize") == nil ? 1 : min(3, max(0, d.integer(forKey: "cg.pl.subsize"))) }
        set { d.set(newValue, forKey: "cg.pl.subsize") }
    }
    /// 0 fit, 1 fill (crop), 2 stretch.
    static var resize: Int {
        get { return min(2, max(0, d.integer(forKey: "cg.pl.resize"))) }
        set { d.set(newValue, forKey: "cg.pl.resize") }
    }
    /// seconds of the double-tap / skip buttons.
    static var seekStep: Int {
        get { let v = d.integer(forKey: "cg.pl.step"); return [5, 10, 15, 30].contains(v) ? v : 10 }
        set { d.set(newValue, forKey: "cg.pl.step") }
    }
    /// Sleep timer minutes (0 = off): a one-night switch, it turns itself off after it fired.
    static var sleepMinutes: Int {
        get { return d.integer(forKey: "cg.pl.sleep") }
        set { d.set(newValue, forKey: "cg.pl.sleep") }
    }
    static func subtitleDelay(forKey key: String) -> Double { return d.double(forKey: "cg.pl.subdelay." + key) }
    static func setSubtitleDelay(_ v: Double, forKey key: String) {
        if abs(v) < 0.001 { d.removeObject(forKey: "cg.pl.subdelay." + key) } else { d.set(v, forKey: "cg.pl.subdelay." + key) }
    }
}

// MARK: - where the viewer stopped (CigramResume / the Android watch progress)

final class PlaybackStore {
    static let shared = PlaybackStore()

    struct Entry: Codable {
        var pos: Double
        var dur: Double
        var finished: Bool
        var at: Double
    }

    /// Kept in the history database (HistoryDB), per account; a small memory copy of the rows read so far.
    private var cache: [String: Entry] = [:]
    private var cacheAccount = ""

    private init() {}

    static func key(show: Show, episode: CatalogItem?) -> String {
        if let e = episode { return show.key + "|" + String(e.season) + "|" + String(e.episode) }
        return show.key
    }

    private func accountSwitch() -> String {
        let account = HistoryDB.accountId
        if account != cacheAccount {
            cache = [:]
            cacheAccount = account
        }
        return account
    }

    func entry(_ key: String) -> Entry? {
        let account = accountSwitch()
        if let e = cache[key] { return e }
        guard let r = HistoryDB.shared.progress(account: account, key: key) else { return nil }
        let e = Entry(pos: r.pos, dur: r.dur, finished: r.finished, at: r.at)
        cache[key] = e
        return e
    }

    func isFinished(_ key: String) -> Bool { return entry(key)?.finished ?? false }

    /// Seconds to resume from, or 0 when nothing useful is saved (finished, too early, or too close to the end).
    func resume(_ key: String) -> Double {
        guard let e = entry(key), !e.finished else { return 0 }
        if e.pos < 5 { return 0 }
        if e.dur > 0 && e.dur - e.pos <= 15 { return 0 }
        return e.pos
    }

    func save(_ key: String, pos: Double, dur: Double) {
        if key.isEmpty || !pos.isFinite || pos < 0 { return }
        let old = entry(key)
        let d = dur.isFinite && dur > 0 ? dur : (old?.dur ?? 0)
        let done = d > 0 && (d - pos <= 30 || pos / d >= 0.95)
        write(key, Entry(pos: pos, dur: d, finished: done, at: Date().timeIntervalSince1970))
    }

    func markFinished(_ key: String) {
        var e = entry(key) ?? Entry(pos: 0, dur: 0, finished: true, at: 0)
        e.finished = true
        e.at = Date().timeIntervalSince1970
        write(key, e)
    }

    func clearAll() {
        let account = accountSwitch()
        cache = [:]
        HistoryDB.shared.clearProgress(account: account)
    }

    private func write(_ key: String, _ e: Entry) {
        let account = accountSwitch()
        cache[key] = e
        HistoryDB.shared.saveProgress(account: account, key: key, row: ProgressRow(pos: e.pos, dur: e.dur, finished: e.finished, at: e.at))
    }
}

// MARK: - HLS text (CigramEmbedResolver: classify / joined master / qualities)

struct HLSVariant {
    let bandwidth: Int
    let width: Int
    let height: Int
    let url: String
}

enum SegmentContent: Int {
    case unknown = 0, muxed = 1, video = 2, audio = 3

    var name: String {
        switch self {
        case .unknown: return "unknown"
        case .muxed: return "video+audio"
        case .video: return "VIDEO ONLY"
        case .audio: return "AUDIO ONLY"
        }
    }
}

enum HLS {
    static func isMaster(_ text: String) -> Bool { return text.contains("#EXT-X-STREAM-INF") }
    static func isMedia(_ text: String) -> Bool { return text.contains("#EXTINF") }

    private static func attribute(_ line: String, _ name: String) -> String? {
        guard let r = line.range(of: name + "=") else { return nil }
        var rest = String(line[r.upperBound...])
        if rest.hasPrefix("\"") {
            rest.removeFirst()
            if let q = rest.firstIndex(of: "\"") { return String(rest[rest.startIndex..<q]) }
            return rest
        }
        if let c = rest.firstIndex(of: ",") { return String(rest[rest.startIndex..<c]) }
        return rest
    }

    /// Every quality of a master playlist (absolute links), lowest bandwidth first.
    static func variants(master text: String, base: String) -> [HLSVariant] {
        let lines = text.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        var out: [HLSVariant] = []
        var i = 0
        while i < lines.count {
            let l = lines[i]
            if l.hasPrefix("#EXT-X-STREAM-INF:") {
                var uri: String?
                var j = i + 1
                while j < lines.count {
                    if !lines[j].isEmpty && !lines[j].hasPrefix("#") { uri = lines[j]; break }
                    j += 1
                }
                if let u = uri, let abs = StreamLink.absolute(base: base, rel: u) {
                    let bw = Int(attribute(l, "BANDWIDTH") ?? "") ?? 0
                    var w = 0, h = 0
                    if let res = attribute(l, "RESOLUTION") {
                        let p = res.lowercased().components(separatedBy: "x")
                        if p.count == 2 { w = Int(p[0]) ?? 0; h = Int(p[1]) ?? 0 }
                    }
                    out.append(HLSVariant(bandwidth: bw, width: w, height: h, url: abs))
                }
                i = j
            }
            i += 1
        }
        return out.sorted { $0.bandwidth < $1.bandwidth }
    }

    /// The first piece (and the init section, when there is one) of a media playlist.
    static func firstSegment(_ text: String) -> (map: String?, segment: String?) {
        var map: String?
        var seg: String?
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("#EXT-X-MAP:") && map == nil {
                if let u = attribute(line, "URI") { map = u }
            } else if !line.isEmpty && !line.hasPrefix("#") && seg == nil {
                seg = line
            }
        }
        return (map, seg)
    }

    /// The first variant's link of a master playlist (relative or absolute), or nil.
    static func firstVariantURI(_ text: String) -> String? {
        let lines = text.components(separatedBy: "\n")
        for i in 0..<lines.count where lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("#EXT-X-STREAM-INF") {
            var j = i + 1
            while j < lines.count {
                let l = lines[j].trimmingCharacters(in: .whitespacesAndNewlines)
                if !l.isEmpty && !l.hasPrefix("#") { return l }
                j += 1
            }
            return nil
        }
        return nil
    }

    /// Whether the pieces are audio by their NAME alone (no request needed).
    static func isAudioName(_ ref: String) -> Bool {
        var path = ref.lowercased()
        if let q = path.firstIndex(of: "?") { path = String(path[path.startIndex..<q]) }
        for e in [".aac", ".m4a", ".mp3", ".ac3", ".ec3", ".opus"] where path.hasSuffix(e) { return true }
        return false
    }

    /// Reads a piece and says whether it carries picture, sound, or both. Works on pieces disguised as pictures (a fake
    /// .png / .jpg header in front of the real data): the real container is searched for anywhere in the first bytes.
    static func analyze(_ b: [UInt8]) -> SegmentContent {
        let n = b.count
        if n < 16 { return .unknown }
        // MPEG-TS: three sync bytes 188 apart, anywhere
        var start = -1
        var k = 0
        while k + 376 < n && k < 65536 {
            if b[k] == 0x47 && b[k + 188] == 0x47 && b[k + 376] == 0x47 { start = k; break }
            k += 1
        }
        if start >= 0 {
            var video = false, audio = false
            var p = start
            while p + 188 <= n {
                if b[p] != 0x47 { break }
                let unitStart = (b[p + 1] & 0x40) != 0
                let afc = Int((b[p + 3] >> 4) & 0x3)
                if !unitStart || (afc & 0x1) == 0 { p += 188; continue }
                var off = p + 4
                if (afc & 0x2) != 0 { off += 1 + Int(b[p + 4]) }
                if off + 4 > p + 188 { p += 188; continue }
                if b[off] == 0 && b[off + 1] == 0 && b[off + 2] == 1 {
                    let id = Int(b[off + 3])
                    if id >= 0xE0 && id <= 0xEF { video = true } else if (id >= 0xC0 && id <= 0xDF) || id == 0xBD { audio = true }
                }
                if video && audio { break }
                p += 188
            }
            if video && audio { return .muxed }
            if video { return .video }
            if audio { return .audio }
            return .unknown
        }
        // fragmented / plain MP4 (init section or piece)
        let head = Array(b.prefix(65536))
        let s = String(bytes: head, encoding: .isoLatin1) ?? ""
        if s.contains("ftyp") || s.contains("styp") || s.contains("moov") || s.contains("moof") {
            let audio = s.contains("mp4a") || s.contains("ec-3") || s.contains("ac-3") || s.contains("Opus") || s.contains("soun")
            let video = s.contains("avc1") || s.contains("avc3") || s.contains("hvc1") || s.contains("hev1") || s.contains("av01")
                || s.contains("vp09") || s.contains("vide")
            if video && audio { return .muxed }
            if video { return .video }
            if audio { return .audio }
            return .unknown
        }
        // packed audio (AAC / MP3 with an ID3 tag, or a bare ADTS frame)
        if b[0] == 0x49 && b[1] == 0x44 && b[2] == 0x33 { return .audio }
        if b[0] == 0xFF && (b[1] & 0xF0) == 0xF0 { return .audio }
        return .unknown
    }

    // MARK: playlists written by the app

    static let audioGroupLine = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"cg\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\""

    /// A master playlist that joins one picture playlist and one separate audio playlist.
    static func joinedMaster(videoURL: String, audioURL: String) -> String {
        return "#EXTM3U\n#EXT-X-VERSION:6\n" + audioGroupLine + audioURL + "\"\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,AUDIO=\"cg\"\n" + videoURL + "\n"
    }

    /// A copy of a sound-less master (every quality kept) whose qualities all use the separate sound playlist.
    static func joinedMaster(from masterText: String, masterURL: String, audioURL: String) -> String? {
        var out = "#EXTM3U\n#EXT-X-VERSION:6\n" + audioGroupLine + audioURL + "\"\n"
        let lines = masterText.components(separatedBy: "\n")
        var count = 0
        var i = 0
        while i < lines.count {
            let l = lines[i].trimmingCharacters(in: .whitespacesAndNewlines)
            if l.hasPrefix("#EXT-X-STREAM-INF:") {
                var attrs = l
                if let rg = attrs.range(of: ",?AUDIO=\"[^\"]*\"", options: .regularExpression) { attrs.removeSubrange(rg) }
                attrs = attrs.replacingOccurrences(of: ":,", with: ":")
                var uri: String?
                var j = i + 1
                while j < lines.count {
                    let u = lines[j].trimmingCharacters(in: .whitespacesAndNewlines)
                    if !u.isEmpty && !u.hasPrefix("#") { uri = u; break }
                    j += 1
                }
                if let u = uri, let abs = StreamLink.absolute(base: masterURL, rel: u) {
                    out += attrs + (attrs.hasSuffix(":") ? "" : ",") + "AUDIO=\"cg\"\n" + abs + "\n"
                    count += 1
                }
                i = j
            }
            i += 1
        }
        return count == 0 ? nil : out
    }

    static func bandwidth(forHeight h: Int) -> Int {
        if h >= 2160 { return 18_000_000 }
        if h >= 1440 { return 10_000_000 }
        if h >= 1080 { return 6_000_000 }
        if h >= 720 { return 3_000_000 }
        if h >= 480 { return 1_500_000 }
        if h >= 360 { return 900_000 }
        return 500_000
    }

    static let ladder = [2160, 1440, 1080, 720, 480, 360, 240]

    /// A copy of a master playlist where every link is absolute and only the quality `keepURL` stays (nil keeps all).
    /// Playing it forces exactly that quality (the sound / subtitle renditions are kept).
    static func filteredMaster(_ text: String, base: String, keepURL: String?) -> String {
        let lines = text.components(separatedBy: "\n")
        var out: [String] = []
        var i = 0
        while i < lines.count {
            let raw = lines[i].trimmingCharacters(in: .whitespacesAndNewlines)
            if raw.hasPrefix("#EXT-X-STREAM-INF:") {
                var j = i + 1
                var uri: String?
                while j < lines.count {
                    let u = lines[j].trimmingCharacters(in: .whitespacesAndNewlines)
                    if !u.isEmpty && !u.hasPrefix("#") { uri = u; break }
                    j += 1
                }
                if let u = uri, let abs = StreamLink.absolute(base: base, rel: u) {
                    if keepURL == nil || keepURL == abs {
                        out.append(raw)
                        out.append(abs)
                    }
                }
                i = j + 1
                continue
            }
            if raw.hasPrefix("#EXT-X-I-FRAME-STREAM-INF") { i += 1; continue }
            if !raw.isEmpty { out.append(absolutizeURIAttribute(raw, base: base)) }
            i += 1
        }
        return out.joined(separator: "\n") + "\n"
    }

    private static func absolutizeURIAttribute(_ line: String, base: String) -> String {
        guard line.hasPrefix("#"), let r = line.range(of: "URI=\"") else { return line }
        let after = line[r.upperBound...]
        guard let q = after.firstIndex(of: "\"") else { return line }
        let uri = String(after[after.startIndex..<q])
        guard let abs = StreamLink.absolute(base: base, rel: uri) else { return line }
        return String(line[line.startIndex..<r.upperBound]) + abs + String(after[q...])
    }

    /// One master playlist with every verified resolution of a title (names like video_720p.m3u8) and the separate sound.
    static func qualityMaster(template: String, heights: [Int], audioURL: String?) -> String? {
        var out = "#EXTM3U\n#EXT-X-VERSION:6\n"
        if let a = audioURL { out += audioGroupLine + a + "\"\n" }
        var found = 0
        for h in heights.sorted(by: >) {
            let w = (h * 16 / 9) & ~1
            out += "#EXT-X-STREAM-INF:BANDWIDTH=" + String(bandwidth(forHeight: h)) + ",RESOLUTION=" + String(w) + "x" + String(h)
            out += (audioURL != nil ? ",AUDIO=\"cg\"" : "") + "\n" + template.replacingOccurrences(of: "{H}", with: String(h)) + "\n"
            found += 1
        }
        return found == 0 ? nil : out
    }

    private static let heightInName = try! NSRegularExpression(pattern: "(?<![0-9A-Za-z])(\\d{3,4})p(?![0-9A-Za-z])")

    /// "…/video_720p.m3u8" → "…/video_{H}p.m3u8" (nil when no name shows a resolution).
    static func heightTemplate(from urls: [String], excluding audio: String?) -> String? {
        for u in urls {
            if let a = audio, u == a { continue }
            let low = u.lowercased()
            if low.contains("subtitle") || low.contains("track_") || low.contains("audio") { continue }
            var path = u
            var tail = ""
            if let q = u.firstIndex(of: "?") { path = String(u[u.startIndex..<q]); tail = String(u[q...]) }
            let ns = path as NSString
            let ms = heightInName.matches(in: path, options: [], range: NSRange(location: 0, length: ns.length))
            guard let last = ms.last else { continue }
            let r = last.range(at: 1)
            return ns.substring(to: r.location) + "{H}" + ns.substring(from: r.location + r.length) + tail
        }
        return nil
    }

    /// The usual name next to a sound playlist: "…/audio.m3u8" → "…/video_{H}p.m3u8".
    static func templateBesideAudio(_ audioURL: String) -> String? {
        var path = audioURL
        var tail = ""
        if let q = audioURL.firstIndex(of: "?") { path = String(audioURL[audioURL.startIndex..<q]); tail = String(audioURL[q...]) }
        guard let slash = path.lastIndex(of: "/"), path.distance(from: path.startIndex, to: slash) > 8 else { return nil }
        return String(path[path.startIndex...slash]) + "video_{H}p.m3u8" + tail
    }
}

// MARK: - plain HTTP for the resolver (background threads only)

enum StreamNet {
    struct Response {
        let status: Int
        let headers: [String: String]
        let data: Data
        let finalURL: String

        func header(_ name: String) -> String {
            let w = name.lowercased()
            for (k, v) in headers where k.lowercased() == w { return v }
            return ""
        }

        var text: String {
            return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
        }
    }

    /// Reads at most `maxBytes` and stops the transfer (a server that ignores Range must not stream a whole film in).
    private final class BoundedFetch: NSObject, URLSessionDataDelegate {
        var data = Data()
        var response: HTTPURLResponse?
        let maxBytes: Int
        let done = DispatchSemaphore(value: 0)

        init(maxBytes: Int) { self.maxBytes = maxBytes }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            self.response = response as? HTTPURLResponse
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive d: Data) {
            data.append(d)
            if data.count >= maxBytes { dataTask.cancel() }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { done.signal() }
    }

    /// Blocking GET. nil = no answer at all (network failure). Never call it on the main thread.
    static func get(_ url: String, headers: [String: String] = [:], range: String? = nil, maxBytes: Int = 1_500_000,
                    timeout: TimeInterval = 10) -> Response? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        req.setValue(StreamLink.userAgent, forHTTPHeaderField: "User-Agent")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let r = range { req.setValue(r, forHTTPHeaderField: "Range") }
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout * 2
        cfg.httpShouldSetCookies = false
        let f = BoundedFetch(maxBytes: maxBytes)
        let session = URLSession(configuration: cfg, delegate: f, delegateQueue: nil)
        let task = session.dataTask(with: req)
        task.resume()
        if f.done.wait(timeout: .now() + timeout * 2 + 2) == .timedOut {
            task.cancel()
            session.invalidateAndCancel()
            return nil
        }
        session.invalidateAndCancel()
        guard let http = f.response else { return nil }
        var h: [String: String] = [:]
        for (k, v) in http.allHeaderFields { h[String(describing: k)] = String(describing: v) }
        return Response(status: http.statusCode, headers: h, data: f.data, finalURL: http.url?.absoluteString ?? url)
    }

    /// The status the server gives this address (-1 = no answer).
    static func status(_ url: String, headers: [String: String] = [:]) -> Int {
        guard let r = get(url, headers: headers, range: "bytes=0-511", maxBytes: 4096, timeout: 6) else { return -1 }
        return r.status
    }
}

// MARK: - finding the stream in the text of a page (CigramEmbedResolver stage 1)

enum PageScrape {
    private static let media = try! NSRegularExpression(
        pattern: #"(?:https?:)?//[^\s"'<>\\)\]}]+?\.(?:m3u8|mpd|mp4)(?:\?[^\s"'<>\\)\]}]*)?"#, options: [.caseInsensitive])
    private static let iframe = try! NSRegularExpression(pattern: #"<iframe[^>]+?src\s*=\s*["']([^"']+)["']"#, options: [.caseInsensitive])
    private static let atob = try! NSRegularExpression(pattern: #"atob\(\s*["']([A-Za-z0-9+/=_-]{24,})["']\s*\)"#)

    static func unescape(_ s: String) -> String {
        return s.replacingOccurrences(of: "\\/", with: "/").replacingOccurrences(of: "\\u0026", with: "&")
            .replacingOccurrences(of: "\\u003d", with: "=").replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "\\u002F", with: "/").replacingOccurrences(of: "\\x2F", with: "/")
    }

    /// Best stream in the text: HLS first, then mp4 (DASH is skipped: iOS cannot play it); ads / trailers / images are skipped.
    static func pickMedia(_ text: String) -> String? {
        var hls: String?
        var mp4: String?
        let ns = text as NSString
        for m in media.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            var u = ns.substring(with: m.range)
            if u.hasPrefix("//") { u = "https:" + u }
            let low = u.lowercased()
            if low.contains("trailer") || low.contains("/ads/") || low.contains("preroll") || low.contains("advert")
                || low.contains("sample") || low.contains("thumbnail") { continue }
            if low.contains(".m3u8") { if hls == nil { hls = u } } else if low.contains(".mpd") { continue } else if mp4 == nil { mp4 = u }
        }
        return hls ?? mp4
    }

    static func decodedBlobs(_ text: String) -> [String] {
        var out: [String] = []
        let ns = text as NSString
        for m in atob.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            var b = ns.substring(with: m.range(at: 1)).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            while b.count % 4 != 0 { b += "=" }
            if let d = Data(base64Encoded: b), let s = String(data: d, encoding: .utf8) { out.append(unescape(s)) }
        }
        return out
    }

    static func iframes(_ text: String) -> [String] {
        var out: [String] = []
        let ns = text as NSString
        for m in iframe.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            let src = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
            if src.isEmpty || src.hasPrefix("about:") || src.hasPrefix("javascript:") { continue }
            out.append(src)
        }
        return out
    }

    /// Downloads the page (and its iframes, up to 3 levels) and looks for a stream link in plain text, JSON, escaped text
    /// and atob() blobs. Blocking: background threads only.
    static func scrape(page: String, referer: String, depth: Int = 0, seen: inout Set<String>) -> String? {
        if depth > 3 || !seen.insert(page).inserted { return nil }
        var h: [String: String] = ["Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8", "Accept-Language": "en-US,en;q=0.9"]
        let o = StreamLink.origin(of: referer)
        if !o.isEmpty { h["Referer"] = o + "/" }
        guard let r = StreamNet.get(page, headers: h, maxBytes: 1_500_000, timeout: 10), r.status >= 200, r.status < 300 else { return nil }
        let text = unescape(r.text)
        if text.isEmpty { return nil }
        if let direct = pickMedia(text) { return direct }
        for blob in decodedBlobs(text) { if let hit = pickMedia(blob) { return hit } }
        var followed = 0
        for src in iframes(text) {
            if followed >= 4 { break }
            guard let abs = StreamLink.absolute(base: page, rel: src) else { continue }
            followed += 1
            if let hit = scrape(page: abs, referer: page, depth: depth + 1, seen: &seen) { return hit }
        }
        return nil
    }
}

// MARK: - subtitles (SRT / VTT)

struct SubtitleCue {
    let start: Double
    let end: Double
    let text: String
}

final class SubtitleDocument {
    let cues: [SubtitleCue]
    /// A name to keep the viewer's delay for this file ("" = none).
    let key: String

    init(cues: [SubtitleCue], key: String) {
        self.cues = cues.sorted { $0.start < $1.start }
        self.key = key
    }

    /// The cue(s) showing at `t` (binary search for the last cue that started, then a short look back for overlaps).
    func text(at t: Double) -> String {
        if cues.isEmpty || t < 0 { return "" }
        var lo = 0, hi = cues.count - 1, idx = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if cues[mid].start <= t { idx = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        if idx < 0 { return "" }
        var parts: [String] = []
        var i = idx
        var looked = 0
        while i >= 0 && looked < 6 {
            if cues[i].end > t { parts.insert(cues[i].text, at: 0) }
            i -= 1
            looked += 1
        }
        return parts.joined(separator: "\n")
    }
}

enum SubtitleParser {
    /// Decodes the bytes of a subtitle file: UTF-8 (with / without BOM), UTF-16 (BOM), else Windows-1256 (Arabic), else Latin-1.
    static func decode(_ data: Data) -> String {
        if data.count >= 2 {
            let b0 = data[data.startIndex], b1 = data[data.startIndex + 1]
            if (b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF) {
                if let s = String(data: data, encoding: .utf16) { return s }
            }
        }
        var d = data
        if d.count >= 3 && d[d.startIndex] == 0xEF && d[d.startIndex + 1] == 0xBB && d[d.startIndex + 2] == 0xBF { d = d.subdata(in: (d.startIndex + 3)..<d.endIndex) }
        if let s = String(data: d, encoding: .utf8) { return s }
        let cf = CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.windowsArabic.rawValue))
        if let s = String(data: d, encoding: String.Encoding(rawValue: cf)) { return s }
        return String(data: d, encoding: .isoLatin1) ?? ""
    }

    /// "00:01:02,500" / "01:02.500" / "1:02:03.4" → seconds.
    static func time(_ raw: String) -> Double? {
        let t = raw.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        let parts = t.components(separatedBy: ":")
        if parts.count < 2 || parts.count > 3 { return nil }
        var total = 0.0
        for p in parts {
            guard let v = Double(p.trimmingCharacters(in: .whitespaces)) else { return nil }
            total = total * 60 + v
        }
        return total
    }

    private static func clean(_ s: String) -> String {
        var t = s
        t = t.replacingOccurrences(of: "\r", with: "")
        t = t.replacingOccurrences(of: "<br>", with: "\n").replacingOccurrences(of: "<br/>", with: "\n").replacingOccurrences(of: "<br />", with: "\n")
        t = t.replacingOccurrences(of: "\\{[^}]*\\}", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ").replacingOccurrences(of: "&quot;", with: "\"")
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Parses SRT and WebVTT alike (a cue = an optional id line, "a --> b", then its text lines).
    static func parse(_ raw: String, key: String) -> SubtitleDocument {
        let text = raw.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var cues: [SubtitleCue] = []
        for block in text.components(separatedBy: "\n\n") {
            let lines = block.components(separatedBy: "\n")
            var at = -1
            for (i, l) in lines.enumerated() where l.contains("-->") { at = i; break }
            if at < 0 { continue }
            let sides = lines[at].components(separatedBy: "-->")
            if sides.count < 2 { continue }
            let endPart = sides[1].trimmingCharacters(in: .whitespaces).components(separatedBy: " ").first ?? ""
            guard let a = time(sides[0]), let b = time(endPart), b > a else { continue }
            let body = clean(lines[(at + 1)...].joined(separator: "\n"))
            if body.isEmpty { continue }
            cues.append(SubtitleCue(start: a, end: b, text: body))
        }
        return SubtitleDocument(cues: cues, key: key)
    }
}
