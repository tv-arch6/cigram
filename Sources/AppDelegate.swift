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

        let home = nav(HomeViewController(), title: "الرئيسية", icon: "house.fill")
        let search = nav(SearchViewController(), title: "البحث", icon: "magnifyingglass")
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
            NSLog("CIGRAM_CHECK details title=%@ meta=%@ episodes=%ld link=%@", first.title, first.meta, first.episodes.count, first.watchLink)
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
        n.tabBarItem = UITabBarItem(title: title, image: AppDelegate.icon(icon), tag: 0)
        return n
    }

    private func styleBars() {
        let attrs: [NSAttributedString.Key: Any] = [.foregroundColor: UIColor.white, .font: Theme.font(17, .bold)]
        UINavigationBar.appearance().titleTextAttributes = attrs
        UINavigationBar.appearance().barTintColor = Theme.bg
        UITabBar.appearance().barTintColor = Theme.bg
    }

    /// SF Symbols on iOS 13+, a drawn house / magnifier on iOS 12 (no image assets needed).
    static func icon(_ symbol: String) -> UIImage? {
        if #available(iOS 13.0, *) {
            if let img = UIImage(systemName: symbol) { return img }
        }
        let size = CGSize(width: 26, height: 26)
        UIGraphicsBeginImageContextWithOptions(size, false, 0)
        defer { UIGraphicsEndImageContext() }
        UIColor.white.setStroke()
        UIColor.white.setFill()
        if symbol.hasPrefix("house") {
            let roof = UIBezierPath()
            roof.move(to: CGPoint(x: 3, y: 13))
            roof.addLine(to: CGPoint(x: 13, y: 4))
            roof.addLine(to: CGPoint(x: 23, y: 13))
            roof.lineWidth = 2.4
            roof.lineCapStyle = .round
            roof.lineJoinStyle = .round
            roof.stroke()
            UIBezierPath(roundedRect: CGRect(x: 7, y: 12, width: 12, height: 11), cornerRadius: 2).fill()
        } else {
            let lens = UIBezierPath(ovalIn: CGRect(x: 4, y: 4, width: 13, height: 13))
            lens.lineWidth = 2.4
            lens.stroke()
            let handle = UIBezierPath()
            handle.move(to: CGPoint(x: 15.5, y: 15.5))
            handle.addLine(to: CGPoint(x: 22, y: 22))
            handle.lineWidth = 2.8
            handle.lineCapStyle = .round
            handle.stroke()
        }
        return UIGraphicsGetImageFromCurrentImageContext()?.withRenderingMode(.alwaysTemplate)
    }
}
