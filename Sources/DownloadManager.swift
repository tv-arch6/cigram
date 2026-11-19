import UIKit
import AVFoundation
import UserNotifications

// The native download manager. Everything about «التنزيلات» that must not depend on a screen being open:
//  - the source is found with the existing resolver (EmbedResolver) and CHECKED before anything is downloaded: an embed page, a
//    live stream, a protected (DRM) stream or an unsupported container is refused with a clear Arabic reason;
//  - a direct video file (mp4 / mov) goes through a background URLSession download task (resumable, ranges validated by
//    URLSession itself, written straight to disk, never held in memory);
//  - an HLS stream goes through AVAssetDownloadURLSession (the system downloads the playlists and pieces in the background and the
//    player opens the package offline) at the quality picked (the best one by default);
//  - a file is called «مكتمل» only after it was moved to its final place and opened successfully (playable, a real duration);
//  - the records live in a JSON file (DownloadStore); on launch the tasks the system kept are re-attached and the rest recovered.
// All state is touched on the main thread (the sessions deliver their callbacks there).

struct DownloadOption {
    let label: String           // «1080p» … empty when the stream does not declare a size
    let height: Int
    let bandwidth: Int
    let estimatedBytes: Int64
    let kind: DownloadKind
    let link: String            // the stream with its "|headers"
    let duration: Double
}

struct DownloadPlan {
    let kind: DownloadKind
    let options: [DownloadOption]       // best first
    let contentLength: Int64            // file: the real size, 0 when unknown
    let acceptsRange: Bool
}

enum InspectResult {
    case ok(DownloadPlan)
    case rejected(String)
}

/// Looks at a stream link (background thread) and says whether and how it can be downloaded.
enum DownloadInspector {
    static func inspect(_ stream: String) -> InspectResult {
        let clean = StreamLink.clean(stream)
        if clean.hasPrefix("data:") { return .rejected(DownloadMessages.unsupported) }
        guard clean.lowercased().hasPrefix("http") else { return .rejected(DownloadMessages.unsupported) }
        var headers = StreamLink.headers(of: stream)
        if headers["Accept"] == nil { headers["Accept"] = "*/*" }
        guard let r = StreamNet.get(clean, headers: headers, range: "bytes=0-4095", maxBytes: 4096, timeout: 10) else {
            return .rejected(NetworkMonitor.shared.isOnline ? DownloadMessages.unreachable : DownloadMessages.offline)
        }
        if r.status >= 400 {
            let d = DownloadMessages.describe(domain: NSURLErrorDomain, code: 0, http: r.status)
            return .rejected(d.text)
        }
        let type = r.header("Content-Type").lowercased()
        let bytes = [UInt8](r.data)
        let head = (String(bytes: bytes.prefix(64), encoding: .isoLatin1) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if head.hasPrefix("#EXTM3U") || type.contains("mpegurl") { return inspectHLS(stream, clean, headers) }
        if head.hasPrefix("<") || type.contains("text/html") || type.contains("json") || type.contains("text/plain") {
            return .rejected(DownloadMessages.notVideo)
        }
        if bytes.count >= 4 && bytes[0] == 0x1A && bytes[1] == 0x45 && bytes[2] == 0xDF && bytes[3] == 0xA3 {
            return .rejected(DownloadMessages.format)                        // Matroska / WebM: iOS cannot play it
        }
        let ftyp = bytes.count >= 12 && bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70
        if !ftyp && !(type.hasPrefix("video/mp4") || type.contains("quicktime")) { return .rejected(DownloadMessages.format) }
        var total: Int64 = 0
        let cr = r.header("Content-Range")
        if let slash = cr.lastIndex(of: "/") {
            total = Int64(String(cr[cr.index(after: slash)...]).trimmingCharacters(in: .whitespaces)) ?? 0
        } else if r.status == 200 {
            total = Int64(r.header("Content-Length").trimmingCharacters(in: .whitespaces)) ?? 0
        }
        if total > 0 && total < 1_000_000 { return .rejected(DownloadMessages.notVideo) }     // too small to be a film
        let ranges = r.status == 206 || r.header("Accept-Ranges").lowercased().contains("bytes")
        let opt = DownloadOption(label: "", height: 0, bandwidth: 0, estimatedBytes: total, kind: .file, link: stream, duration: 0)
        return .ok(DownloadPlan(kind: .file, options: [opt], contentLength: total, acceptsRange: ranges))
    }

    private static func isProtected(_ text: String) -> Bool {
        let t = text.lowercased()
        return t.contains("com.apple.streamingkeydelivery") || t.contains("skd://") || t.contains("playready") || t.contains("widevine")
    }

    private static func duration(of media: String) -> Double {
        var total = 0.0
        for line in media.components(separatedBy: "\n") where line.hasPrefix("#EXTINF:") {
            let v = line.dropFirst(8).components(separatedBy: ",")[0].trimmingCharacters(in: .whitespaces)
            total += Double(v) ?? 0
        }
        return total
    }

    private static func inspectHLS(_ stream: String, _ clean: String, _ headers: [String: String]) -> InspectResult {
        guard let r = StreamNet.get(clean, headers: headers, maxBytes: 1_500_000, timeout: 12), r.status / 100 == 2 else {
            return .rejected(DownloadMessages.unreachable)
        }
        let text = r.text
        if isProtected(text) { return .rejected(DownloadMessages.protected) }
        if !HLS.isMaster(text) {
            // one quality only
            if !text.contains("#EXT-X-ENDLIST") { return .rejected("هذا بث مباشر ولا يمكن تنزيله.") }
            let d = duration(of: text)
            let opt = DownloadOption(label: "", height: 0, bandwidth: 0, estimatedBytes: 0, kind: .hls, link: stream, duration: d)
            return .ok(DownloadPlan(kind: .hls, options: [opt], contentLength: 0, acceptsRange: true))
        }
        let variants = HLS.variants(master: text, base: r.finalURL)
        guard let best = variants.last else { return .rejected(DownloadMessages.unsupported) }
        var length = 0.0
        if let pr = StreamNet.get(best.url, headers: headers, maxBytes: 3_000_000, timeout: 12), pr.status / 100 == 2 {
            let media = pr.text
            if isProtected(media) { return .rejected(DownloadMessages.protected) }
            if !media.contains("#EXT-X-ENDLIST") { return .rejected("هذا بث مباشر ولا يمكن تنزيله.") }
            length = duration(of: media)
        }
        // one option per size, the highest bandwidth of each, best first
        var byHeight: [Int: HLSVariant] = [:]
        for v in variants {
            let h = v.height > 0 ? QualityLabel.classify(height: v.height, width: v.width) : 0
            if let old = byHeight[h], old.bandwidth >= v.bandwidth { continue }
            byHeight[h] = v
        }
        var options: [DownloadOption] = []
        for (h, v) in byHeight {
            var label = ""
            if h >= 720 { label = QualityLabel.label(height: v.height, width: v.width) } else if v.height > 0 { label = String(v.height) + "p" }
            let est = length > 0 ? Int64(Double(v.bandwidth) * length / 8.0 * 0.85) : 0
            options.append(DownloadOption(label: label, height: h, bandwidth: v.bandwidth, estimatedBytes: est, kind: .hls, link: stream, duration: length))
        }
        options.sort { $0.bandwidth > $1.bandwidth }
        return .ok(DownloadPlan(kind: .hls, options: options, contentLength: 0, acceptsRange: true))
    }
}

final class DownloadManager: NSObject, URLSessionDownloadDelegate, AVAssetDownloadDelegate {
    static let shared = DownloadManager()
    static let changed = Notification.Name("cigram.downloads.changed")           // the list or a state changed
    static let progressed = Notification.Name("cigram.downloads.progressed")     // bytes moved (at most twice a second)
    static let maxActive = 2
    private static let fileSessionId = "com.cigram.vid.downloads.file"
    private static let hlsSessionId = "com.cigram.vid.downloads.hls"

    private(set) var records: [DownloadRecord] = []
    private let store = DownloadStore()
    private var fileSession: URLSession?
    private var hlsSession: AVAssetDownloadURLSession?
    private var liveTasks: [String: URLSessionTask] = [:]
    private var jobs: [String: EmbedJob] = [:]
    private var pausing = Set<String>()
    private var cancelling = Set<String>()
    private var resumedWithData = Set<String>()
    private var speedState: [String: (t: Double, bytes: Int64, speed: Double)] = [:]
    private var lastProgressPost: Double = 0
    private var lastSave: Double = 0
    private var launched = false
    /// Asked when a stream offers several qualities: the screen shows the choice and answers (nil = cancel).
    var qualityPicker: ((DownloadRecord, [DownloadOption], @escaping (DownloadOption?) -> Void) -> Void)?
    var backgroundCompletion: (() -> Void)?

    // MARK: - start of the app

    /// Called at launch and when the system wakes the app for the downloads: opens the two background sessions (the system
    /// then delivers what happened meanwhile), re-attaches the tasks it kept, and reconciles the records with the files.
    func launch() {
        if launched { return }
        launched = true
        records = store.load()
        let fc = URLSessionConfiguration.background(withIdentifier: DownloadManager.fileSessionId)
        fc.sessionSendsLaunchEvents = true
        fc.isDiscretionary = false
        fc.timeoutIntervalForRequest = 60
        fc.httpMaximumConnectionsPerHost = 4
        fileSession = URLSession(configuration: fc, delegate: self, delegateQueue: OperationQueue.main)
        let hc = URLSessionConfiguration.background(withIdentifier: DownloadManager.hlsSessionId)
        hc.sessionSendsLaunchEvents = true
        hc.isDiscretionary = false
        hlsSession = AVAssetDownloadURLSession(configuration: hc, assetDownloadDelegate: self, delegateQueue: OperationQueue.main)
        DownloadPaths.excludeFromBackup(DownloadPaths.files)
        NotificationCenter.default.addObserver(self, selector: #selector(networkChanged), name: NetworkMonitor.changed, object: nil)

        let group = DispatchGroup()
        group.enter()
        fileSession?.getAllTasks { [weak self] tasks in
            DispatchQueue.main.async { self?.attach(tasks); group.leave() }
        }
        group.enter()
        hlsSession?.getAllTasks { [weak self] tasks in
            DispatchQueue.main.async { self?.attach(tasks); group.leave() }
        }
        // the system delivers the events of finished tasks right after the sessions exist: reconcile once they had time to arrive
        group.notify(queue: .main) { [weak self] in
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { self?.reconcile() }
        }
    }

    private func attach(_ tasks: [URLSessionTask]) {
        for t in tasks {
            guard let id = t.taskDescription, let i = index(of: id) else { t.cancel(); continue }
            if t.state == .completed || t.state == .canceling { continue }
            liveTasks[id] = t
            if t.state == .suspended {
                if records[i].state != .completed { records[i].state = .paused }
            } else if records[i].state == .preparing || records[i].state == .queued || records[i].state == .paused {
                records[i].state = .downloading
            }
        }
        post(changed: true)
    }

    /// Records vs. files: a completed record whose file is gone disappears; an interrupted one is queued again.
    private func reconcile() {
        var keep: [DownloadRecord] = []
        for var r in records {
            switch r.state {
            case .completed:
                if let u = r.localURL, FileManager.default.fileExists(atPath: u.path) { keep.append(r) }
                else { removeAux(r) }                                    // deleted outside the app: no longer «available offline»
            case .preparing, .queued, .downloading:
                if liveTasks[r.id] == nil && jobs[r.id] == nil {
                    r.state = .queued
                    r.autoResume = true
                }
                keep.append(r)
            case .verifying:
                if let u = r.localURL, FileManager.default.fileExists(atPath: u.path) { keep.append(r) } else { r.state = .queued; keep.append(r) }
            case .paused:
                if r.userPaused == false && liveTasks[r.id] == nil && !r.hasResumeData { r.state = .queued }
                keep.append(r)
            case .failed:
                keep.append(r)
            }
        }
        records = keep
        for r in records where r.state == .verifying { verify(r.id) }
        cleanOrphans()
        cleanOrphanPackages()
        resumeInterrupted()
        pump()
        persist(force: true)
        post(changed: true)
    }

    /// Downloads that stopped by themselves (the network, the app being closed) continue once the phone is online.
    private func resumeInterrupted() {
        guard NetworkMonitor.shared.isOnline else { return }
        for r in records where r.state == .paused && r.autoResume { resume(r.id) }
    }

    /// Files in the downloads folders that no record points at (an interrupted move, a deleted record) are removed. Only our own
    /// folders are looked at.
    private func cleanOrphans() {
        let fm = FileManager.default
        let ids = Set(records.map { $0.id })
        var used = Set<String>()
        for r in records where r.kind == .file && !r.localPath.isEmpty { used.insert(URL(fileURLWithPath: r.localPath).lastPathComponent) }
        if let list = try? fm.contentsOfDirectory(atPath: DownloadPaths.files.path) {
            for f in list where !used.contains(f) { try? fm.removeItem(at: DownloadPaths.files.appendingPathComponent(f)) }
        }
        if let list = try? fm.contentsOfDirectory(atPath: DownloadPaths.resume.path) {
            let have = Set(records.filter { $0.hasResumeData }.map { $0.id + ".data" })
            for f in list where !have.contains(f) { try? fm.removeItem(at: DownloadPaths.resume.appendingPathComponent(f)) }
        }
        if let list = try? fm.contentsOfDirectory(atPath: DownloadPaths.thumbs.path) {
            let have = Set(ids.map { $0 + ".jpg" })
            for f in list where !have.contains(f) { try? fm.removeItem(at: DownloadPaths.thumbs.appendingPathComponent(f)) }
        }
    }

    /// A cancelled / failed HLS download can leave a half package in the folders the system keeps for this app's downloads. They are
    /// removed only while no HLS download is in progress and only if no record points at them (only .movpkg items are touched).
    private func cleanOrphanPackages() {
        for r in records where r.kind == .hls && r.state.isInProgress { return }
        let fm = FileManager.default
        let lib = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library", isDirectory: true)
        guard let dirs = try? fm.contentsOfDirectory(atPath: lib.path) else { return }
        var keep = Set<String>()
        for r in records where r.kind == .hls {
            if let u = r.localURL { keep.insert(u.standardizedFileURL.path) }
        }
        for d in dirs where d.hasPrefix("com.apple.UserManagedAssets") {
            let dir = lib.appendingPathComponent(d, isDirectory: true)
            guard let items = try? fm.contentsOfDirectory(atPath: dir.path) else { continue }
            for it in items where it.hasSuffix(".movpkg") {
                let u = dir.appendingPathComponent(it, isDirectory: true)
                if !keep.contains(u.standardizedFileURL.path) { try? fm.removeItem(at: u) }
            }
        }
    }

    func becameActive() {
        guard launched else { return }
        var changedAny = false
        for r in records where r.state == .completed {
            if let u = r.localURL, !FileManager.default.fileExists(atPath: u.path) {
                delete(r.id)
                changedAny = true
            }
        }
        if changedAny { post(changed: true) }
        resumeInterrupted()
        pump()
    }

    func flush() { store.flush(records) }

    // MARK: - looking things up

    private func index(of id: String) -> Int? { return records.firstIndex { $0.id == id } }

    func record(withId id: String) -> DownloadRecord? { return index(of: id).map { records[$0] } }

    func record(for show: Show, episode: CatalogItem?) -> DownloadRecord? {
        let id = DownloadRecord.makeId(contentKey: show.key, season: episode?.season ?? 0, episode: episode?.episode ?? 0, isEpisode: episode != nil)
        return record(withId: id)
    }

    /// The local file of a COMPLETED download (nil when there is none, or the file vanished).
    func offlineURL(show: Show, episode: CatalogItem?) -> URL? {
        guard let r = record(for: show, episode: episode), r.state == .completed, let u = r.localURL,
              FileManager.default.fileExists(atPath: u.path) else { return nil }
        return u
    }

    func speed(of id: String) -> Double { return speedState[id]?.speed ?? 0 }

    var activeCount: Int { return records.filter { $0.state.isInProgress }.count }
    var completedCount: Int { return records.filter { $0.state == .completed }.count }

    /// Real disk usage of the finished downloads (counted on a background queue).
    func diskUsage(_ done: @escaping (Int64) -> Void) {
        let list = records
        DispatchQueue.global(qos: .utility).async {
            var total: Int64 = 0
            for r in list { if let u = r.localURL { total += DiskSpace.size(of: u) } }
            DispatchQueue.main.async { done(total) }
        }
    }

    // MARK: - starting

    enum BeginResult {
        case started
        case alreadyThere(DownloadRecord)
        case noLink
        case noSpace
    }

    /// The links a title can be found at, best first (the published link, then the other servers by TMDB id): same as the player.
    func candidateLinks(show: Show, episode: CatalogItem?) -> [String] {
        var out: [String] = []
        let own = (episode?.video ?? (show.isSeries ? "" : show.head.video)).replacingOccurrences(of: "vidbolt.xyz", with: "vidbolt.pro")
        if own.hasPrefix("http") { out.append(own) }
        let tmdb = show.head.tmdb.isEmpty ? (episode?.tmdb ?? "") : show.head.tmdb
        if !tmdb.isEmpty {
            if let e = episode {
                for f in Backend.tvServers {
                    let u = String(format: f, tmdb, e.season, e.episode)
                    if !out.contains(u) { out.append(u) }
                }
            } else if !show.isSeries {
                for f in Backend.movieServers {
                    let u = String(format: f, tmdb)
                    if !out.contains(u) { out.append(u) }
                }
            }
        }
        return out
    }

    @discardableResult
    func begin(show: Show, episode: CatalogItem?) -> BeginResult {
        launch()
        let id = DownloadRecord.makeId(contentKey: show.key, season: episode?.season ?? 0, episode: episode?.episode ?? 0, isEpisode: episode != nil)
        if let old = record(withId: id) {
            switch old.state {
            case .failed: retry(id); return .started
            case .paused: resume(id); return .started
            default: return .alreadyThere(old)           // duplicate downloads are refused
            }
        }
        let links = candidateLinks(show: show, episode: episode)
        if links.isEmpty { return .noLink }
        if DiskSpace.available() < 300 * 1_048_576 { return .noSpace }
        var r = DownloadRecord.blank(id: id)
        r.contentKey = show.key
        r.itemId = episode?.id ?? show.head.id
        r.title = show.title
        if let e = episode, !e.title.isEmpty, e.title != show.title, !e.title.contains("الحلقة") { r.episodeTitle = e.title }
        r.poster = (Show.image(episode?.poster ?? "", size: "w342") ?? show.posterURL)?.absoluteString ?? ""
        r.isEpisode = episode != nil
        r.season = episode?.season ?? 0
        r.episode = episode?.episode ?? 0
        r.sourcePage = links[0]
        r.state = .queued
        records.append(r)
        saveThumbnail(r)
        persist(force: true)
        post(changed: true)
        pump()
        return .started
    }

    /// Moves queued records into the free slots (at most `maxActive` run at once; nothing starts while the phone is offline).
    private func pump() {
        guard launched else { return }
        var active = records.filter { $0.state.isActive }.count
        for r in records where r.state == .queued {
            if active >= DownloadManager.maxActive { break }
            if !NetworkMonitor.shared.isOnline { break }
            active += 1
            if r.hasResumeData && r.kind == .file && !r.streamLink.isEmpty, let data = readResumeData(r.id) {
                resumeTask(r.id, data: data)
            } else {
                startPrepare(r.id)
            }
        }
    }

    private func startPrepare(_ id: String) {
        guard let i = index(of: id) else { return }
        records[i].state = .preparing
        records[i].errorMessage = ""
        records[i].autoResume = false
        touch(i)
        post(changed: true)
        let sources = candidateLinks(forRecord: records[i])
        resolveSources(id: id, sources: sources, at: 0, lastProblem: "")
    }

    /// The links to try for a stored record (its own page first, then the other servers).
    private func candidateLinks(forRecord r: DownloadRecord) -> [String] {
        var out: [String] = []
        if !r.sourcePage.isEmpty { out.append(r.sourcePage) }
        if let s = CatalogStore.shared.show(forKey: r.contentKey) {
            let ep = r.isEpisode ? s.episodes.first(where: { $0.season == r.season && $0.episode == r.episode }) : nil
            for l in candidateLinks(show: s, episode: ep) where !out.contains(l) { out.append(l) }
        }
        return out
    }

    private func isPreparing(_ id: String) -> Bool { return record(withId: id)?.state == .preparing }

    private func resolveSources(id: String, sources: [String], at n: Int, lastProblem: String) {
        guard isPreparing(id) else { return }
        if n >= sources.count {
            fail(id, text: lastProblem.isEmpty ? DownloadMessages.noSource : lastProblem, code: 0, autoResume: false)
            return
        }
        let link = sources[n]
        let inspectAll: ([String]) -> Void = { [weak self] streams in
            DispatchQueue.global(qos: .userInitiated).async {
                var problem = ""
                for s in streams.prefix(5) {
                    switch DownloadInspector.inspect(s) {
                    case .ok(let plan):
                        DispatchQueue.main.async { self?.planReady(id, plan: plan, page: link) }
                        return
                    case .rejected(let why):
                        problem = why
                    }
                }
                DispatchQueue.main.async { self?.resolveSources(id: id, sources: sources, at: n + 1, lastProblem: problem) }
            }
        }
        if StreamLink.kind(of: link) == nil && StreamLink.isEmbed(link) {
            guard let host = (UIApplication.shared.delegate as? AppDelegate)?.window else {
                resolveSources(id: id, sources: sources, at: n + 1, lastProblem: DownloadMessages.unreachable)
                return
            }
            jobs[id] = EmbedResolver.shared.resolve(page: link, host: host, timeout: n == 0 ? 28 : 18) { [weak self] stream, reason in
                guard let self = self else { return }
                self.jobs[id] = nil
                guard self.isPreparing(id) else { return }
                if let s = stream {
                    var streams = [s.url]
                    for a in s.alternatives where a != s.url { streams.append(a) }
                    inspectAll(streams)
                } else {
                    let p = PlaybackProblem.fromResolver(reason, online: NetworkMonitor.shared.isOnline)
                    self.resolveSources(id: id, sources: sources, at: n + 1, lastProblem: p.message)
                }
            }
        } else {
            inspectAll([link])
        }
    }

    /// The source was found and checked: pick the quality (the best, unless the stream offers several and the viewer chooses).
    private func planReady(_ id: String, plan: DownloadPlan, page: String) {
        guard let i = index(of: id), records[i].state == .preparing else { return }
        records[i].sourcePage = page
        let go: (DownloadOption?) -> Void = { [weak self] option in
            guard let self = self, self.isPreparing(id) else { return }
            guard let o = option else { self.cancel(id); return }
            self.startTask(id, plan: plan, option: o)
        }
        if plan.options.count > 1, let picker = qualityPicker {
            picker(records[i], plan.options, go)
        } else {
            go(plan.options.first)
        }
    }

    private func startTask(_ id: String, plan: DownloadPlan, option: DownloadOption) {
        guard let i = index(of: id) else { return }
        let need = option.estimatedBytes > 0 ? option.estimatedBytes : plan.contentLength
        if need > 0 && DiskSpace.available() < need + 150 * 1_048_576 {
            fail(id, text: DownloadMessages.storage + " تحتاج إلى نحو " + DownloadFormat.size(need) + ".", code: 0, autoResume: false)
            return
        }
        let clean = StreamLink.clean(option.link)
        var headers = StreamLink.headers(of: option.link)
        if headers["User-Agent"] == nil { headers["User-Agent"] = StreamLink.userAgent }
        guard let url = URL(string: clean) else { fail(id, text: DownloadMessages.unsupported, code: 0, autoResume: false); return }
        records[i].kind = option.kind
        records[i].quality = option.label
        records[i].qualityHeight = option.height
        records[i].bandwidth = option.bandwidth
        records[i].duration = option.duration
        records[i].streamLink = option.link
        records[i].expectedBytes = need
        records[i].expectedIsEstimate = option.kind == .hls
        records[i].downloadedBytes = 0
        records[i].progress = 0
        records[i].hasResumeData = false
        removeResumeFile(id)
        switch option.kind {
        case .file:
            var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 60)
            for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
            guard let session = fileSession else { return }
            let t = session.downloadTask(with: req)
            t.taskDescription = id
            liveTasks[id] = t
            records[i].state = .downloading
            t.resume()
        case .hls:
            let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
            var options: [String: Any]?
            if option.bandwidth > 0 { options = [AVAssetDownloadTaskMinimumRequiredMediaBitrateKey: option.bandwidth] }
            guard let session = hlsSession,
                  let t = session.makeAssetDownloadTask(asset: asset, assetTitle: records[i].title, assetArtworkData: nil, options: options) else {
                fail(id, text: DownloadMessages.unsupported, code: 0, autoResume: false)
                return
            }
            t.taskDescription = id
            liveTasks[id] = t
            records[i].state = .downloading
            t.resume()
        }
        touch(i)
        persist(force: true)
        post(changed: true)
    }

    // MARK: - pause / resume / cancel / retry / delete

    func pause(_ id: String) {
        guard let i = index(of: id) else { return }
        switch records[i].state {
        case .downloading:
            if let t = liveTasks[id] as? URLSessionDownloadTask {
                pausing.insert(id)
                t.cancel(byProducingResumeData: { [weak self] data in
                    DispatchQueue.main.async { self?.savedResumeData(id, data) }
                })
            } else if let t = liveTasks[id] as? AVAssetDownloadTask {
                t.suspend()
            }
        case .preparing:
            jobs[id]?.cancel()
            jobs[id] = nil
        case .queued:
            break
        default:
            return
        }
        records[i].state = .paused
        records[i].userPaused = true
        records[i].autoResume = false
        speedState[id] = nil
        touch(i)
        persist(force: true)
        post(changed: true)
        pump()
    }

    func resume(_ id: String) {
        guard let i = index(of: id), records[i].state == .paused || records[i].state == .failed else { return }
        records[i].userPaused = false
        records[i].autoResume = false
        records[i].errorMessage = ""
        if let t = liveTasks[id] as? AVAssetDownloadTask, t.state == .suspended {
            t.resume()
            records[i].state = .downloading
        } else if records[i].kind == .file && records[i].hasResumeData, let data = readResumeData(id) {
            if records[i].state == .paused || records[i].state == .failed {
                // goes through the queue so at most two run together
                records[i].state = .queued
            }
            _ = data
        } else {
            records[i].state = .queued
            if let t = liveTasks[id] {
                cancelling.insert(id)
                t.cancel()
                liveTasks[id] = nil
            }
            records[i].downloadedBytes = 0
            records[i].progress = 0
        }
        touch(i)
        persist(force: true)
        post(changed: true)
        pump()
    }

    private func resumeTask(_ id: String, data: Data) {
        guard let i = index(of: id), let session = fileSession else { return }
        let t = session.downloadTask(withResumeData: data)
        t.taskDescription = id
        liveTasks[id] = t
        resumedWithData.insert(id)
        records[i].state = .downloading
        records[i].errorMessage = ""
        touch(i)
        post(changed: true)
        t.resume()
    }

    /// Stops the download and throws away what was downloaded (the record goes too).
    func cancel(_ id: String) {
        guard let i = index(of: id) else { return }
        let r = records[i]
        jobs[id]?.cancel()
        jobs[id] = nil
        if let t = liveTasks[id] {
            cancelling.insert(id)
            t.cancel()
        }
        liveTasks[id] = nil
        removeAux(r)
        if let u = r.localURL, DownloadPaths.mayDelete(u, kind: r.kind) { try? FileManager.default.removeItem(at: u) }
        records.remove(at: i)
        speedState[id] = nil
        persist(force: true)
        post(changed: true)
        pump()
        if r.kind == .hls { DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.cleanOrphanPackages() } }
    }

    /// A finished download: the file and the record are removed (the caller asked the viewer to confirm first).
    func delete(_ id: String) {
        guard let i = index(of: id) else { return }
        let r = records[i]
        if let u = r.localURL, DownloadPaths.mayDelete(u, kind: r.kind) { try? FileManager.default.removeItem(at: u) }
        removeAux(r)
        records.remove(at: i)
        persist(force: true)
        post(changed: true)
    }

    func retry(_ id: String) {
        guard let i = index(of: id), records[i].state == .failed else { return }
        records[i].errorMessage = ""
        records[i].errorCode = 0
        records[i].refreshed = 0
        records[i].userPaused = false
        records[i].autoResume = false
        if !(records[i].kind == .file && records[i].hasResumeData) {
            records[i].downloadedBytes = 0
            records[i].progress = 0
        }
        records[i].state = .queued
        touch(i)
        persist(force: true)
        post(changed: true)
        pump()
    }

    // MARK: - resume data and small files

    private func resumeFile(_ id: String) -> URL { return DownloadPaths.resume.appendingPathComponent(id + ".data") }

    private func readResumeData(_ id: String) -> Data? { return try? Data(contentsOf: resumeFile(id)) }

    private func removeResumeFile(_ id: String) { try? FileManager.default.removeItem(at: resumeFile(id)) }

    private func savedResumeData(_ id: String, _ data: Data?) {
        liveTasks[id] = nil
        guard let i = index(of: id) else { return }
        if let d = data, !d.isEmpty {
            do {
                try d.write(to: resumeFile(id), options: [.atomic])
                records[i].hasResumeData = true
            } catch {
                records[i].hasResumeData = false
            }
        } else {
            records[i].hasResumeData = false
        }
        touch(i)
        persist(force: true)
    }

    private func removeAux(_ r: DownloadRecord) {
        removeResumeFile(r.id)
        try? FileManager.default.removeItem(at: DownloadPaths.thumbs.appendingPathComponent(r.id + ".jpg"))
    }

    private func thumbURL(_ id: String) -> URL { return DownloadPaths.thumbs.appendingPathComponent(id + ".jpg") }

    func thumbnail(for id: String) -> UIImage? { return UIImage(contentsOfFile: thumbURL(id).path) }

    /// The poster is kept on the phone (a small JPEG), so the list shows it offline.
    private func saveThumbnail(_ r: DownloadRecord) {
        guard let url = URL(string: r.poster) else { return }
        let dest = thumbURL(r.id)
        _ = ImageLoader.shared.load(url, maxPixel: 300) { img in
            guard let img = img, let d = img.jpegData(compressionQuality: 0.8) else { return }
            try? d.write(to: dest, options: [.atomic])
            DispatchQueue.main.async { NotificationCenter.default.post(name: DownloadManager.changed, object: nil) }
        }
    }

    // MARK: - failures

    private func fail(_ id: String, text: String, code: Int, autoResume: Bool) {
        guard let i = index(of: id) else { return }
        jobs[id]?.cancel()
        jobs[id] = nil
        if let t = liveTasks[id] {
            if t.state == .running || t.state == .suspended {
                cancelling.insert(id)           // its "cancelled" callback must not replace the reason shown
                t.cancel()
            }
            liveTasks[id] = nil
        }
        records[i].state = autoResume ? .paused : .failed
        records[i].autoResume = autoResume
        records[i].errorMessage = text
        records[i].errorCode = code
        speedState[id] = nil
        touch(i)
        persist(force: true)
        post(changed: true)
        pump()
    }

    @objc private func networkChanged() {
        resumeInterrupted()
        pump()
    }

    // MARK: - URLSession (file) delegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription, let i = index(of: id), records[i].state != .paused else { return }
        resumedWithData.remove(id)
        records[i].state = .downloading
        records[i].downloadedBytes = totalBytesWritten
        if totalBytesExpectedToWrite > 0 {
            records[i].expectedBytes = totalBytesExpectedToWrite
            records[i].expectedIsEstimate = false
            records[i].progress = min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
        }
        updateSpeed(id, bytes: totalBytesWritten)
        progressTick(i)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription, let i = index(of: id) else { return }
        let http = downloadTask.response as? HTTPURLResponse
        if let h = http, !(200..<300).contains(h.statusCode) {
            // an error page is also delivered as a "file": never kept
            let d = DownloadMessages.describe(domain: NSURLErrorDomain, code: 0, http: h.statusCode)
            if (h.statusCode == 401 || h.statusCode == 403 || h.statusCode == 404 || h.statusCode == 410) && refreshLink(id) { return }
            fail(id, text: d.text, code: h.statusCode, autoResume: false)
            return
        }
        let size = DiskSpace.size(of: location)
        let expected = downloadTask.countOfBytesExpectedToReceive
        if expected > 0 && size != expected && http?.statusCode != 206 {
            fail(id, text: DownloadMessages.incomplete, code: 0, autoResume: false)
            return
        }
        // the first bytes: a web page or another container is not a film
        var head = Data()
        if let h = try? FileHandle(forReadingFrom: location) {
            head = h.readData(ofLength: 16)
            h.closeFile()
        }
        let b = [UInt8](head)
        if b.first == 0x3C { fail(id, text: DownloadMessages.notVideo, code: 0, autoResume: false); return }       // "<"
        if b.count >= 4 && b[0] == 0x1A && b[1] == 0x45 && b[2] == 0xDF && b[3] == 0xA3 {
            fail(id, text: DownloadMessages.format, code: 0, autoResume: false)
            return
        }
        if size < 500_000 { fail(id, text: DownloadMessages.corrupt, code: 0, autoResume: false); return }
        let name = id + ".mp4"
        let dest = DownloadPaths.files.appendingPathComponent(name)
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: location, to: dest)
        } catch {
            let ns = error as NSError
            let d = DownloadMessages.describe(domain: ns.domain, code: ns.code, http: 0)
            fail(id, text: d.text == DownloadMessages.unknown ? DownloadMessages.write : d.text, code: ns.code, autoResume: false)
            return
        }
        DownloadPaths.excludeFromBackup(dest)
        records[i].localPath = "Downloads/files/" + name
        records[i].state = .verifying
        records[i].progress = 1
        records[i].downloadedBytes = size
        touch(i)
        post(changed: true)
        verify(id)
    }

    // MARK: - AVAssetDownloadURLSession (HLS) delegate

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didLoad timeRange: CMTimeRange,
                    totalTimeRangesLoaded loadedTimeRanges: [NSValue], timeRangeExpectedToLoad: CMTimeRange) {
        guard let id = assetDownloadTask.taskDescription, let i = index(of: id), records[i].state != .paused else { return }
        var loaded = 0.0
        for v in loadedTimeRanges { loaded += CMTimeGetSeconds(v.timeRangeValue.duration) }
        let expected = CMTimeGetSeconds(timeRangeExpectedToLoad.duration)
        guard expected.isFinite, expected > 0, loaded.isFinite else { return }
        records[i].state = .downloading
        let p = min(1, loaded / expected)
        records[i].progress = p
        if records[i].duration <= 0 { records[i].duration = expected }
        if records[i].expectedBytes > 0 { records[i].downloadedBytes = Int64(Double(records[i].expectedBytes) * p) }
        updateSpeed(id, bytes: records[i].downloadedBytes)
        progressTick(i)
    }

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = assetDownloadTask.taskDescription, let i = index(of: id) else { return }
        // the package stays where the system put it; only its path inside this app's folder is saved (the container path changes)
        let path = location.path
        var rel = location.relativePath
        if let r = path.range(of: "/Library/") { rel = "Library/" + String(path[r.upperBound...]) }
        records[i].localPath = rel
        records[i].kind = .hls
        records[i].state = .verifying
        records[i].progress = 1
        touch(i)
        post(changed: true)
        verify(id)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let id = task.taskDescription else { return }
        if liveTasks[id] === task { liveTasks[id] = nil }
        if pausing.remove(id) != nil { return }                 // the viewer paused it: handled when the resume data arrived
        if cancelling.remove(id) != nil { return }              // cancelled by us (cancel / a failure already reported)
        guard let err = error, let i = index(of: id) else { return }
        if records[i].state == .completed || records[i].state == .verifying { return }
        let ns = err as NSError
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        let d = DownloadMessages.describe(domain: ns.domain, code: ns.code, http: status)
        if let data = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data, !data.isEmpty {
            try? data.write(to: resumeFile(id), options: [.atomic])
            records[i].hasResumeData = true
        }
        // resume data that the server no longer accepts: start again from the link, once
        if resumedWithData.remove(id) != nil && status == 0 && !isNetworkError(ns) {
            removeResumeFile(id)
            records[i].hasResumeData = false
            records[i].state = .queued
            touch(i)
            post(changed: true)
            pump()
            return
        }
        if status == 401 || status == 403 || status == 404 || status == 410 {
            if refreshLink(id) { return }
        }
        if isNetworkError(ns) {
            fail(id, text: DownloadMessages.offline, code: ns.code, autoResume: true)      // continues by itself when the network is back
        } else if ns.code == NSURLErrorCancelled {
            // the system stopped it (the app was closed from the app switcher): it continues from the saved bytes by itself
            fail(id, text: DownloadMessages.interrupted, code: ns.code, autoResume: records[i].hasResumeData)
        } else {
            fail(id, text: d.text, code: ns.code == 0 ? status : ns.code, autoResume: false)
        }
    }

    private func isNetworkError(_ e: NSError) -> Bool {
        guard e.domain == NSURLErrorDomain else { return false }
        return [NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut, NSURLErrorDataNotAllowed,
                NSURLErrorCannotConnectToHost, NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed].contains(e.code)
    }

    /// The link expired or moved: the stream is found again from the title's page (once), and the download starts from the new link.
    private func refreshLink(_ id: String) -> Bool {
        guard let i = index(of: id), records[i].refreshed < 1 else { return false }
        records[i].refreshed += 1
        for s in candidateLinks(forRecord: records[i]) { EmbedResolver.shared.invalidate(page: s) }
        removeResumeFile(id)
        records[i].hasResumeData = false
        records[i].downloadedBytes = 0
        records[i].progress = 0
        records[i].state = .queued
        touch(i)
        post(changed: true)
        pump()
        return true
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { [weak self] in
            let h = self?.backgroundCompletion
            self?.backgroundCompletion = nil
            h?()
        }
    }

    // MARK: - checking a finished file

    /// A download is complete only when the file opens: playable, with a real length (an HLS package: playable offline).
    private func verify(_ id: String) {
        guard let i = index(of: id), let url = records[i].localURL else { return }
        let kind = records[i].kind
        let asset = AVURLAsset(url: url)
        asset.loadValuesAsynchronously(forKeys: ["playable", "duration"]) { [weak self] in
            var e1: NSError?
            var e2: NSError?
            let s1 = asset.statusOfValue(forKey: "playable", error: &e1)
            let s2 = asset.statusOfValue(forKey: "duration", error: &e2)
            let seconds = CMTimeGetSeconds(asset.duration)
            var ok = s1 == .loaded && s2 == .loaded && asset.isPlayable && seconds.isFinite && seconds > 1
            if ok && kind == .hls { ok = asset.assetCache?.isPlayableOffline == true }
            let size = DiskSpace.size(of: url)
            DispatchQueue.main.async { self?.verified(id, ok: ok, seconds: seconds.isFinite ? seconds : 0, size: size) }
        }
    }

    private func verified(_ id: String, ok: Bool, seconds: Double, size: Int64) {
        guard let i = index(of: id) else { return }
        if ok && size > 0 {
            records[i].state = .completed
            records[i].progress = 1
            records[i].downloadedBytes = size
            records[i].expectedBytes = size
            records[i].expectedIsEstimate = false
            if seconds > 0 { records[i].duration = seconds }
            records[i].completedAt = Date().timeIntervalSince1970
            records[i].errorMessage = ""
            records[i].hasResumeData = false
            removeResumeFile(id)
            speedState[id] = nil
            touch(i)
            persist(force: true)
            post(changed: true)
            announce(records[i])
        } else {
            if let u = records[i].localURL, DownloadPaths.mayDelete(u, kind: records[i].kind) { try? FileManager.default.removeItem(at: u) }
            records[i].localPath = ""
            fail(id, text: DownloadMessages.corrupt, code: 0, autoResume: false)
        }
        pump()
    }

    /// «اكتمل التنزيل», only when the app is not on screen.
    private func announce(_ r: DownloadRecord) {
        guard UIApplication.shared.applicationState != .active else { return }
        let c = UNMutableNotificationContent()
        c.title = "اكتمل التنزيل"
        c.body = r.isEpisode ? r.title + " · " + r.kindText : r.title
        c.sound = UNNotificationSound.default
        let req = UNNotificationRequest(identifier: "cg.download." + r.id, content: c,
                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }

    // MARK: - bookkeeping

    private func touch(_ i: Int) { records[i].updated = Date().timeIntervalSince1970 }

    /// Speed from the bytes of the last seconds (smoothed).
    private func updateSpeed(_ id: String, bytes: Int64) {
        let now = Date().timeIntervalSince1970
        guard let old = speedState[id] else {
            speedState[id] = (t: now, bytes: bytes, speed: 0)
            return
        }
        let dt = now - old.t
        if dt < 1 { return }
        let inst = Double(max(0, bytes - old.bytes)) / dt
        let smooth = old.speed <= 0 ? inst : old.speed * 0.6 + inst * 0.4
        speedState[id] = (t: now, bytes: bytes, speed: smooth)
    }

    /// Progress is posted at most twice a second; the file is written every few seconds.
    private func progressTick(_ i: Int) {
        let now = Date().timeIntervalSince1970
        if now - lastProgressPost >= 0.5 {
            lastProgressPost = now
            NotificationCenter.default.post(name: DownloadManager.progressed, object: nil)
        }
        if now - lastSave >= 5 { persist(force: false) }
    }

    private func persist(force: Bool) {
        lastSave = Date().timeIntervalSince1970
        store.save(records)
    }

    private func post(changed: Bool) {
        NotificationCenter.default.post(name: DownloadManager.changed, object: nil)
    }
}
