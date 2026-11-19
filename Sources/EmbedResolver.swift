import UIKit
import WebKit
import AVFoundation

// Turns an EMBED page (vidbolt.pro, vidfast, vidnest, videm ... any "player" web page) into a DIRECT stream link that
// AVPlayer can open. Ported from the Android CigramEmbedResolver:
//   stage 1: download the page (and its iframes) and look for a stream link in the text;
//   stage 2: only when stage 1 finds nothing (the page builds the link with JavaScript, every known embed site does):
//            load the page in a hidden WKWebView, press play, and take the first request that really is the video.
// On Android the hidden browser sees every request (shouldInterceptRequest). WebKit does not offer that on iOS, so the
// page's own fetch / XMLHttpRequest / <video> calls are observed by a script injected into every frame.
// The answer carries the headers the CDN expects, Kodi style ("https://cdn/x.m3u8|Referer=…&Origin=…&User-Agent=…").

struct SniffedSubtitle {
    let url: String
    let label: String
}

struct ResolvedStream {
    /// Playable by AVPlayer (may carry "|headers", may be a data: playlist written by the app).
    let url: String
    /// Every other usable way to play the same title, best first (the first one is `url` itself).
    let alternatives: [String]
    let subtitles: [SniffedSubtitle]
    let page: String
}

final class EmbedJob {
    fileprivate(set) var cancelled = false
    fileprivate var sniffer: StreamSniffer?

    func cancel() {
        cancelled = true
        sniffer?.abort()
        sniffer = nil
    }
}

final class EmbedResolver {
    static let shared = EmbedResolver()
    private var cache: [String: (stream: ResolvedStream, at: Date)] = [:]
    private let cacheTTL: TimeInterval = 8 * 60

    /// Forget a resolved stream (it stopped working: expired link): the next resolve starts again.
    func invalidate(page: String) { cache[StreamLink.clean(page)] = nil }

    /// Resolves in the background; `completion` runs on the main thread. `host` is the view the hidden browser lives in.
    @discardableResult
    func resolve(page rawPage: String, host: UIView, timeout: TimeInterval = 28,
                 completion: @escaping (ResolvedStream?, String) -> Void) -> EmbedJob {
        let page = StreamLink.clean(rawPage)
        let job = EmbedJob()
        if let c = cache[page], Date().timeIntervalSince(c.at) < cacheTTL {
            DispatchQueue.main.async { if !job.cancelled { completion(c.stream, "") } }
            return job
        }
        let finish: (ResolvedStream?, String) -> Void = { [weak self, weak job] stream, reason in
            DispatchQueue.main.async {
                guard let job = job, !job.cancelled else { return }
                if let s = stream { self?.cache[page] = (stream: s, at: Date()) }
                completion(stream, reason)
            }
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak job] in
            guard let job = job, !job.cancelled else { return }
            var found: String?
            // known embed sites build their stream with JavaScript: a plain download of the page would only find unrelated
            // files (trailers, ads), so they go straight to the hidden browser
            if !StreamLink.isKnownHost(page) {
                var seen = Set<String>()
                found = PageScrape.scrape(page: page, referer: page, seen: &seen)
            }
            if let f = found {
                finish(ResolvedStream(url: StreamLink.withHeaders(f, referer: page), alternatives: [], subtitles: [], page: page), "")
                return
            }
            DispatchQueue.main.async { [weak job] in
                guard let job = job, !job.cancelled else { return }
                let sniffer = StreamSniffer(page: page, host: host, timeout: timeout) { [weak job] stream, reason in
                    job?.sniffer = nil
                    if let s = stream { finish(s, ""); return }
                    guard StreamLink.isKnownHost(page) else { finish(nil, reason); return }
                    // last resort for a known embed site whose hidden browser found nothing: the page itself sometimes
                    // ships the link in plain text (pickMedia already rejects trailers, ads and samples)
                    DispatchQueue.global(qos: .userInitiated).async {
                        var seen = Set<String>()
                        if let hit = PageScrape.scrape(page: page, referer: page, seen: &seen) {
                            finish(ResolvedStream(url: StreamLink.withHeaders(hit, referer: page), alternatives: [], subtitles: [], page: page), "")
                        } else {
                            finish(nil, reason)
                        }
                    }
                }
                job.sniffer = sniffer
                sniffer.start()
            }
        }
        return job
    }
}

// MARK: - the hidden browser

final class StreamSniffer: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    private enum CType: Int { case pending = -1, master = 1, dash = 2, media = 3, bigMP4 = 4, ignored = 99 }

    private final class Cand {
        let url: String
        let origin: String
        var type = CType.pending
        var content = SegmentContent.unknown
        var masterHasAudio = false
        var text = ""
        init(url: String, origin: String) { self.url = url; self.origin = origin }
    }

    private static let bridge = "cgs"
    private static let queue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 3
        q.qualityOfService = .userInitiated
        return q
    }()

    private let page: String
    private let pageOrigin: String
    private weak var host: UIView?
    private let timeout: TimeInterval
    private var completion: ((ResolvedStream?, String) -> Void)?
    private var web: WKWebView?
    private var cands: [Cand] = []
    private var seen = Set<String>()
    private var unhinted = 0
    private var done = false
    private var firstAt = Date()
    private var qState = 0
    private var qMaster: String?
    private var subs: [SniffedSubtitle] = []
    private var committed = false
    private var allowedHost = ""
    private var timeoutTimer: Timer?

    init(page: String, host: UIView, timeout: TimeInterval, completion: @escaping (ResolvedStream?, String) -> Void) {
        self.page = page
        self.pageOrigin = StreamLink.origin(of: page)
        self.host = host
        self.timeout = timeout
        self.completion = completion
        super.init()
    }

    deinit { timeoutTimer?.invalidate() }

    // MARK: lifecycle

    func start() {
        guard let host = host, let url = URL(string: page) else { finish(nil, "engine: bad page link"); return }
        allowedHost = (url.host ?? "").lowercased()
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        cfg.applicationNameForUserAgent = "Version/17.4 Mobile/15E148 Safari/604.1"
        let ucc = cfg.userContentController
        ucc.add(WeakScriptHandler(self), name: StreamSniffer.bridge)
        ucc.addUserScript(WKUserScript(source: StreamSniffer.sniffScript, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        ucc.addUserScript(WKUserScript(source: StreamSniffer.autoplayScript, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        // the page is really rendered and played (small, almost transparent, behind everything, takes no touch)
        let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 320, height: 180), configuration: cfg)
        w.navigationDelegate = self
        w.uiDelegate = self
        w.alpha = 0.03
        w.isUserInteractionEnabled = false
        w.isOpaque = false
        w.backgroundColor = .black
        host.addSubview(w)          // on top (WebKit throttles a page that another view covers); alpha and touches make it invisible
        web = w
        w.load(URLRequest(url: url))
        timeoutTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
            guard let self = self, !self.done else { return }
            self.finish(nil, "timeout")
        }
    }

    func abort() {
        if done { return }
        done = true
        completion = nil
        teardown()
    }

    private func teardown() {
        timeoutTimer?.invalidate()
        timeoutTimer = nil
        if let w = web {
            w.stopLoading()
            w.navigationDelegate = nil
            w.uiDelegate = nil
            w.configuration.userContentController.removeScriptMessageHandler(forName: StreamSniffer.bridge)
            w.configuration.userContentController.removeAllUserScripts()
            w.loadHTMLString("", baseURL: nil)
            w.removeFromSuperview()
        }
        web = nil
    }

    private func finish(_ stream: ResolvedStream?, _ reason: String) {
        if done { return }
        done = true
        teardown()
        let c = completion
        completion = nil
        c?(stream, reason)
    }

    // MARK: what the page reports

    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
        guard !done, let body = message.body as? [String: Any], let t = body["t"] as? String, let u = body["u"] as? String else { return }
        let origin = (body["o"] as? String) ?? ""
        if t == "sub" {
            addSubtitle(u)
        } else if t == "m" {
            offer(u, origin: origin, hinted: (body["h"] as? Int) == 1)
        }
    }

    private func addSubtitle(_ u: String) {
        if subs.contains(where: { $0.url == u }) || subs.count >= 12 { return }
        let low = u.lowercased()
        var label = "ترجمة " + String(subs.count + 1)
        let ar = ["/ar.", "_ar.", "-ar.", "/ar_", "arabic", "/ara", "_ara", ".ar.", "lang=ar", "label=ar"]
        let en = ["/en.", "_en.", "-en.", "/en_", "english", "/eng", "_eng", ".en.", "lang=en", "label=en"]
        if ar.contains(where: { low.contains($0) }) { label = "العربية" } else if en.contains(where: { low.contains($0) }) { label = "English" }
        subs.append(SniffedSubtitle(url: u, label: label))
    }

    private func headers(origin: String) -> [String: String] {
        var h: [String: String] = ["Accept": "*/*", "Accept-Language": "en-US,en;q=0.9"]
        let o = origin.isEmpty ? pageOrigin : origin
        if !o.isEmpty { h["Referer"] = o + "/"; h["Origin"] = o }
        return h
    }

    /// A request of the page that may be the video: classify it now, and decide soon (or at once for the best kinds).
    private func offer(_ url: String, origin: String, hinted: Bool) {
        let low = url.lowercased()
        guard low.hasPrefix("http://") || low.hasPrefix("https://") else { return }
        if low.contains("trailer") || low.contains("/ads/") || low.contains("preroll") || low.contains("advert")
            || low.contains(".mp4.jpg") || low.contains("doubleclick") || low.contains("googlesyndication") || low.contains("adservice") { return }
        let h = StreamLink.host(of: url)
        if WebShield.blockedHosts.contains(where: { h == $0 || h.hasSuffix("." + $0) }) { return }
        if !hinted {
            if unhinted >= 60 { return }
            unhinted += 1
        }
        if !seen.insert(url).inserted { return }
        let c = Cand(url: url, origin: origin)
        cands.append(c)
        if cands.count == 1 {
            firstAt = Date()
            // first sign of a video: give the page a moment to ask for the MASTER playlist too
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in self?.decide() }
        }
        StreamSniffer.queue.addOperation { [weak self] in
            guard let self = self else { return }
            let r = self.classify(c)
            DispatchQueue.main.async {
                if self.done { return }
                c.type = r.type
                c.content = r.content
                c.masterHasAudio = r.masterAudio
                c.text = r.text
                // complete answers (picture AND sound) need no more waiting
                let complete = (c.type == .master && c.masterHasAudio) || c.type == .bigMP4
                    || (c.type == .media && (c.content == .muxed || c.content == .audio))
                // after the waiting window every newly classified candidate may be the one that finishes the decision
                if complete || Date().timeIntervalSince(self.firstAt) >= 7 { self.decide() }
            }
        }
    }

    // MARK: classify (background)

    private func classify(_ c: Cand) -> (type: CType, content: SegmentContent, masterAudio: Bool, text: String) {
        let skip: (type: CType, content: SegmentContent, masterAudio: Bool, text: String) = (CType.ignored, SegmentContent.unknown, false, "")
        let h = headers(origin: c.origin)
        guard let r = StreamNet.get(c.url, headers: h, range: "bytes=0-65535", maxBytes: 65536, timeout: 8),
              r.status >= 200, r.status < 400 else { return skip }
        let type = r.header("Content-Type").lowercased()
        var total: Int64 = -1
        let cr = r.header("Content-Range")
        if let slash = cr.lastIndex(of: "/") {
            total = Int64(String(cr[cr.index(after: slash)...]).trimmingCharacters(in: .whitespaces)) ?? -1
        } else {
            total = Int64(r.header("Content-Length").trimmingCharacters(in: .whitespaces)) ?? -1
        }
        let bytes = [UInt8](r.data)
        if bytes.count < 8 { return skip }
        let head = (String(bytes: bytes.prefix(64), encoding: .isoLatin1) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if head.hasPrefix("#EXTM3U") || type.contains("mpegurl") {
            let text = String(decoding: bytes, as: UTF8.self)
            if HLS.isMaster(text) {
                return (.master, .unknown, masterBringsAudio(c, text), text)
            }
            if HLS.isMedia(text) {
                return (.media, segmentContent(playlistURL: c.url, headers: h, text: text), false, text)
            }
            return skip
        }
        let first400 = String(bytes: bytes.prefix(400), encoding: .isoLatin1) ?? ""
        if type.contains("dash+xml") || first400.contains("<MPD") { return (.dash, .unknown, false, "") }
        let ftyp = bytes.count > 7 && bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70
        if ftyp || type.hasPrefix("video/mp4") || type.hasPrefix("video/webm") || type.hasPrefix("video/x-matroska") {
            // a whole film is big; the small mp4 pieces a page loads (segments, previews, ads) are not
            if total > 30 * 1024 * 1024 { return (CType.bigMP4, SegmentContent.unknown, false, "") }
            return skip
        }
        return skip
    }

    /// True when a master playlist brings sound: it declares an audio group, or its first quality's pieces really carry audio.
    private func masterBringsAudio(_ master: Cand, _ text: String) -> Bool {
        if text.contains("TYPE=AUDIO") { return true }
        guard let variant = HLS.firstVariantURI(text), let abs = StreamLink.absolute(base: master.url, rel: variant) else { return true }
        let h = headers(origin: master.origin)
        guard let r = StreamNet.get(abs, headers: h, maxBytes: 65536, timeout: 8), r.status / 100 == 2 else { return true }
        let kind = segmentContent(playlistURL: abs, headers: h, text: String(decoding: r.data, as: UTF8.self))
        return kind != .video     // only a confirmed picture-only master lacks sound
    }

    /// What the pieces of a media playlist carry, read from the init section or the first piece.
    private func segmentContent(playlistURL: String, headers h: [String: String], text: String) -> SegmentContent {
        let parts = HLS.firstSegment(text)
        guard let ref = parts.map ?? parts.segment else { return .unknown }
        if HLS.isAudioName(ref) { return .audio }
        guard let abs = StreamLink.absolute(base: playlistURL, rel: ref) else { return .unknown }
        guard let r = StreamNet.get(abs, headers: h, range: "bytes=0-262143", maxBytes: 262144, timeout: 8),
              r.status >= 200, r.status < 400 else { return .unknown }
        return HLS.analyze([UInt8](r.data))
    }

    // MARK: decide

    /// Picks what the player gets, SOUND INCLUDED:
    ///  1. a master playlist that brings sound, a playlist whose pieces carry picture AND sound, or a whole mp4 file;
    ///  2. otherwise picture and sound found as separate playlists are joined into one master;
    ///  3. while the sound has not shown up yet, wait for it (up to ~7 s after the first candidate);
    ///  4. last resort: the best picture found.
    private func decide() {
        if done { return }
        var master: Cand?, masterNoSound: Cand?, muxed: Cand?, mp4: Cand?
        var videoOnly: Cand?, audio: Cand?, unknownMedia: Cand?, loose: Cand?
        var pending = false
        for c in cands {
            switch c.type {
            case .pending:
                pending = true
                if loose == nil && c.url.lowercased().contains("m3u8") { loose = c }
            case .master:
                if c.masterHasAudio { if master == nil { master = c } } else if masterNoSound == nil { masterNoSound = c }
            case .dash, .ignored:
                break
            case .bigMP4:
                if mp4 == nil { mp4 = c }
            case .media:
                switch c.content {
                case .muxed: if muxed == nil { muxed = c }
                case .audio: if audio == nil { audio = c }
                case .video: if videoOnly == nil { videoOnly = c }
                case .unknown: if unknownMedia == nil { unknownMedia = c }
                }
            }
        }
        var pick: Cand? = master ?? muxed ?? mp4
        var stream: String? = pick?.url
        if pick == nil, let a = audio {
            let picture: Cand? = masterNoSound ?? videoOnly ?? unknownMedia
            if let p = picture, p !== masterNoSound {
                // look for the other resolutions of this title (video_1080p, video_480p ...) first
                if qState == 0 {
                    qState = 1
                    let urls = cands.filter { $0.type == .media }.map { $0.url }
                    let audioURL = a.url
                    let origin = a.origin
                    DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                        let m = self?.qualityMaster(urls, audioURL: audioURL, origin: origin)
                        DispatchQueue.main.async {
                            guard let self = self else { return }
                            self.qMaster = m
                            self.qState = 2
                            self.decide()
                        }
                    }
                    return
                }
                if qState == 1 {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.decide() }
                    return
                }
            }
            if let p = picture {
                let joined: String?
                if p === masterNoSound {
                    joined = HLS.joinedMaster(from: p.text, masterURL: p.url, audioURL: a.url)
                } else {
                    joined = qMaster ?? HLS.joinedMaster(videoURL: p.url, audioURL: a.url)
                }
                if let j = joined {
                    pick = p
                    stream = StreamSniffer.dataPlaylist(j)
                }
            }
        }
        if pick == nil {
            let waited = Date().timeIntervalSince(firstAt)
            if (pending || audio == nil) && waited < 7 {
                // the sound playlist is usually asked for right after the picture: give it a little longer
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in self?.decide() }
                return
            }
            pick = masterNoSound ?? videoOnly ?? unknownMedia ?? loose
            if pick == nil { return }       // nothing usable yet: the timeout ends it
            stream = pick?.url
        }
        guard let chosen = pick, let main = stream else { return }
        let ref = chosen.origin.isEmpty ? pageOrigin : chosen.origin
        let mainLink = StreamLink.withHeaders(main, referer: ref)
        // every other usable way to play the same title, best first: if the sound turns out to be missing the player
        // tries them one after the other
        var alts: [String] = [mainLink]
        let order: [Cand?] = [master, muxed, mp4, masterNoSound, videoOnly, unknownMedia]
        for c in order {
            guard let c = c else { continue }
            let u = StreamLink.withHeaders(c.url, referer: ref)
            if !alts.contains(u) { alts.append(u) }
        }
        if let a = audio {
            let pictures: [Cand?] = [masterNoSound, videoOnly, unknownMedia]
            for c in pictures {
                guard let c = c else { continue }
                let joined: String? = c === masterNoSound ? HLS.joinedMaster(from: c.text, masterURL: c.url, audioURL: a.url)
                    : HLS.joinedMaster(videoURL: c.url, audioURL: a.url)
                guard let j = joined else { continue }
                let u = StreamLink.withHeaders(StreamSniffer.dataPlaylist(j), referer: ref)
                if !alts.contains(u) { alts.append(u) }
            }
        }
        finish(ResolvedStream(url: mainLink, alternatives: alts, subtitles: subs, page: page), "")
    }

    /// A playlist written by the app, as a data: link (the player writes it to a file before opening it).
    static func dataPlaylist(_ text: String) -> String {
        return "data:application/vnd.apple.mpegurl;base64," + Data(text.utf8).base64EncodedString()
    }

    /// Sites such as vidbolt offer one playlist per resolution under names like video_720p.m3u8. Only the one the page
    /// played is seen; the others are looked for by name and each one that answers with a real playlist whose first piece
    /// exists is added. The result is ONE master playlist with every resolution and the separate sound.
    private func qualityMaster(_ mediaURLs: [String], audioURL: String, origin: String) -> String? {
        let template = HLS.heightTemplate(from: mediaURLs, excluding: audioURL) ?? HLS.templateBesideAudio(audioURL)
        guard let tpl = template else { return nil }
        let h = headers(origin: origin)
        var verified: [Int] = []
        let lock = NSLock()
        let group = DispatchGroup()
        for height in HLS.ladder {
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                defer { group.leave() }
                let url = tpl.replacingOccurrences(of: "{H}", with: String(height))
                guard let r = StreamNet.get(url, headers: h, maxBytes: 262144, timeout: 6), r.status >= 200, r.status < 300 else { return }
                let text = r.text
                guard text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U"), text.contains("#EXTINF") else { return }
                guard let first = HLS.firstSegment(text).segment, let abs = StreamLink.absolute(base: url, rel: first) else { return }
                let st = StreamNet.status(abs, headers: h)
                if st >= 200 && st < 300 {
                    lock.lock()
                    verified.append(height)
                    lock.unlock()
                }
            }
        }
        _ = group.wait(timeout: .now() + 9)
        lock.lock()
        let heights = verified
        lock.unlock()
        return HLS.qualityMaster(template: tpl, heights: heights, audioURL: audioURL)
    }

    // MARK: navigation (stay on the player: pop-ups and redirects to other sites never open)

    func webView(_ w: WKWebView, didCommit n: WKNavigation!) { committed = true }

    func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        let scheme = (url.scheme ?? "").lowercased()
        if !["http", "https", "about", "blob", "data", "javascript"].contains(scheme) { decisionHandler(.cancel); return }
        guard let target = action.targetFrame else { decisionHandler(.cancel); return }
        if !target.isMainFrame { decisionHandler(.allow); return }
        let h = (url.host ?? "").lowercased()
        if h.isEmpty || scheme == "about" { decisionHandler(.allow); return }
        if StreamSniffer.sameSite(h, allowedHost) { decisionHandler(.allow); return }
        if !committed && action.navigationType == .other {      // the embed link's own server redirect (a mirror)
            allowedHost = h
            decisionHandler(.allow)
            return
        }
        decisionHandler(.cancel)
    }

    static func sameSite(_ a: String, _ b: String) -> Bool {
        if a == b || a.hasSuffix("." + b) || b.hasSuffix("." + a) { return true }
        let pa = a.split(separator: "."), pb = b.split(separator: ".")
        return pa.count >= 2 && pb.count >= 2 && pa.suffix(2) == pb.suffix(2)
    }

    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration, for a: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? { return nil }
    func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) { completionHandler() }
    func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) { completionHandler(false) }
    func webView(_ w: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) { completionHandler(nil) }

    func webViewWebContentProcessDidTerminate(_ w: WKWebView) {
        if !done && cands.isEmpty { finish(nil, "engine: web process ended") }
    }

    func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) {
        let ns = e as NSError
        if ns.code == NSURLErrorCancelled || (ns.domain == "WebKitErrorDomain" && ns.code == 102) { return }
        if !committed && cands.isEmpty { finish(nil, "load failed: " + String(ns.code)) }
    }

    // MARK: the scripts injected into every frame of the page

    /// Document start: reports every request that may be the video (fetch / XMLHttpRequest / <video src> / resource list).
    static let sniffScript = """
    (function(){
      if (window.__cgSniff) return; window.__cgSniff = 1;
      var post = function(m){ try { window.webkit.messageHandlers.cgs.postMessage(m); } catch (e) {} };
      var seen = {};
      var org = function(){ try { return location.origin || ''; } catch (e) { return ''; } };
      var abs = function(u){ try { return new URL(String(u), location.href).href; } catch (e) { return ''; } };
      var STATIC = /\\.(js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf|map|html?|json|txt|xml)(\\?|#|$)/i;
      var SUB = /\\.(vtt|srt)(\\?|#|$)/i;
      var HINT = /m3u8|\\.mpd|\\.mp4|\\/playlist|\\/master|\\/manifest/i;
      var offer = function(raw, viaRequest){
        try {
          if (!raw) return;
          var u = abs(raw);
          if (u.indexOf('http') !== 0 || seen[u]) return;
          if (SUB.test(u)) { seen[u] = 1; post({t:'sub', u:u, o:org()}); return; }
          if (HINT.test(u)) { seen[u] = 1; post({t:'m', u:u, o:org(), h:1}); return; }
          if (viaRequest && !STATIC.test(u)) { seen[u] = 1; post({t:'m', u:u, o:org(), h:0}); }
        } catch (e) {}
      };
      try {
        var of = window.fetch;
        if (of) window.fetch = function(i){ try { offer(typeof i === 'string' ? i : (i && i.url), true); } catch (e) {} return of.apply(this, arguments); };
      } catch (e) {}
      try {
        var ox = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(m, u){ try { offer(u, true); } catch (e) {} return ox.apply(this, arguments); };
      } catch (e) {}
      try {
        var ds = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
        if (ds && ds.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
          get: ds.get, configurable: true,
          set: function(v){ try { offer(v, false); } catch (e) {} return ds.set.call(this, v); }
        });
      } catch (e) {}
      var scan = function(){
        try {
          var es = performance.getEntriesByType('resource');
          for (var i = 0; i < es.length; i++) offer(es[i].name, false);
          var vs = document.querySelectorAll('video');
          for (var j = 0; j < vs.length; j++) {
            var s = vs[j].currentSrc || vs[j].src;
            if (s && s.indexOf('http') === 0) offer(s, false);
            var ts = vs[j].querySelectorAll('track');
            for (var k = 0; k < ts.length; k++) { if (ts[k].src) offer(ts[k].src, false); }
          }
        } catch (e) {}
      };
      setInterval(scan, 1000);
    })();
    """

    /// Document end: presses play in the page and in every frame of it, several times while it loads (muted: nobody listens).
    static let autoplayScript = """
    (function(){
      if (window.__cgAuto) return; window.__cgAuto = 1;
      var n = 0;
      var iv = setInterval(function(){
        n++; if (n > 16) { clearInterval(iv); return; }
        try {
          var v = document.querySelectorAll('video');
          for (var i = 0; i < v.length; i++) {
            v[i].muted = true;
            var p = v[i].play && v[i].play();
            if (p && p.catch) p.catch(function(){});
          }
          var b = document.querySelector('.vjs-big-play-button,.jw-icon-display,.plyr__control--overlaid,#play,.play,.play-button,.play-btn,[class*="play-btn"],[class*="PlayButton"],[class*="play_button"],[aria-label*="lay"],button[title*="lay"]');
          if (b) b.click();
          var el = document.elementFromPoint((document.documentElement.clientWidth || 300) / 2, (document.documentElement.clientHeight || 200) / 2);
          if (el && el.click) el.click();
        } catch (e) {}
      }, 1500);
    })();
    """
}

// MARK: - the asset AVPlayer opens

enum PlayerAsset {
    /// "https://cdn/x.m3u8|Referer=…" or a data: playlist → an asset with the headers the CDN expects.
    static func make(_ stream: String) -> AVURLAsset? {
        let clean = StreamLink.clean(stream)
        var headers = StreamLink.headers(of: stream)
        if headers["User-Agent"] == nil { headers["User-Agent"] = StreamLink.userAgent }
        var url: URL?
        if clean.hasPrefix("data:application/vnd.apple.mpegurl") {
            url = writePlaylist(clean)
        } else {
            url = URL(string: clean) ?? URL(string: clean.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")
        }
        guard let u = url else { return nil }
        let options: [String: Any] = ["AVURLAssetHTTPHeaderFieldsKey": headers]
        return AVURLAsset(url: u, options: options)
    }

    /// The text of the master playlist behind a link (nil for anything that is not a master playlist). Background threads only.
    static func masterText(_ stream: String) -> (text: String, base: String)? {
        let clean = StreamLink.clean(stream)
        if clean.hasPrefix("data:application/vnd.apple.mpegurl") {
            guard let t = decode(clean), HLS.isMaster(t) else { return nil }
            return (t, clean)
        }
        guard StreamLink.kind(of: stream) == .hls || StreamLink.kind(of: stream) == nil else { return nil }
        var h = StreamLink.headers(of: stream)
        if h["Accept"] == nil { h["Accept"] = "*/*" }
        guard let r = StreamNet.get(clean, headers: h, maxBytes: 262144, timeout: 8), r.status >= 200, r.status < 300 else { return nil }
        let t = r.text
        guard HLS.isMaster(t) else { return nil }
        return (t, r.finalURL)
    }

    private static func decode(_ dataURL: String) -> String? {
        guard let comma = dataURL.firstIndex(of: ",") else { return nil }
        let b64 = String(dataURL[dataURL.index(after: comma)...])
        guard let d = Data(base64Encoded: b64), let s = String(data: d, encoding: .utf8) else { return nil }
        return s
    }

    /// AVPlayer opens a playlist from a file (its pieces are absolute https links). Old files are removed.
    private static func writePlaylist(_ dataURL: String) -> URL? {
        guard let text = decode(dataURL) else { return nil }
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("cigram-play", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) {
            for f in files {
                let m = (try? f.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
                if Date().timeIntervalSince(m) > 24 * 3600 { try? FileManager.default.removeItem(at: f) }
            }
        }
        var h: UInt64 = 0xcbf29ce484222325
        for b in text.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        let file = dir.appendingPathComponent(String(h, radix: 16) + ".m3u8")
        do { try text.write(to: file, atomically: true, encoding: .utf8) } catch { return nil }
        return file
    }
}
