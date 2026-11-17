import UIKit
import AVFoundation

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var tabs: UITabBarController?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Arabic first: every screen right-to-left, like the Android app
        UIView.appearance().semanticContentAttribute = .forceRightToLeft
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        styleBars()

        let home = nav(HomeViewController(), title: "الرئيسية", icon: "⌂")
        let search = nav(SearchViewController(), title: "البحث", icon: "⌕")
        let t = UITabBarController()
        t.viewControllers = [home, search]
        t.tabBar.barStyle = .black
        t.tabBar.tintColor = .white
        t.tabBar.unselectedItemTintColor = Theme.text3
        tabs = t

        let w = UIWindow(frame: UIScreen.main.bounds)
        w.backgroundColor = Theme.bg
        w.rootViewController = t
        w.makeKeyAndVisible()
        window = w

        CatalogStore.shared.start()
        Notifier.shared.openShow = { [weak self] key in self?.open(key) }
        Notifier.shared.setUp(application)
        if ProcessInfo.processInfo.arguments.contains("-cigramOpenFirst") { openFirstWhenLoaded() }
        return true
    }

    /// CI check (simulator): opens the details page of the first title once the catalogue is in.
    private var firstObserver: NSObjectProtocol?
    private func openFirstWhenLoaded() {
        firstObserver = NotificationCenter.default.addObserver(forName: .cigramCatalogChanged, object: nil, queue: .main) { [weak self] _ in
            guard let self = self, let first = CatalogStore.shared.sections.first?.shows.first ?? CatalogStore.shared.shows.first,
                  let nav = self.tabs?.viewControllers?.first as? UINavigationController else { return }
            if let o = self.firstObserver { NotificationCenter.default.removeObserver(o) }
            self.firstObserver = nil
            NSLog("CIGRAM_CHECK details title=%@ meta=%@ episodes=%d link=%@", first.title, first.meta, first.episodes.count, first.watchLink)
            nav.pushViewController(DetailsViewController(show: first), animated: false)
        }
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        CatalogStore.shared.startPulse()          // a title the admin adds appears within ~20 s
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        CatalogStore.shared.stopPulse()
        Notifier.shared.schedule()
    }

    /// iOS 12 background fetch (iOS 13+ uses BGTaskScheduler, see Notifier).
    func application(_ application: UIApplication,
                     performFetchWithCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        Notifier.shared.backgroundRefresh { ok in completionHandler(ok ? .newData : .failed) }
    }

    private func open(_ key: String) {
        guard let t = tabs, let nav = t.viewControllers?.first as? UINavigationController else { return }
        t.selectedIndex = 0
        if let s = CatalogStore.shared.show(forKey: key) {
            nav.popToRootViewController(animated: false)
            nav.pushViewController(DetailsViewController(show: s), animated: true)
        }
    }

    private func nav(_ root: UIViewController, title: String, icon: String) -> UINavigationController {
        let n = UINavigationController(rootViewController: root)
        n.navigationBar.barStyle = .black
        n.navigationBar.tintColor = .white
        n.navigationBar.isTranslucent = true
        if #available(iOS 11.0, *) { n.navigationBar.prefersLargeTitles = false }
        n.tabBarItem = UITabBarItem(title: title, image: AppDelegate.glyph(icon), tag: 0)
        return n
    }

    private func styleBars() {
        let attrs: [NSAttributedString.Key: Any] = [.foregroundColor: UIColor.white, .font: Theme.font(17, .bold)]
        UINavigationBar.appearance().titleTextAttributes = attrs
        UINavigationBar.appearance().barTintColor = Theme.bg
        UITabBar.appearance().barTintColor = Theme.bg
    }

    /// A tab icon drawn from a text glyph (no image assets needed; works on iOS 12).
    static func glyph(_ text: String) -> UIImage? {
        let size = CGSize(width: 26, height: 26)
        UIGraphicsBeginImageContextWithOptions(size, false, 0)
        defer { UIGraphicsEndImageContext() }
        let p = NSMutableParagraphStyle()
        p.alignment = .center
        (text as NSString).draw(in: CGRect(x: 0, y: 0, width: 26, height: 26),
                                withAttributes: [.font: UIFont.systemFont(ofSize: 21, weight: .bold), .paragraphStyle: p,
                                                 .foregroundColor: UIColor.white])
        return UIGraphicsGetImageFromCurrentImageContext()?.withRenderingMode(.alwaysTemplate)
    }
}
