import UIKit
import Foundation
import ImageIO

/// Same backend the Android app uses.
enum Backend {
    static let admin = "https://cigram-admin-api.wwq-mixtv.workers.dev"
    static let moviesURL = URL(string: admin + "/movies")!
    static let sectionsURL = URL(string: admin + "/home-sections")!
    /// movies.json in the public bucket: a HEAD answers its ETag in ~300 bytes (the change check, see CatalogStore.pulse).
    static let probeURL = URL(string: "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev/movies.json")!
    /// Fallback embed servers (the record's own "video" link is always tried first). %@ = TMDB id.
    static let movieServers = ["https://vidbolt.pro/movie/%@", "https://vidfast.vc/movie/%@",
                               "https://vidnest.fun/movie/%@", "https://videm.xyz/movie/%@"]
    static let tvServers = ["https://vidbolt.pro/tv/%@/%d/%d", "https://vidfast.vc/tv/%@/%d/%d",
                            "https://vidnest.fun/tv/%@/%d/%d", "https://videm.xyz/tv/%@/%d/%d"]
    /// Every home row shows at most this many posters (the full list is one tap away on «المزيد»).
    static let homeRowMax = 11
}

// MARK: - «2025 ¦ كوميديا | دراما»

enum MetaLine {
    private static let separators = CharacterSet(charactersIn: ",،؛;|\u{00A6}/•·\n")

    static func genres(_ category: String) -> [String] {
        var out: [String] = []
        var seen = Set<String>()
        var parts: [String] = []
        for chunk in category.components(separatedBy: separators) {
            parts.append(contentsOf: chunk.components(separatedBy: " - "))
        }
        for p in parts {
            let g = p.trimmingCharacters(in: .whitespacesAndNewlines)
            if g.isEmpty { continue }
            if seen.insert(g.lowercased()).inserted { out.append(g) }
        }
        return out
    }

    static func line(year: String, category: String) -> String {
        var y = year.trimmingCharacters(in: .whitespaces)
        if y == "0" || y.lowercased() == "null" { y = "" }
        if y.count > 4, let first = y.split(separator: "-").first, first.count == 4 { y = String(first) }
        let c = genres(category).joined(separator: " | ")
        if y.isEmpty { return c }
        if c.isEmpty { return y }
        return y + " \u{00A6} " + c
    }
}

// MARK: - Records

struct CatalogItem {
    let id: String
    let type: String
    let title: String
    let seriesId: String
    let poster: String
    let seriesPoster: String
    let backdrop: String
    let overview: String
    let year: String
    let category: String
    let imdb: String
    let tmdb: String
    let video: String
    let sectionId: String
    let sectionName: String
    let season: Int
    let episode: Int
    let created: Double
    let originalTitle: String

    var isEpisode: Bool { return type == "episode" || (!seriesId.isEmpty && type != "series" && episode > 0) }
    var isSeriesHeader: Bool { return type == "series" }

    init(_ r: [String: Any]) {
        func s(_ keys: String...) -> String {
            for k in keys {
                if let v = r[k] as? String {
                    let t = v.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty && t.lowercased() != "null" { return t }
                }
                if let n = r[k] as? NSNumber { return n.stringValue }
            }
            return ""
        }
        id = s("id")
        type = s("type", "content_type", "media_type").lowercased()
        title = s("title", "name", "title_ar", "title_en")
        seriesId = s("series_id")
        poster = s("poster", "season_poster", "image")
        seriesPoster = s("series_poster", "poster")
        backdrop = s("backdrop", "landscape_poster", "thumb")
        overview = s("description", "overview", "story", "plot")
        year = s("year", "release_year")
        category = s("category", "genre", "genres")
        imdb = s("imdb", "rating", "imdb_rating")
        tmdb = s("tmdb", "tmdb_id")
        video = s("video", "link", "video_url", "movie_url")
        sectionId = s("section_id")
        sectionName = s("section_name")
        season = Int(s("season")) ?? 0
        episode = Int(s("episode")) ?? 0
        if let n = r["created_at_ms"] as? NSNumber { created = n.doubleValue } else { created = Double(s("created_at_ms")) ?? 0 }
        originalTitle = s("original_title")
    }
}

/// What a poster stands for: a film, or a series with all its episodes.
final class Show {
    let key: String
    var head: CatalogItem
    var episodes: [CatalogItem] = []
    let isSeries: Bool
    var newest: Double

    init(key: String, head: CatalogItem, isSeries: Bool) {
        self.key = key
        self.head = head
        self.isSeries = isSeries
        self.newest = head.created
    }

    var title: String { return head.title.isEmpty ? (isSeries ? "مسلسل" : "فيلم") : head.title }
    var posterURL: URL? {
        let p = isSeries ? (head.seriesPoster.isEmpty ? head.poster : head.seriesPoster) : head.poster
        return Show.image(p, size: "w500")
    }
    var backdropURL: URL? { return Show.image(head.backdrop.isEmpty ? head.poster : head.backdrop, size: "w1280") }
    var meta: String { return MetaLine.line(year: head.year, category: head.category) }
    var sectionId: String { return head.sectionId.isEmpty ? (episodes.first?.sectionId ?? "") : head.sectionId }
    var sectionName: String { return head.sectionName.isEmpty ? (episodes.first?.sectionName ?? "") : head.sectionName }
    var contentId: String { return isSeries ? key : head.id }
    var genres: [String] { return MetaLine.genres(head.category) }

    /// «أعمال مشابهة»: titles that share this one's first genre (newest first), at most one home row.
    func related() -> [Show] {
        guard let g = genres.first else { return [] }
        return Array(CatalogStore.shared.shows.filter { $0.key != key && $0.genres.contains(g) }.prefix(Backend.homeRowMax))
    }
    var seasons: [Int] { return Array(Set(episodes.map { $0.season })).sorted() }

    /// The link the player opens first: the film's own, or the newest episode's.
    var watchLink: String { return isSeries ? (episodes.last?.video ?? "") : head.video }

    static func image(_ raw: String, size: String) -> URL? {
        var u = raw.trimmingCharacters(in: .whitespaces)
        if u.isEmpty { return nil }
        if u.hasPrefix("//") { u = "https:" + u }
        if !u.hasPrefix("http") { u = "https://image.tmdb.org/t/p/" + size + (u.hasPrefix("/") ? u : "/" + u) }
        if let r = u.range(of: "image.tmdb.org/t/p/") {
            let rest = u[r.upperBound...]
            if let slash = rest.firstIndex(of: "/") { u = String(u[..<r.upperBound]) + size + String(rest[slash...]) }
        }
        return URL(string: u.replacingOccurrences(of: " ", with: "%20"))
    }
}

struct HomeSection {
    let id: String
    let name: String
    let shows: [Show]
}

// MARK: - Parsing + grouping (background queue)

enum CatalogueParser {
    static func records(_ data: Data) -> [[String: Any]] {
        var d = data
        if d.starts(with: [0xEF, 0xBB, 0xBF]) { d = d.dropFirst(3) }
        guard let json = try? JSONSerialization.jsonObject(with: d, options: [.allowFragments]) else { return [] }
        if let arr = json as? [[String: Any]] { return arr }
        if let obj = json as? [String: Any] {
            for k in ["movies", "items", "records", "data", "catalogue", "catalog"] {
                if let arr = obj[k] as? [[String: Any]] { return arr }
            }
        }
        return []
    }

    static func shows(_ records: [[String: Any]]) -> [Show] {
        var movies: [Show] = []
        var series: [String: Show] = [:]
        var orphanEpisodes: [String: [CatalogItem]] = [:]
        for r in records {
            let it = CatalogItem(r)
            if it.title.isEmpty && it.seriesId.isEmpty { continue }
            if it.isSeriesHeader {
                let key = it.seriesId.isEmpty ? it.id : it.seriesId
                if let existing = series[key] { existing.head = it } else { series[key] = Show(key: key, head: it, isSeries: true) }
            } else if it.isEpisode {
                orphanEpisodes[it.seriesId, default: []].append(it)
            } else {
                movies.append(Show(key: "m:" + (it.id.isEmpty ? it.title : it.id), head: it, isSeries: false))
            }
        }
        for (sid, eps) in orphanEpisodes {
            let show: Show
            if let s = series[sid] { show = s } else if let first = eps.first {
                show = Show(key: sid, head: first, isSeries: true)   // no header record: the first episode stands in
                series[sid] = show
            } else { continue }
            show.episodes = eps.filter { !$0.video.isEmpty }.sorted { ($0.season, $0.episode) < ($1.season, $1.episode) }
            for e in eps where e.created > show.newest { show.newest = e.created }
        }
        let all = movies + Array(series.values).filter { !$0.episodes.isEmpty }
        return all.sorted { $0.newest > $1.newest }
    }

    /// The administrator's sections in the registry's order; hidden / deleted ones are left out (their titles stay in search).
    static func sections(_ shows: [Show], registry: SectionRegistry) -> [HomeSection] {
        var byId: [String: [Show]] = [:]
        var names: [String: String] = [:]
        for s in shows {
            let id = s.sectionId
            if id.isEmpty { continue }
            byId[id, default: []].append(s)
            if names[id] == nil, !s.sectionName.isEmpty { names[id] = s.sectionName }
        }
        var out: [HomeSection] = []
        if registry.exists {
            for entry in registry.entries where entry.showHome {
                guard let list = byId[entry.id], !list.isEmpty else { continue }
                out.append(HomeSection(id: entry.id, name: entry.name.isEmpty ? (names[entry.id] ?? entry.id) : entry.name,
                                       shows: Array(list.prefix(Backend.homeRowMax))))
            }
        } else {
            for (id, list) in byId.sorted(by: { ($0.value.first?.newest ?? 0) > ($1.value.first?.newest ?? 0) }) {
                out.append(HomeSection(id: id, name: names[id] ?? id, shows: Array(list.prefix(Backend.homeRowMax))))
            }
        }
        return out
    }

    static func normalize(_ s: String) -> String {
        var t = s.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: Locale(identifier: "ar"))
        let letters: [Character: Character] = ["أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"]
        var unified = ""
        for ch in t { unified.append(letters[ch] ?? ch) }
        t = unified.replacingOccurrences(of: "ـ", with: "")
        return t.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: " ")
    }
}

// MARK: - The administrator's section registry (/home-sections)

struct SectionRegistry {
    struct Entry { let id: String; let name: String; let showHome: Bool }
    var exists = false
    var entries: [Entry] = []

    static func parse(_ data: Data) -> SectionRegistry {
        var r = SectionRegistry()
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return r }
        r.exists = (obj["source"] as? String) == "registry"
        let list = (obj["sections"] as? [[String: Any]]) ?? []
        let sorted = list.sorted { (($0["order"] as? NSNumber)?.intValue ?? 0) < (($1["order"] as? NSNumber)?.intValue ?? 0) }
        r.entries = sorted.compactMap { s in
            guard let id = s["id"] as? String, !id.isEmpty else { return nil }
            let home = (s["show_home"] as? Bool ?? true) && !(s["more_only"] as? Bool ?? false)
            return Entry(id: id, name: (s["name"] as? String) ?? "", showHome: home)
        }
        return r
    }
}

// MARK: - The store

extension Notification.Name {
    static let cigramCatalogChanged = Notification.Name("cigram.catalog.changed")
}

final class CatalogStore {
    static let shared = CatalogStore()

    private(set) var shows: [Show] = []
    private(set) var sections: [HomeSection] = []
    private(set) var registry = SectionRegistry()
    private(set) var loaded = false

    private let queue = DispatchQueue(label: "cigram.catalog", qos: .userInitiated)
    private let defaults = UserDefaults.standard
    private var fetching = false
    private var lastProbe = Date.distantPast
    private var pulseTimer: Timer?

    private lazy var dir: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("Cigram", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private var cacheFile: URL { return dir.appendingPathComponent("movies.json") }
    private var sectionsFile: URL { return dir.appendingPathComponent("home-sections.json") }

    /// Saved copy first (instant Home, works offline), then the network.
    func start() {
        queue.async {
            if let d = try? Data(contentsOf: self.sectionsFile) { self.registry = SectionRegistry.parse(d) }
            if let d = try? Data(contentsOf: self.cacheFile) { self.install(CatalogueParser.records(d)) }
            self.refresh(force: false, completion: nil)
            self.refreshSections()
            DispatchQueue.main.async { self.refreshCarousel() }
        }
    }

    func show(forKey key: String) -> Show? { return shows.first { $0.key == key } }

    // MARK: the admin's carousel (/carousel-active), the big slides at the top of Home

    struct Slide {
        let show: Show
        let image: URL?
    }

    private(set) var carousel: [Slide] = []
    private var carouselItems: [[String: Any]] = []

    func refreshCarousel() {
        guard let url = URL(string: Backend.admin + "/carousel-active") else { return }
        URLSession.shared.dataTask(with: URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)) { d, _, _ in
            guard let d = d, let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
                  (o["enabled"] as? Bool) ?? true, let items = o["items"] as? [[String: Any]] else { return }
            DispatchQueue.main.async {
                self.carouselItems = items
                self.rebuildCarousel()
                NotificationCenter.default.post(name: .cigramCatalogChanged, object: nil)
            }
        }.resume()
    }

    private func rebuildCarousel() {
        var out: [Slide] = []
        for it in carouselItems {
            let id = (it["content_id"] as? String) ?? ""
            guard let found = self.show(forKey: id) ?? self.show(forKey: "m:" + id) else { continue }
            let raw = (it["landscape_poster"] as? String) ?? (it["source_backdrop"] as? String) ?? ""
            out.append(Slide(show: found, image: Show.image(raw, size: "w1280") ?? found.backdropURL))
        }
        carousel = out
    }

    func search(_ query: String) -> [Show] {
        let q = CatalogueParser.normalize(query)
        if q.isEmpty { return [] }
        return shows.filter {
            CatalogueParser.normalize($0.title).contains(q) || CatalogueParser.normalize($0.head.originalTitle).contains(q)
        }
    }

    private func install(_ records: [[String: Any]]) {
        let list = CatalogueParser.shows(records)
        guard !list.isEmpty else { return }
        let secs = CatalogueParser.sections(list, registry: registry)
        NSLog("CIGRAM_CHECK catalogue shows=%ld series=%ld sections=%ld rowMax=%ld", list.count, list.filter { $0.isSeries }.count,
              secs.count, secs.map { $0.shows.count }.max() ?? 0)
        DispatchQueue.main.async {
            self.shows = list
            self.sections = secs
            self.loaded = true
            self.rebuildCarousel()
            NotificationCenter.default.post(name: .cigramCatalogChanged, object: nil)
        }
    }

    private func regroup() {
        let list = shows
        let secs = CatalogueParser.sections(list, registry: registry)
        DispatchQueue.main.async {
            self.sections = secs
            NotificationCenter.default.post(name: .cigramCatalogChanged, object: nil)
        }
    }

    /// Downloads /movies (conditional when possible). `completion(changed)` runs on the main queue.
    func refresh(force: Bool, completion: ((Bool) -> Void)?) {
        queue.async {
            if self.fetching { DispatchQueue.main.async { completion?(false) }; return }
            self.fetching = true
            var req = URLRequest(url: Backend.moviesURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 45)
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            if !force, let etag = self.defaults.string(forKey: "cg.movies.etag") { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
            URLSession.shared.dataTask(with: req) { data, resp, _ in
                var changed = false
                defer {
                    self.queue.async { self.fetching = false }
                    DispatchQueue.main.async { completion?(changed) }
                }
                guard let http = resp as? HTTPURLResponse, http.statusCode == 200, let data = data else { return }
                let digest = CatalogStore.digest(data)
                let etag = CatalogStore.header(http, "ETag")
                if !etag.isEmpty { self.defaults.set(etag, forKey: "cg.movies.etag") }
                if digest == self.defaults.string(forKey: "cg.movies.digest") && self.loaded { return }
                let records = CatalogueParser.records(data)
                guard !records.isEmpty else { return }
                try? data.write(to: self.cacheFile, options: .atomic)
                self.defaults.set(digest, forKey: "cg.movies.digest")
                changed = true
                self.install(records)
            }.resume()
        }
    }

    func refreshSections() {
        var req = URLRequest(url: Backend.sectionsURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        URLSession.shared.dataTask(with: req) { data, resp, _ in
            guard (resp as? HTTPURLResponse)?.statusCode == 200, let data = data else { return }
            let reg = SectionRegistry.parse(data)
            guard reg.exists || !reg.entries.isEmpty else { return }
            self.queue.async {
                let old = self.registry.entries.map { $0.id + "|" + $0.name + "|" + String($0.showHome) }
                let new = reg.entries.map { $0.id + "|" + $0.name + "|" + String($0.showHome) }
                try? data.write(to: self.sectionsFile, options: .atomic)
                self.registry = reg
                if old != new { self.regroup() }
            }
        }.resume()
    }

    /// A header whatever the case the server sent it in (HTTP/2 answers are lower-case).
    static func header(_ http: HTTPURLResponse, _ name: String) -> String {
        let want = name.lowercased()
        for (k, v) in http.allHeaderFields {
            if String(describing: k).lowercased() == want { return String(describing: v) }
        }
        return ""
    }

    /// A stable fingerprint of the catalogue (FNV-1a over its size and a spread of its bytes).
    static func digest(_ data: Data) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        let n = data.count
        let step = max(1, n / 65536)
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var i = 0
            while i < n {
                h ^= UInt64(raw[i])
                h = h &* 0x100000001b3
                i += step
            }
        }
        return String(n) + ":" + String(h, radix: 16)
    }

    // MARK: pulse — a title the admin adds shows up within seconds

    func startPulse() {
        pulseTimer?.invalidate()
        pulseTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in self?.probe() }
        probe()
    }

    func stopPulse() {
        pulseTimer?.invalidate()
        pulseTimer = nil
    }

    /// HEAD of movies.json (~300 bytes). A new ETag = the catalogue changed: download it now.
    func probe(_ done: ((Bool) -> Void)? = nil) {
        if Date().timeIntervalSince(lastProbe) < 6 { done?(false); return }
        lastProbe = Date()
        var req = URLRequest(url: Backend.probeURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
        req.httpMethod = "HEAD"
        URLSession.shared.dataTask(with: req) { _, resp, _ in
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { done?(false); return }
            let etag = CatalogStore.header(http, "ETag").replacingOccurrences(of: "W/", with: "").replacingOccurrences(of: "\"", with: "")
            let seen = self.defaults.string(forKey: "cg.pulse.seen") ?? ""
            self.refreshSections()
            if etag.isEmpty || etag == seen { done?(false); return }
            self.refresh(force: true) { _ in
                self.defaults.set(etag, forKey: "cg.pulse.seen")
                done?(true)
            }
        }.resume()
    }
}

// MARK: - Images

/// Every picture of the app (posters, backdrops, channel logos, support-chat photos):
///  - one download per link at a time (several cells asking for the same poster share it);
///  - only a real 2xx image is accepted: an error page (404 HTML) is never decoded, cached or kept in the disk cache;
///  - decoded and down-sampled OFF the main thread (ImageIO), so scrolling never stutters on a big JPEG;
///  - a link on a bucket host that moved is retried once on the other host (the channel logos moved buckets: the
///    channels API still writes the old host — the Android app does the same rewrite, see CigramLogos.alternate);
///  - a link that failed is not asked again for a short while (no hammering of a dead host from every cell).
final class ImageLoader {
    static let shared = ImageLoader()

    /// Buckets that moved: (old, new). A link on either host is tried on the other one when it fails.
    static let movedHosts: [(String, String)] = [
        ("https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev/channels/", "https://pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev/channels/"),
    ]

    /// The other host of a moved bucket, or nil.
    static func alternate(_ url: URL) -> URL? {
        let s = url.absoluteString
        for (old, new) in movedHosts {
            if s.hasPrefix(old) { return URL(string: new + s.dropFirst(old.count)) }
            if s.hasPrefix(new) { return URL(string: old + s.dropFirst(new.count)) }
        }
        return nil
    }

    /// The link to ask first: a channel logo on the old bucket goes straight to the new one.
    static func preferred(_ url: URL) -> URL {
        let s = url.absoluteString
        for (old, new) in movedHosts where s.hasPrefix(old) {
            return URL(string: new + s.dropFirst(old.count)) ?? url
        }
        return url
    }

    private let cache = NSCache<NSString, UIImage>()
    private let lock = NSLock()
    private var waiting: [String: [(UIImage?) -> Void]] = [:]
    private var failedAt: [String: Date] = [:]
    private let screenScale: CGFloat = UIScreen.main.scale
    private let urlCache: URLCache
    private let session: URLSession

    init() {
        let disk = URLCache(memoryCapacity: 24 * 1024 * 1024, diskCapacity: 260 * 1024 * 1024, diskPath: "cigram-images")
        urlCache = disk
        let c = URLSessionConfiguration.default
        c.urlCache = disk
        c.requestCachePolicy = .returnCacheDataElseLoad
        c.timeoutIntervalForRequest = 25
        c.httpMaximumConnectionsPerHost = 6
        session = URLSession(configuration: c)
        cache.totalCostLimit = 80 * 1024 * 1024
        cache.countLimit = 900
        NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil,
                                               queue: nil) { [weak self] _ in self?.cache.removeAllObjects() }
    }

    func clear() {
        cache.removeAllObjects()
        urlCache.removeAllCachedResponses()
        lock.lock(); failedAt.removeAll(); lock.unlock()
    }

    /// The image already in memory (no network), for a cell that wants to paint synchronously.
    func cached(_ url: URL?, maxPixel: CGFloat = 0, key: String? = nil) -> UIImage? {
        guard let url = url else { return nil }
        return cache.object(forKey: cacheKey(url, maxPixel, key) as NSString)
    }

    private func cacheKey(_ url: URL, _ maxPixel: CGFloat, _ key: String?) -> String {
        return (key ?? ImageLoader.preferred(url).absoluteString) + "@" + String(Int(maxPixel))
    }

    /// Loads a picture. `maxPixel` (points, longest side) down-samples it for a small view; 0 keeps the full size.
    /// `key` replaces the link as the memory-cache key (signed chat links change on every fetch, their media id does not).
    /// The result always arrives on the main queue. The return value is kept for source compatibility (always nil:
    /// a shared download is never cancelled by one of the cells waiting for it).
    @discardableResult
    func load(_ url: URL?, maxPixel: CGFloat = 0, key: String? = nil, _ done: @escaping (UIImage?) -> Void) -> URLSessionDataTask? {
        guard let original = url else { done(nil); return nil }
        let k = cacheKey(original, maxPixel, key)
        if let img = cache.object(forKey: k as NSString) { done(img); return nil }
        lock.lock()
        if let t = failedAt[k], Date().timeIntervalSince(t) < 90 { lock.unlock(); done(nil); return nil }
        if waiting[k] != nil { waiting[k]?.append(done); lock.unlock(); return nil }
        waiting[k] = [done]
        lock.unlock()
        fetch(ImageLoader.preferred(original), maxPixel: maxPixel, key: k, triedAlternate: false)
        return nil
    }

    private func fetch(_ url: URL, maxPixel: CGFloat, key: String, triedAlternate: Bool) {
        var req = URLRequest(url: url)
        req.setValue("image/webp,image/png,image/jpeg,image/*;q=0.8", forHTTPHeaderField: "Accept")
        session.dataTask(with: req) { [weak self] data, resp, _ in
            guard let self = self else { return }
            let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            var image: UIImage?
            if (200..<300).contains(status), let d = data, !d.isEmpty {
                image = ImageLoader.decode(d, maxPixel: maxPixel, scale: self.screenScale)
            }
            if image == nil {
                // never keep an error page / a broken file in the disk cache: the next try must reach the network
                self.urlCache.removeCachedResponse(for: req)
                if !triedAlternate, let alt = ImageLoader.alternate(url) {
                    self.fetch(alt, maxPixel: maxPixel, key: key, triedAlternate: true)
                    return
                }
            }
            if let img = image {
                let cost = Int(img.size.width * img.size.height * img.scale * img.scale * 4)
                self.cache.setObject(img, forKey: key as NSString, cost: cost)
            }
            self.lock.lock()
            let callbacks = self.waiting.removeValue(forKey: key) ?? []
            if image == nil { self.failedAt[key] = Date() } else { self.failedAt.removeValue(forKey: key) }
            self.lock.unlock()
            DispatchQueue.main.async { callbacks.forEach { $0(image) } }
        }.resume()
    }

    /// Decodes on the calling (background) queue: ImageIO thumbnail = decoded once, rotated by its EXIF, down-sampled.
    static func decode(_ data: Data, maxPixel: CGFloat, scale: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(src) > 0 else { return nil }
        var longest: CGFloat = 0
        if let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] {
            let w = (p[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue ?? 0
            let h = (p[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue ?? 0
            longest = CGFloat(max(w, h))
        }
        if longest <= 0 { return UIImage(data: data) }
        let target = maxPixel > 0 ? min(longest, maxPixel * scale) : min(longest, 2600)
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                     kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceShouldCacheImmediately: true,
                                     kCGImageSourceThumbnailMaxPixelSize: Int(target.rounded(.up))]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return UIImage(data: data) }
        return UIImage(cgImage: cg, scale: maxPixel > 0 ? scale : 1, orientation: .up)
    }

    /// Blocking download for the notification gate (background queue only).
    func data(_ url: URL, timeout: TimeInterval = 15) -> Data? {
        let sem = DispatchSemaphore(value: 0)
        var out: Data?
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: req) { d, r, _ in
            if (r as? HTTPURLResponse)?.statusCode == 200 { out = d }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + timeout + 2)
        return out
    }
}

extension UIImage {
    /// True when the picture has an alpha channel (a transparent PNG logo).
    var cgHasAlpha: Bool {
        guard let a = cgImage?.alphaInfo else { return false }
        return a == .first || a == .last || a == .premultipliedFirst || a == .premultipliedLast
    }

    /// Average luminance (0…1) of the visible pixels, weighted by their opacity. Tiny 8×8 draw: cheap on the main thread.
    var visibleLuminance: CGFloat {
        guard let cg = cgImage else { return 0.5 }
        let n = 8
        var px = [UInt8](repeating: 0, count: n * n * 4)
        let drawn: Bool = px.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: n, height: n))
            return true
        }
        if !drawn { return 0.5 }
        var sum: CGFloat = 0
        var weight: CGFloat = 0
        for i in stride(from: 0, to: px.count, by: 4) {
            let a = CGFloat(px[i + 3]) / 255
            if a < 0.05 { continue }
            // premultiplied: divide by alpha to get the real colour
            let r = CGFloat(px[i]) / 255 / a, g = CGFloat(px[i + 1]) / 255 / a, b = CGFloat(px[i + 2]) / 255 / a
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) * a
            weight += a
        }
        return weight > 0 ? min(1, sum / weight) : 0.5
    }
}
