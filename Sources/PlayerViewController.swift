import UIKit
import WebKit
import AVFoundation

/// Plays a film / an episode through its own VidBolt link (exactly what the admin published), with the other embed servers
/// as automatic fallbacks. Pop-ups and redirects to other sites are blocked; the video starts by itself.
final class PlayerViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let show: Show
    private var episode: CatalogItem?
    private var web: WKWebView!
    private var sources: [URL] = []
    private var index = 0
    private var allowedHost = ""
    private let bar = UIView()
    private let close = UIButton(type: .system)
    private let titleLabel = UILabel()
    private let next = UIButton(type: .system)
    private let server = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .whiteLarge)
    private var hideTimer: Timer?

    init(show: Show, episode: CatalogItem?) {
        self.show = show
        self.episode = episode
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var prefersStatusBarHidden: Bool { return true }
    override var prefersHomeIndicatorAutoHidden: Bool { return true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { return .allButUpsideDown }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { return .landscapeRight }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        try? AVAudioSession.sharedInstance().setActive(true)

        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.allowsPictureInPictureMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        web = WKWebView(frame: view.bounds, configuration: cfg)
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        web.navigationDelegate = self
        web.uiDelegate = self
        web.backgroundColor = .black
        web.isOpaque = false
        web.scrollView.backgroundColor = .black
        web.scrollView.contentInsetAdjustmentBehavior = .never
        view.addSubview(web)

        spinner.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        spinner.autoresizingMask = [.flexibleTopMargin, .flexibleBottomMargin, .flexibleLeftMargin, .flexibleRightMargin]
        view.addSubview(spinner)

        bar.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        bar.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: 54)
        bar.autoresizingMask = [.flexibleWidth]
        view.addSubview(bar)
        close.setTitle("✕", for: .normal)
        close.setTitleColor(.white, for: .normal)
        close.titleLabel?.font = Theme.font(22, .bold)
        close.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        titleLabel.textColor = .white
        titleLabel.font = Theme.font(15, .bold)
        titleLabel.textAlignment = .center
        next.setTitle("الحلقة التالية ›", for: .normal)
        next.setTitleColor(.white, for: .normal)
        next.titleLabel?.font = Theme.font(14, .bold)
        next.addTarget(self, action: #selector(nextTapped), for: .touchUpInside)
        server.setTitle("مصدر آخر", for: .normal)
        server.setTitleColor(Theme.text2, for: .normal)
        server.titleLabel?.font = Theme.font(13, .semibold)
        server.addTarget(self, action: #selector(serverTapped), for: .touchUpInside)
        [close, titleLabel, next, server].forEach { bar.addSubview($0) }
        let tap = UITapGestureRecognizer(target: self, action: #selector(showBar))
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)
        start()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = view.bounds.width
        bar.frame = CGRect(x: 0, y: 0, width: w, height: 54)
        var left: CGFloat = 8
        var right: CGFloat = 8
        if #available(iOS 11.0, *) { left = max(8, view.safeAreaInsets.left); right = max(8, view.safeAreaInsets.right) }
        close.frame = CGRect(x: left, y: 7, width: 44, height: 40)
        next.frame = CGRect(x: w - right - 130, y: 7, width: 130, height: 40)
        server.frame = CGRect(x: left + 48, y: 7, width: 80, height: 40)
        titleLabel.frame = CGRect(x: left + 132, y: 7, width: max(40, w - left - right - 132 - 136), height: 40)
    }

    private func start() {
        var text = show.title
        if let e = episode { text += " · م" + String(e.season) + " ح" + String(e.episode) }
        titleLabel.text = text
        next.isHidden = nextEpisode() == nil
        sources = buildSources()
        index = 0
        load()
        showBar()
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

    private func load() {
        guard index < sources.count else {
            spinner.stopAnimating()
            let a = UIAlertController(title: "الفيديو غير متاح الآن", message: "جرّب بعد قليل.", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "رجوع", style: .cancel) { [weak self] _ in self?.dismiss(animated: true) })
            present(a, animated: true)
            return
        }
        let u = sources[index]
        allowedHost = u.host ?? ""
        spinner.startAnimating()
        web.load(URLRequest(url: u))
    }

    private func nextEpisode() -> CatalogItem? {
        guard let e = episode, let i = show.episodes.firstIndex(where: { $0.season == e.season && $0.episode == e.episode }),
              i + 1 < show.episodes.count else { return nil }
        return show.episodes[i + 1]
    }

    @objc private func closeTapped() {
        web.stopLoading()
        web.loadHTMLString("", baseURL: nil)
        dismiss(animated: true)
    }

    @objc private func nextTapped() {
        guard let n = nextEpisode() else { return }
        episode = n
        start()
    }

    @objc private func serverTapped() {
        index = (index + 1) % max(1, sources.count)
        load()
        showBar()
    }

    @objc private func showBar() {
        bar.alpha = 1
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.3) { self?.bar.alpha = 0 }
        }
    }

    private let autoplay = "(function(){function v(){var x=document.querySelector('video');if(x){x.play&&x.play();return true;}" +
        "var b=document.querySelector('.jw-icon-display,.vjs-big-play-button,[class*=play],[id*=play]');if(b){b.click();}return false;}" +
        "v();setTimeout(v,1200);setTimeout(v,3000);})();"

    // MARK: WKNavigationDelegate / WKUIDelegate

    func webView(_ w: WKWebView, didFinish n: WKNavigation!) {
        spinner.stopAnimating()
        w.evaluateJavaScript(autoplay, completionHandler: nil)
    }

    func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard action.targetFrame?.isMainFrame ?? false, let host = action.request.url?.host else {
            decisionHandler(.allow)
            return
        }
        // only the embed site itself may take over the main frame (ad pop-ups / redirects are dropped)
        let base = allowedHost.lowercased()
        let h = host.lowercased()
        if h == base || h.hasSuffix("." + base) || base.hasSuffix("." + h) { decisionHandler(.allow) } else { decisionHandler(.cancel) }
    }

    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration,
                 for a: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { return nil }

    func webView(_ w: WKWebView, didFail n: WKNavigation!, withError e: Error) { failover(e) }
    func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) { failover(e) }

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
        if (e as NSError).code == NSURLErrorCancelled { return }
        advance()
    }

    private func advance() {
        index += 1
        load()
    }
}
