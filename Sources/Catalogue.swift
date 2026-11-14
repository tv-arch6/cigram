import UIKit
import Foundation

/// Same backend the Android app uses (CigramCatalogSync -> /movies).
enum Backend {
    static let admin = "https://cigram-admin-api.wwq-mixtv.workers.dev"
    static let moviesURL = URL(string: admin + "/movies")!
    // Same embed servers as CigramVidResolver; %@ = tmdb id.
    static let movieServers = ["https://vidfast.vc/movie/%@", "https://vidbolt.pro/movie/%@",
                               "https://vidnest.fun/movie/%@", "https://videm.xyz/movie/%@"]
    static let tvServers    = ["https://vidfast.vc/tv/%@/%ld/%ld", "https://vidbolt.pro/tv/%@/%ld/%ld",
                               "https://vidnest.fun/tv/%@/%ld/%ld", "https://videm.xyz/tv/%@/%ld/%ld"]
}

struct Title {
    let id: String
    let name: String
    let poster: URL?
    let isSeries: Bool
    let year: String
    let raw: [String: Any]
}

/// Tolerant parser: the Worker schema changed several times (see UPDATE-18.4), so every key has fallbacks.
enum CatalogueParser {
    private static func str(_ d: [String: Any], _ keys: [String]) -> String? {
        for k in keys {
            if let s = d[k] as? String, !s.isEmpty { return s }
            if let n = d[k] as? NSNumber { return n.stringValue }
        }
        return nil
    }

    static func parse(_ data: Data) -> [Title] {
        var d = data
        if d.starts(with: [0xEF, 0xBB, 0xBF]) { d = d.dropFirst(3) }          // BOM
        guard let json = try? JSONSerialization.jsonObject(with: d, options: [.allowFragments]) else { return [] }
        var list: [[String: Any]] = []
        if let arr = json as? [[String: Any]] { list = arr }
        else if let obj = json as? [String: Any] {
            for k in ["movies", "items", "records", "data", "catalogue", "catalog"] {
                if let arr = obj[k] as? [[String: Any]] { list = arr; break }
            }
        }
        return list.compactMap { r in
            guard let id = str(r, ["tmdb_id", "tmdbId", "tmdb", "id"]),
                  let name = str(r, ["title", "name", "title_en", "title_ar", "t"]) else { return nil }
            let posterStr = str(r, ["poster", "poster_url", "posterUrl", "image", "img", "cover", "p"])
            let type = (str(r, ["type", "kind", "media_type", "category"]) ?? "").lowercased()
            let series = type.contains("tv") || type.contains("series") || type.contains("مسلسل")
                || r["seasons"] != nil
            var url: URL? = nil
            if let p = posterStr {
                url = p.hasPrefix("http") ? URL(string: p) : URL(string: "https://image.tmdb.org/t/p/w342" + (p.hasPrefix("/") ? p : "/" + p))
            }
            return Title(id: id, name: name, poster: url, isSeries: series,
                         year: str(r, ["year", "release_year", "y"]) ?? "", raw: r)
        }
    }
}

final class CatalogueClient {
    static let shared = CatalogueClient()
    private let cacheURL: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("catalogue.json")
    }()

    /// Cached copy first (instant), network refresh after; an empty answer never replaces a good cache.
    func load(_ done: @escaping ([Title], _ fromCache: Bool) -> Void) {
        if let cached = try? Data(contentsOf: cacheURL) {
            let t = CatalogueParser.parse(cached)
            if !t.isEmpty { DispatchQueue.main.async { done(t, true) } }
        }
        var req = URLRequest(url: Backend.moviesURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: req) { [cacheURL] data, resp, _ in
            guard let data = data, (resp as? HTTPURLResponse)?.statusCode == 200 else { return }
            let t = CatalogueParser.parse(data)
            guard !t.isEmpty else { return }
            try? data.write(to: cacheURL, options: .atomic)
            DispatchQueue.main.async { done(t, false) }
        }.resume()
    }
}

final class ImageLoader {
    static let shared = ImageLoader()
    private let cache = NSCache<NSURL, UIImage>()
    func load(_ url: URL, _ done: @escaping (UIImage?) -> Void) -> URLSessionDataTask? {
        if let img = cache.object(forKey: url as NSURL) { done(img); return nil }
        let t = URLSession.shared.dataTask(with: url) { [weak self] d, _, _ in
            let img = d.flatMap { UIImage(data: $0) }
            if let img = img { self?.cache.setObject(img, forKey: url as NSURL) }
            DispatchQueue.main.async { done(img) }
        }
        t.resume()
        return t
    }
}
