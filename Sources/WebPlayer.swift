import UIKit
import WebKit
import AVFoundation

/// THE BROWSER PLAYER (kept as the fallback of the native player, see PlayerViewController.swift).
/// Plays a film / an episode through its own VidBolt link (exactly what the admin published), with the other embed servers
/// as automatic fallbacks. The title plays INSIDE the embed page (no stream extraction).
/// Since 1.1.3 the main player is native (AVPlayer, like the Android ExoPlayer player); this one opens only when the native
/// player could not get a stream out of any server, or when the viewer picks «المشغّل البديل».
///
/// 1.1.2 — why the film «froze» (يعلگ) and what changed:
///  1. The old auto-play clicked the first element whose class contained "play" — on these pages that is the player box
///     itself, so the clicks at 0 s / 1.2 s / 3 s toggled play → pause. Now: `video.play()` once per video, and the
///     player's own big-play button is pressed at most once, only when the browser refused to start the video.
///  2. A tap recognizer on the whole screen competed with the web view's own tap recognizers: taps on the embed's
///     play / seek / subtitle buttons could be swallowed. It now recognizes together with them and ignores our buttons.
///  3. Siri, a call, Control Center or an alarm interrupt the audio session and WebKit pauses the video; nothing resumed it.
///     A pause the viewer did not make (no touch in that frame just before it) is now resumed when the interruption ends
///     and when the app comes back to the front — in every frame (the video lives in the embed's iframe).
///  4. The embed's ad layer: pop-ups / pop-unders / redirects are refused (as before) and known ad networks are blocked
///     (content rules); floating ad overlays laid over the player (fixed, high z-index, no video inside, not a player
///     control) are removed as they appear.
///  5. The script bridge held the controller strongly (a leak that kept the page — and its audio — alive after closing).
///  6. Portrait: the bar sits under the Dynamic Island and the page starts under the bar (nothing hides the embed's own
///     buttons). Landscape: the bar floats and lets every touch outside its buttons through.
final class WebPlayerViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, UIGestureRecognizerDelegate {
    private let show: Show
    private var episode: CatalogItem?
    /// Called after this player closed (the native player that opened it closes too).
    var onClose: (() -> Void)?
    private var web: WKWebView!
    private var sources: [URL] = []
    private var index = 0
    private var allowedHost = ""
    private var committed = false
    private var playing = false
    private var sawVideo = false
    private var shield: WKContentRuleList?
    private var shieldOn = false
    private var shieldRetried = false
    private var videoFrames: [WKFrameInfo] = []
    private let bar = PassThroughView()
    private let barShade = UIView()
    private let close = UIButton(type: .system)
    private let titleLabel = UILabel()
    private let nextButton = UIButton(type: .system)
    private let server = UIButton(type: .system)
    private let hint = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)
    private var hideTimer: Timer?
    private var hintTimer: Timer?
    private static let bridge = "cg"

    init(show: Show, episode: CatalogItem?) {
        self.show = show
        self.episode = episode
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    deinit {
        NotificationCenter.default.removeObserver(self)
        hideTimer?.invalidate()
        hintTimer?.invalidate()
    }

    override var prefersStatusBarHidden: Bool { return true }
    override var prefersHomeIndicatorAutoHidden: Bool { return true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { return .allButUpsideDown }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { return .landscapeRight }

    private var isPortrait: Bool { return view.bounds.height >= view.bounds.width }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        // activating the audio session can block for a moment (it interrupts other apps): never on the main thread
        let session = AVAudioSession.sharedInstance()
        DispatchQueue.global(qos: .userInitiated).async {
            try? session.setCategory(.playback, mode: .moviePlayback, options: [])
            try? session.setActive(true)
        }

        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.allowsPictureInPictureMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        cfg.applicationNameForUserAgent = "Version/17.4 Mobile/15E148 Safari/604.1"
        let ucc = cfg.userContentController
        ucc.add(WeakScriptHandler(self), name: WebPlayerViewController.bridge)
        ucc.addUserScript(WKUserScript(source: PlayerScripts.guardScript, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        ucc.addUserScript(WKUserScript(source: PlayerScripts.startScript, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        web = WKWebView(frame: view.bounds, configuration: cfg)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.backgroundColor = .black
        web.isOpaque = false
        web.scrollView.backgroundColor = .black
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.scrollView.bounces = false
        web.allowsLinkPreview = false
        view.addSubview(web)

        spinner.color = .white
        spinner.hidesWhenStopped = true
        view.addSubview(spinner)

        hint.text = "إذا لم يبدأ العرض اضغط «مصدر آخر»"
        hint.textColor = .white
        hint.font = Theme.font(13.5, .semibold)
        hint.textAlignment = .center
        hint.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        hint.layer.cornerRadius = 10
        hint.clipsToBounds = true
        hint.alpha = 0
        view.addSubview(hint)

        barShade.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        barShade.isUserInteractionEnabled = false
        view.addSubview(barShade)
        view.addSubview(bar)
        close.setImage(UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .bold)), for: .normal)
        close.tintColor = .white
        close.accessibilityLabel = "إغلاق"
        close.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        titleLabel.textColor = .white
        titleLabel.font = Theme.font(15, .bold)
        titleLabel.textAlignment = .center
        titleLabel.adjustsFontSizeToFitWidth = true
        titleLabel.minimumScaleFactor = 0.75
        nextButton.setTitle("الحلقة التالية ›", for: .normal)
        nextButton.setTitleColor(.white, for: .normal)
        nextButton.titleLabel?.font = Theme.font(14, .bold)
        nextButton.addTarget(self, action: #selector(nextTapped), for: .touchUpInside)
        server.setTitleColor(Theme.text2, for: .normal)
        server.titleLabel?.font = Theme.font(13, .semibold)
        server.addTarget(self, action: #selector(serverTapped), for: .touchUpInside)
        [close, nextButton, server].forEach { bar.addSubview($0) }
        bar.addSubview(titleLabel)

        // shows the bar; recognizes TOGETHER with the web view's own taps (never takes them away from the embed)
        let tap = UITapGestureRecognizer(target: self, action: #selector(showBar))
        tap.cancelsTouchesInView = false
        tap.delaysTouchesBegan = false
        tap.delaysTouchesEnded = false
        tap.delegate = self
        view.addGestureRecognizer(tap)

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(audioInterrupted(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(becameActive), name: UIApplication.didBecomeActiveNotification, object: nil)

        WebShield.rules { [weak self] list in
            guard let self = self else { return }
            self.shield = list
            self.setShield(true)
            self.start()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let b = view.bounds
        let s = view.safeAreaInsets
        let barH: CGFloat = 52
        let side = max(8, max(s.left, s.right))
        if isPortrait {
            // a solid bar under the Dynamic Island; the page starts below it (the embed's buttons stay reachable)
            barShade.frame = CGRect(x: 0, y: 0, width: b.width, height: s.top + barH)
            barShade.backgroundColor = .black
            bar.frame = CGRect(x: 0, y: s.top, width: b.width, height: barH)
            web.frame = CGRect(x: 0, y: s.top + barH, width: b.width, height: max(0, b.height - s.top - barH - s.bottom))
            bar.alpha = 1
            barShade.alpha = 1
        } else {
            barShade.frame = CGRect(x: 0, y: 0, width: b.width, height: barH)
            barShade.backgroundColor = UIColor.black.withAlphaComponent(0.6)
            bar.frame = CGRect(x: 0, y: 0, width: b.width, height: barH)
            web.frame = CGRect(x: s.left, y: 0, width: b.width - s.left - s.right, height: b.height)
        }
        let w = b.width
        close.frame = CGRect(x: side, y: 4, width: 44, height: 44)
        server.frame = CGRect(x: side + 48, y: 4, width: 96, height: 44)
        nextButton.frame = CGRect(x: w - side - 130, y: 4, width: 130, height: 44)
        let tx = side + 148
        let tw = max(40, w - tx - (nextButton.isHidden ? side + 8 : side + 136))
        titleLabel.frame = CGRect(x: tx, y: 4, width: tw, height: 44)
        spinner.center = CGPoint(x: web.frame.midX, y: web.frame.midY)
        hint.frame = CGRect(x: (w - 280) / 2, y: web.frame.maxY - 64, width: 280, height: 36)
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.showBar() }
    }

    // MARK: sources

    private func start() {
        var text = show.title
        if let e = episode { text += " · م" + String(e.season) + " ح" + String(e.episode) }
        titleLabel.text = text
        nextButton.isHidden = nextEpisode() == nil
        sources = buildSources()
        index = 0
        load()
        showBar()
        view.setNeedsLayout()
    }

    /// The published link first, then the same title on the other embed servers (TMDB id).
    private func buildSources() -> [URL] {
        var out: [URL] = []
        let own = (episode?.video ?? (show.isSeries ? "" : show.head.video)).replacingOccurrences(of: "vidbolt.xyz", with: "vidbolt.pro")
        if own.hasPrefix("http"), let u = URL(string: own) { out.append(u) }
        let tmdb = show.head.tmdb.isEmpty ? (episode?.tmdb ?? "") : show.head.tmdb
        if !tmdb.isEmpty {
            if let e = episode {
                for f in Backend.tvServers {
                    if let u = URL(string: String(format: f, tmdb, e.season, e.episode)), !out.contains(u) { out.append(u) }
                }
            } else if !show.isSeries {
                for f in Backend.movieServers {
                    if let u = URL(string: String(format: f, tmdb)), !out.contains(u) { out.append(u) }
                }
            }
        }
        return out
    }

    /// The ad shield on / off for the page (off only for a source whose player would not even build with it).
    private func setShield(_ on: Bool) {
        guard let l = shield, on != shieldOn else { return }
        let ucc = web.configuration.userContentController
        if on { ucc.add(l) } else { ucc.remove(l) }
        shieldOn = on
    }

    private func load(keepShieldState: Bool = false) {
        if !keepShieldState { shieldRetried = false; setShield(true) }
        guard index < sources.count else {
            spinner.stopAnimating()
            let a = UIAlertController(title: (episode == nil ? "الفيلم" : "الحلقة") + " غير متاح الآن", message: "جرّب بعد قليل.", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "رجوع", style: .cancel) { [weak self] _ in self?.closeTapped() })
            present(a, animated: true)
            return
        }
        let u = sources[index]
        allowedHost = (u.host ?? "").lowercased()
        committed = false
        playing = false
        sawVideo = false
        videoFrames.removeAll()
        server.setTitle(sources.count > 1 ? "مصدر آخر (" + String(index + 1) + "/" + String(sources.count) + ")" : "إعادة التحميل", for: .normal)
        spinner.startAnimating()
        hideHint()
        hintTimer?.invalidate()
        hintTimer = Timer.scheduledTimer(withTimeInterval: 18, repeats: false) { [weak self] _ in
            guard let self = self, !self.playing else { return }
            // some players refuse to build at all when their ad scripts are blocked: such a source (no video element in
            // any frame after 18 s) is loaded once more WITHOUT the shield before the viewer is told to switch
            if !self.sawVideo && self.shieldOn && !self.shieldRetried {
                NSLog("CIGRAM_CHECK player: no video with the shield, reloading the source without it")
                self.shieldRetried = true
                self.setShield(false)
                self.load(keepShieldState: true)
                return
            }
            UIView.animate(withDuration: 0.25) { self.hint.alpha = 1 }
            self.showBar()
        }
        web.load(URLRequest(url: u))
    }

    private func hideHint() { if hint.alpha > 0 { UIView.animate(withDuration: 0.2) { self.hint.alpha = 0 } } }

    private func nextEpisode() -> CatalogItem? {
        guard let e = episode, let i = show.episodes.firstIndex(where: { $0.season == e.season && $0.episode == e.episode }),
              i + 1 < show.episodes.count else { return nil }
        return show.episodes[i + 1]
    }

    // MARK: actions

    @objc private func closeTapped() {
        hintTimer?.invalidate()
        web.stopLoading()
        web.configuration.userContentController.removeScriptMessageHandler(forName: WebPlayerViewController.bridge)
        web.loadHTMLString("", baseURL: nil)
        let done = onClose
        onClose = nil
        dismiss(animated: true) { done?() }
    }

    @objc private func nextTapped() {
        guard let n = nextEpisode() else { return }
        episode = n
        Library.shared.record(show, episode: n)
        start()
    }

    @objc private func serverTapped() {
        index = sources.count > 1 ? (index + 1) % sources.count : index
        load()
        showBar()
    }

    @objc private func showBar() {
        hideTimer?.invalidate()
        if isPortrait { bar.alpha = 1; barShade.alpha = 1; return }
        UIView.animate(withDuration: 0.2) { self.bar.alpha = 1; self.barShade.alpha = 1 }
        hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            guard let self = self, !self.isPortrait else { return }
            UIView.animate(withDuration: 0.3) { self.bar.alpha = 0; self.barShade.alpha = 0 }
        }
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { return true }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool { return !(touch.view is UIControl) }

    // MARK: resume after Siri / a call / Control Center

    @objc private func audioInterrupted(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw), type == .ended else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.resumeVideo() }
    }

    @objc private func becameActive() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.resumeVideo() }
    }

    /// Plays again every video that was paused by the system (never one the viewer paused), in every frame that has one.
    private func resumeVideo() {
        guard presentedViewController == nil, web != nil else { return }
        let js = "window.__cgResume && window.__cgResume();"
        DispatchQueue.global(qos: .userInitiated).async {
            try? AVAudioSession.sharedInstance().setActive(true)
        }
        web.evaluateJavaScript(js, completionHandler: nil)
        if #available(iOS 14.0, *) {
            for f in videoFrames where !f.isMainFrame {
                web.evaluateJavaScript(js, in: f, in: .page, completionHandler: nil)
            }
        }
    }

    // MARK: WKScriptMessageHandler — what the page scripts report

    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let t = body["t"] as? String else { return }
        switch t {
        case "video":
            sawVideo = true
            // remember the frame that holds the player (at most a few; a frame that navigated is simply ignored later)
            if !videoFrames.contains(where: { $0.securityOrigin.host == message.frameInfo.securityOrigin.host && $0.isMainFrame == message.frameInfo.isMainFrame }) {
                videoFrames.append(message.frameInfo)
                if videoFrames.count > 6 { videoFrames.removeFirst() }
            }
        case "playing":
            playing = true
            spinner.stopAnimating()
            hintTimer?.invalidate()
            hideHint()
        case "removed":
            NSLog("CIGRAM_CHECK player removed an overlay: %@", (body["what"] as? String) ?? "")
        default: break
        }
    }

    // MARK: WKNavigationDelegate / WKUIDelegate

    func webView(_ w: WKWebView, didCommit n: WKNavigation!) { committed = true }

    func webView(_ w: WKWebView, didFinish n: WKNavigation!) {
        if !playing { DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in self?.spinner.stopAnimating() } }
    }

    func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        let scheme = (url.scheme ?? "").lowercased()
        // app links of ads (itms-apps, intent, tel, sms, custom schemes) never leave the player
        if !["http", "https", "about", "blob", "data", "javascript"].contains(scheme) { decisionHandler(.cancel); return }
        // a new window (target=_blank / window.open): refused, pop-ups and pop-unders never open
        guard let target = action.targetFrame else { decisionHandler(.cancel); return }
        // the player's iframes may come from other hosts
        guard target.isMainFrame else { decisionHandler(.allow); return }
        let h = (url.host ?? "").lowercased()
        if h.isEmpty || scheme == "about" { decisionHandler(.allow); return }
        if WebPlayerViewController.sameSite(h, allowedHost) { decisionHandler(.allow); return }
        // the embed link's own server redirect (vidbolt.xyz → vidbolt.pro, a mirror) happens before the first page
        // commits and without a tap: follow it, and from then on that host is the player's host
        if !committed && action.navigationType == .other {
            allowedHost = h
            decisionHandler(.allow)
            return
        }
        // anything else that tries to take over the player (an ad redirect after a tap) is dropped
        NSLog("CIGRAM_CHECK player blocked a redirect to %@", h)
        decisionHandler(.cancel)
    }

    static func sameSite(_ a: String, _ b: String) -> Bool {
        if a == b || a.hasSuffix("." + b) || b.hasSuffix("." + a) { return true }
        // same registrable name (player.example.com ~ cdn.example.com)
        let pa = a.split(separator: "."), pb = b.split(separator: ".")
        return pa.count >= 2 && pb.count >= 2 && pa.suffix(2) == pb.suffix(2)
    }

    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration,
                 for a: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { return nil }

    // a page that asks for alert / confirm / prompt (ad tricks) is answered silently
    func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) { completionHandler() }
    func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) { completionHandler(false) }
    func webView(_ w: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) { completionHandler(nil) }

    func webView(_ w: WKWebView, didFail n: WKNavigation!, withError e: Error) { failover(e) }
    func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) { failover(e) }

    /// The web content process died (memory pressure on a long film): load the same source again instead of a black screen.
    func webViewWebContentProcessDidTerminate(_ w: WKWebView) {
        NSLog("CIGRAM_CHECK player web process ended, reloading the source")
        load()
    }

    func webView(_ w: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if response.isForMainFrame, let http = response.response as? HTTPURLResponse, http.statusCode >= 400 {
            decisionHandler(.cancel)
            DispatchQueue.main.async { [weak self] in self?.advance() }
            return
        }
        decisionHandler(.allow)
    }

    private func failover(_ e: Error) {
        let ns = e as NSError
        if ns.code == NSURLErrorCancelled { return }
        if ns.domain == "WebKitErrorDomain" && ns.code == 102 { return }      // frame load interrupted (our own cancel)
        if committed { return }                                                 // a failing sub-resource is not a dead source
        advance()
    }

    private func advance() {
        index += 1
        load()
    }
}

/// WKUserContentController keeps its handlers strongly; this proxy keeps the player weakly so it can be released.
final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(ucc, didReceive: message)
    }
}

/// The scripts injected into every frame of the embed page.
enum PlayerScripts {
    /// Document start: no new windows, the viewer's touches are remembered (a pause right after a touch is the viewer's),
    /// `__cgResume()` plays again what the system paused, the native side learns which frame has the video.
    static let guardScript = """
    (function(){
      if (window.__cgGuard) return; window.__cgGuard = 1;
      var post = function(m){ try { window.webkit.messageHandlers.cg.postMessage(m); } catch (e) {} };
      try { window.open = function(){ return null; }; } catch (e) {}
      var lastTouch = 0;
      ['touchstart','touchend','pointerdown','mousedown','keydown','click'].forEach(function(t){
        window.addEventListener(t, function(){ lastTouch = Date.now(); }, true);
      });
      document.addEventListener('pause', function(e){
        var v = e.target; if (!v || v.tagName !== 'VIDEO') return;
        v.__cgUserPause = (Date.now() - lastTouch) < 1500 && !document.hidden;
      }, true);
      document.addEventListener('play', function(e){
        var v = e.target; if (!v || v.tagName !== 'VIDEO') return;
        v.__cgUserPause = false; v.__cgStarted = true;
      }, true);
      document.addEventListener('playing', function(e){
        var v = e.target; if (!v || v.tagName !== 'VIDEO') return;
        v.__cgStarted = true; post({t:'playing'});
      }, true);
      document.addEventListener('loadedmetadata', function(e){
        var v = e.target; if (v && v.tagName === 'VIDEO') post({t:'video'});
      }, true);
      window.__cgResume = function(){
        var vs = document.querySelectorAll('video');
        for (var i = 0; i < vs.length; i++) {
          var v = vs[i];
          if (v.paused && !v.ended && v.__cgStarted && !v.__cgUserPause) {
            try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {}
          }
        }
      };
      document.addEventListener('visibilitychange', function(){ if (!document.hidden) setTimeout(window.__cgResume, 400); });
    })();
    """

    /// Document end: start the video once (no clicking around), and keep floating ad layers off the player.
    static let startScript = """
    (function(){
      if (window.__cgStart) return; window.__cgStart = 1;
      var post = function(m){ try { window.webkit.messageHandlers.cg.postMessage(m); } catch (e) {} };
      var BIG = '.jw-display-icon-display,.jw-icon-display,.vjs-big-play-button,.plyr__control--overlaid,.art-control-play,.dplayer-mobile-play,.fp-play,.mejs__overlay-button';
      var pressed = false;
      var tryStart = function(){
        var v = document.querySelector('video');
        if (!v) return false;
        post({t:'video'});
        if (v.__cgStarted || v.__cgTried) return true;
        v.__cgTried = true;
        try {
          var p = v.play();
          if (p && p.catch) p.catch(function(){
            if (pressed) return; pressed = true;
            var b = document.querySelector(BIG); if (b) { try { b.click(); } catch (e) {} }
          });
        } catch (e) {}
        return true;
      };
      var n = 0;
      var iv = setInterval(function(){ n++; if (tryStart() || n > 15) clearInterval(iv); }, 700);
      tryStart();

      var PLAYERISH = /(jw|vjs|video|plyr|player|art-|dplayer|setting|subtit|caption|menu|control|quality|audio|track|episode|server|source|loader|loading|spinner|captcha|cf-|turnstile)/i;
      var vw = function(){ return Math.max(1, window.innerWidth * window.innerHeight); };
      var named = function(el){ return ((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '')); };
      var holdsPlayer = function(el){
        if (el.querySelector && el.querySelector('video')) return true;
        var fr = el.tagName === 'IFRAME' ? [el] : (el.querySelectorAll ? el.querySelectorAll('iframe') : []);
        for (var i = 0; i < fr.length; i++) {
          var r = fr[i].getBoundingClientRect();
          if (r.width * r.height > vw() * 0.3) return true;
        }
        return false;
      };
      var suspicious = function(el){
        if (!el || el === document.body || el === document.documentElement) return false;
        if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'LINK' || el.tagName === 'VIDEO') return false;
        var cs; try { cs = window.getComputedStyle(el); } catch (e) { return false; }
        if (!cs || (cs.position !== 'fixed' && !(cs.position === 'absolute' && el.parentNode === document.body))) return false;
        var z = parseInt(cs.zIndex, 10); if (!(z >= 1000)) return false;
        if (holdsPlayer(el)) return false;
        if (PLAYERISH.test(named(el))) return false;
        var first = el.firstElementChild; if (first && PLAYERISH.test(named(first))) return false;
        return true;
      };
      var sweep = function(){
        if (!document.body) return;
        var kids = document.body.children;
        for (var i = kids.length - 1; i >= 0; i--) {
          var el = kids[i];
          if (suspicious(el)) { post({t:'removed', what: el.tagName + ' ' + named(el).slice(0, 60)}); el.remove(); }
        }
        var frames = document.querySelectorAll('iframe');
        for (var j = 0; j < frames.length; j++) {
          var f = frames[j];
          if (f.parentNode && f.parentNode !== document.body && suspicious(f)) { post({t:'removed', what: 'IFRAME ' + (f.src || '').slice(0, 60)}); f.remove(); }
        }
      };
      var queued = false;
      var later = function(){ if (queued) return; queued = true; setTimeout(function(){ queued = false; sweep(); }, 350); };
      sweep();
      try { new MutationObserver(later).observe(document.documentElement, {childList: true, subtree: false}); } catch (e) {}
      try { if (document.body) new MutationObserver(later).observe(document.body, {childList: true, subtree: false}); } catch (e) {}
      var k = 0; var sv = setInterval(function(){ k++; sweep(); if (k > 20) clearInterval(sv); }, 1500);
    })();
    """
}

/// Content rules for the player's web view: the ad networks these embed pages pull pop-ups, pop-unders and overlays from
/// are never loaded (third-party requests only). Compiled once, kept by WebKit between launches.
enum WebShield {
    private static let identifier = "cigram-player-shield-v2"
    private static var compiled: WKContentRuleList?
    private static var waiting: [(WKContentRuleList?) -> Void] = []
    private static var working = false

    static let blockedHosts = [
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net", "onclkds.com",
        "adsterra.com", "highperformanceformat.com", "profitablecpmrate.com", "profitablegatecpm.com",
        "effectiveratecpm.com", "effectivegatecpm.com", "exoclick.com", "exosrv.com", "exdynsrv.com", "magsrv.com",
        "juicyads.com", "trafficjunky.net", "hilltopads.net", "hilltopads.com", "clickadu.com", "adcash.com",
        "acscdn.com", "a-ads.com", "monetag.com", "galaksion.com", "tsyndicate.com", "realsrv.com", "admaven.com",
        "ad-maven.com", "bidgear.com", "yllix.com", "displayvertising.com", "pushame.com", "richpush.co",
        "clickaine.com", "adsco.re", "popmyads.com", "zeropark.com", "mgid.com", "revcontent.com",
        "outbrain.com", "taboola.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "criteo.com",
    ]

    static func ruleJSON() -> String {
        var rules: [[String: Any]] = []
        for h in blockedHosts {
            // the regex WebKit receives is  ^https?://[^/]*doubleclick\.net[:/]  (JSONSerialization escapes the backslash)
            let escaped = h.replacingOccurrences(of: ".", with: "\\.")
            let trigger: [String: Any] = ["url-filter": "^https?://[^/]*" + escaped + "[:/]", "load-type": ["third-party"]]
            let action: [String: Any] = ["type": "block"]
            rules.append(["trigger": trigger, "action": action])
        }
        let data = (try? JSONSerialization.data(withJSONObject: rules)) ?? Data("[]".utf8)
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    /// The compiled list (nil when WebKit could not compile it: the player then simply runs without it).
    static func rules(_ done: @escaping (WKContentRuleList?) -> Void) {
        if let c = compiled { done(c); return }
        waiting.append(done)
        if working { return }
        working = true
        guard let store = WKContentRuleListStore.default() else { finish(nil); return }
        store.lookUpContentRuleList(forIdentifier: identifier) { list, _ in
            if let l = list { finish(l); return }
            store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: ruleJSON()) { list, error in
                if let e = error { NSLog("CIGRAM_CHECK player shield not compiled: %@", e.localizedDescription) }
                finish(list)
            }
        }
    }

    private static func finish(_ list: WKContentRuleList?) {
        DispatchQueue.main.async {
            compiled = list
            working = false
            let w = waiting
            waiting.removeAll()
            w.forEach { $0(list) }
        }
    }

    /// Compiles the list early (app start), so the first film does not wait for it.
    static func prepare() { rules { _ in } }
}
