import UIKit
import WebKit
import AVFoundation

/// Plays through the same embed servers as the Android resolver, with server switching and popup blocking.
final class PlayerViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let item: Title
    private var web: WKWebView!
    private let servers = UISegmentedControl(items: ["S1", "S2", "S3", "S4"])
    private var season = 1, episode = 1
    private var allowedHost = ""

    init(title: Title) { item = title; super.init(nibName: nil, bundle: nil); self.title = title.name }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        try? AVAudioSession.sharedInstance().setCategory(.playback)   // sound with the silent switch on

        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        if #available(iOS 10.0, *) { cfg.mediaTypesRequiringUserActionForPlayback = [] }
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = self; web.uiDelegate = self
        web.backgroundColor = .black; web.isOpaque = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.translatesAutoresizingMaskIntoConstraints = false

        servers.selectedSegmentIndex = 0
        servers.addTarget(self, action: #selector(reload), for: .valueChanged)
        servers.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(servers); view.addSubview(web)

        var g: UILayoutGuide? = nil
        if #available(iOS 11.0, *) { g = view.safeAreaLayoutGuide }
        let top = g?.topAnchor ?? view.topAnchor
        let bottom = g?.bottomAnchor ?? view.bottomAnchor
        NSLayoutConstraint.activate([
            servers.topAnchor.constraint(equalTo: top, constant: 6),
            servers.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            servers.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            web.topAnchor.constraint(equalTo: servers.bottomAnchor, constant: 6),
            web.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            web.bottomAnchor.constraint(equalTo: bottom)
        ])
        if item.isSeries {
            navigationItem.rightBarButtonItem = UIBarButtonItem(title: "S\(season)E\(episode)", style: .plain,
                                                                target: self, action: #selector(pickEpisode))
        }
        reload()
    }

    private func url(_ i: Int) -> URL? {
        if item.isSeries { return URL(string: String(format: Backend.tvServers[i], item.id, season, episode)) }
        return URL(string: String(format: Backend.movieServers[i], item.id))
    }

    @objc private func reload() {
        guard let u = url(servers.selectedSegmentIndex) else { return }
        allowedHost = u.host ?? ""
        web.load(URLRequest(url: u))
    }

    @objc private func pickEpisode() {
        let a = UIAlertController(title: "Season / Episode", message: nil, preferredStyle: .alert)
        a.addTextField { $0.placeholder = "Season"; $0.keyboardType = .numberPad; $0.text = "\(self.season)" }
        a.addTextField { $0.placeholder = "Episode"; $0.keyboardType = .numberPad; $0.text = "\(self.episode)" }
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        a.addAction(UIAlertAction(title: "Play", style: .default) { [weak self] _ in
            guard let s = self else { return }
            s.season = max(1, Int(a.textFields?[0].text ?? "") ?? 1)
            s.episode = max(1, Int(a.textFields?[1].text ?? "") ?? 1)
            s.navigationItem.rightBarButtonItem?.title = "S\(s.season)E\(s.episode)"
            s.reload()
        })
        present(a, animated: true)
    }

    // Popup / redirect blocking: only the embed host (and its subresources) may take over the main frame.
    func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard action.targetFrame?.isMainFrame ?? true, let host = action.request.url?.host else {
            decisionHandler(.allow); return
        }
        if action.navigationType == .linkActivated && host != allowedHost { decisionHandler(.cancel); return }
        decisionHandler(.allow)
    }
    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration,
                 for a: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }   // no popups

    func webView(_ w: WKWebView, didFail n: WKNavigation!, withError e: Error) { failover(e) }
    func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) { failover(e) }
    private func failover(_ e: Error) {
        if (e as NSError).code == NSURLErrorCancelled { return }
        let next = servers.selectedSegmentIndex + 1
        if next < servers.numberOfSegments { servers.selectedSegmentIndex = next; reload() }
    }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .allButUpsideDown }
}
