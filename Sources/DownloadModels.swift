import Foundation

// «التنزيلات» — the data side of the download system: one record per downloaded / downloading film or episode, saved in a JSON
// file on this iPhone (written atomically), the places the files live in, free-space checks, and the Arabic texts of every
// state and error. Nothing here touches the network or the screen.

enum DownloadState: String, Codable {
    case preparing      // the source is being found and checked
    case queued         // waiting for a free slot (at most 2 downloads run together)
    case downloading
    case verifying      // the file arrived and is being checked before it is called complete
    case paused
    case completed
    case failed

    /// A slot of the queue is in use.
    var isActive: Bool { return self == .preparing || self == .downloading || self == .verifying }
    /// Not finished and not failed: the viewer can still see it advancing or resume it.
    var isInProgress: Bool { return isActive || self == .queued || self == .paused }

    var arabic: String {
        switch self {
        case .preparing: return "جارٍ التجهيز"
        case .queued: return "في الانتظار"
        case .downloading: return "جارٍ التنزيل"
        case .verifying: return "جارٍ التحقق من الملف"
        case .paused: return "متوقف مؤقتاً"
        case .completed: return "مكتمل"
        case .failed: return "فشل التنزيل"
        }
    }
}

enum DownloadKind: String, Codable {
    case file       // one video file (mp4 / mov): a background URLSession download task
    case hls        // an HLS stream: AVAssetDownloadURLSession (saved as a .movpkg package the player opens offline)
}

struct DownloadRecord: Codable {
    var id: String
    var contentKey: String          // Show.key
    var itemId: String              // the film's id / the episode's id
    var title: String
    var episodeTitle: String
    var poster: String
    var isEpisode: Bool
    var season: Int
    var episode: Int
    var quality: String             // "1080p" … only what the stream itself declares (empty = not verified)
    var qualityHeight: Int
    var bandwidth: Int              // the HLS quality chosen (bits per second), 0 for a file
    var expectedBytes: Int64        // 0 = unknown
    var expectedIsEstimate: Bool    // HLS: bandwidth × duration
    var downloadedBytes: Int64
    var progress: Double            // 0 ... 1
    var duration: Double            // seconds, 0 = unknown
    var state: DownloadState
    var kind: DownloadKind
    var localPath: String           // file: relative to Application Support/Cigram; hls: relative to the home folder
    var sourcePage: String          // the link the title was published with (the stream is found from it again when it expires)
    var streamLink: String          // the stream being downloaded (with its "|headers")
    var userPaused: Bool
    var autoResume: Bool            // stopped by the network, not by the viewer: continues by itself when it returns
    var refreshed: Int              // how many times the link was renewed
    var errorMessage: String
    var errorCode: Int
    var hasResumeData: Bool
    var created: Double
    var completedAt: Double
    var updated: Double

    static func makeId(contentKey: String, season: Int, episode: Int, isEpisode: Bool) -> String {
        let k = DownloadPaths.safeName(contentKey)
        return isEpisode ? "e_" + k + "_" + String(season) + "_" + String(episode) : "m_" + k
    }

    static func blank(id: String) -> DownloadRecord {
        let now = Date().timeIntervalSince1970
        return DownloadRecord(id: id, contentKey: "", itemId: "", title: "", episodeTitle: "", poster: "", isEpisode: false, season: 0,
                              episode: 0, quality: "", qualityHeight: 0, bandwidth: 0, expectedBytes: 0, expectedIsEstimate: false,
                              downloadedBytes: 0, progress: 0, duration: 0, state: .preparing, kind: .file, localPath: "",
                              sourcePage: "", streamLink: "", userPaused: false, autoResume: false, refreshed: 0, errorMessage: "",
                              errorCode: 0, hasResumeData: false, created: now, completedAt: 0, updated: now)
    }

    /// «الموسم 2 · الحلقة 5» or «فيلم».
    var kindText: String {
        return isEpisode ? "الموسم " + String(season) + " · الحلقة " + String(episode) : "فيلم"
    }

    var localURL: URL? {
        if localPath.isEmpty { return nil }
        switch kind {
        case .file: return DownloadPaths.base.appendingPathComponent(localPath)
        case .hls: return URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(localPath)
        }
    }
}

// MARK: - where things live

enum DownloadPaths {
    /// Application Support/Cigram (the catalogue cache lives here too).
    static var base: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Cigram", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static var downloads: URL { return folder("Downloads", under: base) }
    static var files: URL { return folder("files", under: downloads) }
    static var thumbs: URL { return folder("thumbs", under: downloads) }
    static var resume: URL { return folder("resume", under: downloads) }
    static var database: URL { return downloads.appendingPathComponent("downloads.json") }

    private static func folder(_ name: String, under parent: URL) -> URL {
        let u = parent.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static func safeName(_ s: String) -> String {
        var out = ""
        for ch in s.unicodeScalars {
            if (ch.value >= 48 && ch.value <= 57) || (ch.value >= 65 && ch.value <= 90) || (ch.value >= 97 && ch.value <= 122) || ch == "-" || ch == "_" {
                out.unicodeScalars.append(ch)
            } else {
                out += "_"
            }
        }
        return out.isEmpty ? "x" : String(out.prefix(80))
    }

    /// A path is ours to delete only inside the downloads folder or, for an HLS package, inside Library/ of this app and named .movpkg.
    static func mayDelete(_ url: URL, kind: DownloadKind) -> Bool {
        let p = url.standardizedFileURL.path
        switch kind {
        case .file: return p.hasPrefix(downloads.standardizedFileURL.path + "/")
        case .hls: return p.contains("/Library/") && p.hasSuffix(".movpkg") && p.hasPrefix(URL(fileURLWithPath: NSHomeDirectory()).standardizedFileURL.path)
        }
    }

    static func excludeFromBackup(_ url: URL) {
        var u = url
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }
}

enum DiskSpace {
    /// Free space the system would give this app (counts what it can purge).
    static func available() -> Int64 {
        let url = DownloadPaths.base
        if let v = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let c = v.volumeAvailableCapacityForImportantUsage { return c }
        if let a = try? FileManager.default.attributesOfFileSystem(forPath: url.path), let n = a[.systemFreeSize] as? NSNumber { return n.int64Value }
        return Int64.max
    }

    /// The real size of a file or of a package folder on disk.
    static func size(of url: URL) -> Int64 {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { return 0 }
        if !isDir.boolValue {
            if let a = try? fm.attributesOfItem(atPath: url.path), let n = a[.size] as? NSNumber { return n.int64Value }
            return 0
        }
        var total: Int64 = 0
        if let en = fm.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey], options: []) {
            for case let f as URL in en {
                if let v = try? f.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]), v.isRegularFile == true {
                    total += Int64(v.fileSize ?? 0)
                }
            }
        }
        return total
    }
}

// MARK: - texts

enum DownloadFormat {
    static func size(_ bytes: Int64) -> String {
        if bytes <= 0 { return "—" }
        let b = Double(bytes)
        if b >= 1_073_741_824 { return String(format: "%.2f غيغابايت", b / 1_073_741_824) }
        if b >= 1_048_576 { return String(format: "%.0f ميغابايت", b / 1_048_576) }
        return String(format: "%.0f كيلوبايت", max(1, b / 1024))
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        if bytesPerSecond < 1 { return "" }
        if bytesPerSecond >= 1_048_576 { return String(format: "%.1f ميغابايت/ث", bytesPerSecond / 1_048_576) }
        return String(format: "%.0f كيلوبايت/ث", max(1, bytesPerSecond / 1024))
    }

    static func percent(_ p: Double) -> String { return String(Int(max(0, min(1, p)) * 100)) + "%" }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy/MM/dd"
        return f
    }()

    static func date(_ t: Double) -> String {
        if t <= 0 { return "—" }
        return dateFormatter.string(from: Date(timeIntervalSince1970: t))
    }

    /// Time left, from the speed (only when both are known).
    static func remaining(bytesLeft: Int64, speed: Double) -> String {
        if bytesLeft <= 0 || speed < 1 { return "" }
        let s = Int(Double(bytesLeft) / speed)
        if s >= 3600 { return String(s / 3600) + " س " + String((s % 3600) / 60) + " د" }
        if s >= 60 { return String(s / 60) + " دقيقة" }
        return String(max(1, s)) + " ثانية"
    }
}

enum DownloadMessages {
    static let offline = "انقطع الاتصال بالإنترنت. يستمر التنزيل تلقائياً عند عودته."
    static let timeout = "انتهت مهلة الاتصال بخادم المشاهدة."
    static let unreachable = "تعذّر الوصول إلى خادم المشاهدة."
    static let expired = "انتهت صلاحية الرابط أو رفض الخادم الطلب."
    static let missing = "الملف لم يعد موجوداً على الخادم."
    static let server = "خادم المشاهدة يواجه مشكلة حالياً. حاول بعد قليل."
    static let storage = "المساحة المتوفرة على الجهاز غير كافية."
    static let write = "تعذّرت كتابة الملف على الجهاز."
    static let corrupt = "الملف الذي وصل تالف أو غير قابل للتشغيل، فلم يُحفظ."
    static let incomplete = "وصل الملف ناقصاً، فلم يُحفظ. أعد المحاولة."
    static let notVideo = "الرابط يشير إلى صفحة وليس إلى ملف قابل للتنزيل."
    static let unsupported = "هذا المصدر لا يدعم التنزيل."
    static let protected = "هذا المحتوى محمي ولا يمكن تنزيله."
    static let format = "صيغة هذا الملف غير مدعومة على الآيفون."
    static let noSource = "لا يوجد مصدر قابل للتنزيل لهذا العنوان الآن."
    static let interrupted = "توقف التنزيل. اضغط إعادة المحاولة."
    static let unknown = "تعذّر التنزيل."

    /// Error → (Arabic text, can the same download try again / continue).
    static func describe(domain: String, code: Int, http: Int) -> (text: String, retry: Bool) {
        if http == 401 || http == 403 { return (expired, true) }
        if http == 404 || http == 410 { return (missing, false) }
        if http == 416 { return (incomplete, true) }
        if http >= 500 && http < 600 { return (server, true) }
        if domain == NSURLErrorDomain {
            switch code {
            case NSURLErrorNotConnectedToInternet, NSURLErrorDataNotAllowed, NSURLErrorInternationalRoamingOff: return (offline, true)
            case NSURLErrorNetworkConnectionLost: return (offline, true)
            case NSURLErrorTimedOut: return (timeout, true)
            case NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed: return (unreachable, true)
            case NSURLErrorUserAuthenticationRequired, NSURLErrorNoPermissionsToReadFile: return (expired, true)
            case NSURLErrorFileDoesNotExist: return (missing, false)
            case NSURLErrorCannotCreateFile, NSURLErrorCannotWriteToFile, NSURLErrorCannotMoveFile: return (write, true)
            case NSURLErrorBadServerResponse: return (server, true)
            default: return (unknown, true)
            }
        }
        if domain == NSCocoaErrorDomain && (code == 640 || code == 4 || code == 512) { return (storage, false) }
        if domain == NSPOSIXErrorDomain && (code == 28 || code == 69) { return (storage, false) }
        if domain == "AVFoundationErrorDomain" {
            switch code {
            case -11828, -11829, -11853, -11822, -11850: return (unsupported, false)
            case -11831, -11835: return (protected, false)
            case -11807: return (storage, false)
            default: return (unknown, true)
            }
        }
        return (unknown, true)
    }
}

// MARK: - persistence

/// The list of records in one JSON file. Written atomically (a crash in the middle leaves the old file); a file that cannot be
/// read is kept aside as downloads.corrupt.json instead of being overwritten.
final class DownloadStore {
    private let queue = DispatchQueue(label: "cigram.downloads.store", qos: .utility)
    private var pending: Data?
    private var scheduled = false

    func load() -> [DownloadRecord] {
        let url = DownloadPaths.database
        guard let data = try? Data(contentsOf: url) else { return [] }
        if let list = try? JSONDecoder().decode([DownloadRecord].self, from: data) { return list }
        let bad = url.deletingLastPathComponent().appendingPathComponent("downloads.corrupt.json")
        try? FileManager.default.removeItem(at: bad)
        try? FileManager.default.moveItem(at: url, to: bad)
        NSLog("CIGRAM_CHECK downloads database unreadable, kept aside")
        return []
    }

    /// Saves soon (several calls in a row make one write).
    func save(_ list: [DownloadRecord]) {
        guard let data = try? JSONEncoder().encode(list) else { return }
        queue.async { [weak self] in
            guard let self = self else { return }
            self.pending = data
            if self.scheduled { return }
            self.scheduled = true
            self.queue.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                guard let self = self, let d = self.pending else { return }
                self.pending = nil
                self.scheduled = false
                do { try d.write(to: DownloadPaths.database, options: [.atomic]) } catch { NSLog("CIGRAM_CHECK downloads database not saved: %@", error.localizedDescription) }
            }
        }
    }

    /// Writes now (the app is about to be suspended).
    func flush(_ list: [DownloadRecord]) {
        guard let data = try? JSONEncoder().encode(list) else { return }
        queue.sync {
            self.pending = nil
            try? data.write(to: DownloadPaths.database, options: [.atomic])
        }
    }
}
