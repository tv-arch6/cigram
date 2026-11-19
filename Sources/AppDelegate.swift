import UIKit
import AVFoundation

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate, UITabBarControllerDelegate {
    var window: UIWindow?
    private var tabs: UITabBarController?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Arabic first: every screen right-to-left, like the Android app
        UIView.appearance().semanticContentAttribute = .forceRightToLeft
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        styleBars()

        let w = UIWindow(frame: UIScreen.main.bounds)
        w.backgroundColor = Theme.bg
        w.overrideUserInterfaceStyle = .dark
        window = w

        // «حماية التطبيق»: a debugger or an injected cracking tool = the app does not open
        if case .tampered(let why) = Integrity.check() {
            NSLog("CIGRAM_CHECK integrity blocked: %@", why)
            w.rootViewController = BlockedViewController()
            w.makeKeyAndVisible()
            return true
        }

        // the same four tabs as the Android bottom bar (the first one sits at the far right)
        let t = UITabBarController()
        t.viewControllers = [
            nav(HomeViewController(), title: "الرئيسية", icon: "house", selected: "house.fill"),
            nav(ChannelsViewController(), title: "القنوات", icon: "play.tv", selected: "play.tv.fill"),
            nav(SearchViewController(), title: "البحث", icon: "magnifyingglass", selected: "magnifyingglass"),
            nav(AccountViewController(), title: "حسابي", icon: "person.crop.circle", selected: "person.crop.circle.fill"),
        ]
        t.delegate = self
        tabs = t
        w.rootViewController = t
        w.makeKeyAndVisible()

        DownloadUI.install()
        DownloadManager.shared.launch()           // re-attaches the background downloads the system kept
        CatalogStore.shared.start()
        ChannelStore.shared.start()
        SupportCenter.shared.start()
        WebShield.prepare()                        // the player's ad shield is compiled before the first film
        NotificationCenter.default.addObserver(self, selector: #selector(supportBadge), name: SupportCenter.changed, object: nil)
        Notifier.shared.openShow = { [weak self] key in self?.open(key) }
        Notifier.shared.openSupport = { [weak self] in self?.openSupport() }
        Notifier.shared.setUp(application, ask: !ProcessInfo.processInfo.arguments.contains("-cigramNoPrompt"))
        if case .jailbroken = Integrity.check() {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.warnJailbreak() }
        }
        ScreenRouter.run(tabs: t)
        return true
    }

    /// Portrait everywhere, landscape too while a player is open or the channels tab is selected (see AppOrientation).
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        return AppOrientation.mask
    }

    func tabBarController(_ tabBarController: UITabBarController, didSelect viewController: UIViewController) {
        AppOrientation.channelsTabChanged(tabBarController.selectedIndex == 1)
    }

    /// The system finished downloads while the app was not running: the sessions are opened again and it is told when to stop.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        DownloadManager.shared.backgroundCompletion = completionHandler
        DownloadManager.shared.launch()
    }

    func applicationWillTerminate(_ application: UIApplication) {
        DownloadManager.shared.flush()
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        DownloadManager.shared.becameActive()
        CatalogStore.shared.startPulse()          // a title the admin adds appears within ~20 s
        RequestsStore.shared.refresh()
        if case .tampered = Integrity.check(), !(window?.rootViewController is BlockedViewController) {
            window?.rootViewController = BlockedViewController()
        }
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        DownloadManager.shared.flush()
        CatalogStore.shared.stopPulse()
        Notifier.shared.schedule()
    }

    private func warnJailbreak() {
        guard let root = window?.rootViewController, !UserDefaults.standard.bool(forKey: "cg.jb.warned") else { return }
        UserDefaults.standard.set(true, forKey: "cg.jb.warned")
        let a = UIAlertController(title: "تنبيه أمني", message: "جهازك معدّل (Jailbreak). قد لا يكون حسابك آمناً بالكامل على هذا الجهاز.", preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "حسناً", style: .default))
        root.present(a, animated: true)
    }

    /// The red count on «حسابي» when the Cigram team answered in «الدعم والتواصل».
    @objc private func supportBadge() {
        let n = SupportCenter.shared.unread
        let item = tabs?.viewControllers?.last?.tabBarItem
        item?.badgeValue = n > 0 ? (n > 99 ? "99+" : String(n)) : nil
        item?.badgeColor = Theme.red
        UIApplication.shared.applicationIconBadgeNumber = n
    }

    /// A «رد جديد من الدعم» notification opens the conversation (or the sign-in when the account signed out meanwhile).
    private func openSupport() {
        guard let t = tabs, let nav = t.viewControllers?.last as? UINavigationController else { return }
        t.selectedIndex = (t.viewControllers?.count ?? 1) - 1
        nav.popToRootViewController(animated: false)
        (nav.viewControllers.first as? AccountViewController)?.openSupport()
    }

    private func open(_ key: String) {
        guard let t = tabs, let nav = t.viewControllers?.first as? UINavigationController else { return }
        t.selectedIndex = 0
        if let s = CatalogStore.shared.show(forKey: key) {
            nav.popToRootViewController(animated: false)
            nav.pushViewController(DetailsViewController(show: s), animated: true)
        }
    }

    private func nav(_ root: UIViewController, title: String, icon: String, selected: String) -> UINavigationController {
        let n = UINavigationController(rootViewController: root)
        n.navigationBar.tintColor = .white
        n.tabBarItem = UITabBarItem(title: title, image: UIImage(systemName: icon), selectedImage: UIImage(systemName: selected))
        return n
    }

    private func styleBars() {
        let navAppearance = UINavigationBarAppearance()
        navAppearance.configureWithOpaqueBackground()
        navAppearance.backgroundColor = Theme.bg
        navAppearance.shadowColor = .clear
        navAppearance.titleTextAttributes = [.foregroundColor: UIColor.white, .font: Theme.font(17, .bold)]
        UINavigationBar.appearance().standardAppearance = navAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navAppearance
        UINavigationBar.appearance().compactAppearance = navAppearance

        // the Android bar: dark, the open tab white and bold, the others grey
        let tab = UITabBarAppearance()
        tab.configureWithOpaqueBackground()
        tab.backgroundColor = UIColor(red: 0.04, green: 0.04, blue: 0.05, alpha: 1)
        tab.shadowColor = UIColor.white.withAlphaComponent(0.08)
        for layout in [tab.stackedLayoutAppearance, tab.inlineLayoutAppearance, tab.compactInlineLayoutAppearance] {
            layout.normal.iconColor = Theme.text3
            layout.normal.titleTextAttributes = [.foregroundColor: Theme.text3, .font: Theme.font(11, .medium)]
            layout.selected.iconColor = .white
            layout.selected.titleTextAttributes = [.foregroundColor: UIColor.white, .font: Theme.font(11, .bold)]
        }
        UITabBar.appearance().standardAppearance = tab
        if #available(iOS 15.0, *) { UITabBar.appearance().scrollEdgeAppearance = tab }
    }
}

/// Opens one screen at launch (`-cigramScreen <name>`), used by the simulator check that photographs every screen.
enum ScreenRouter {
    static func run(tabs: UITabBarController) {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-cigramScreen"), i + 1 < args.count else { return }
        let screen = args[i + 1]
        var done = false
        var observer: NSObjectProtocol?
        let go = {
            guard !done, CatalogStore.shared.loaded else { return }
            done = true
            if let o = observer { NotificationCenter.default.removeObserver(o) }
            open(screen, tabs: tabs)
        }
        observer = NotificationCenter.default.addObserver(forName: .cigramCatalogChanged, object: nil, queue: .main) { _ in go() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { go() }
    }

    private static func open(_ screen: String, tabs: UITabBarController) {
        let store = CatalogStore.shared
        let navs = tabs.viewControllers?.compactMap { $0 as? UINavigationController } ?? []
        guard navs.count == 4 else { return }
        let series = store.shows.first { $0.isSeries && $0.episodes.count > 3 }
        let movie = store.shows.first { !$0.isSeries }
        switch screen {
        case "channels": tabs.selectedIndex = 1
        case "channelplayer":
            tabs.selectedIndex = 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                guard let s = ChannelStore.shared.sections.first else { return }
                let p = ChannelPlayerViewController(channels: s.channels, index: 0)
                p.modalPresentationStyle = .fullScreen
                navs[1].present(p, animated: false)
            }
        case "search":
            tabs.selectedIndex = 2
            if let s = navs[2].viewControllers.first as? SearchViewController {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { s.searchController.searchBar.text = "ال" }
            }
        case "account": tabs.selectedIndex = 3
        case "login":
            tabs.selectedIndex = 3
            navs[3].pushViewController(LoginViewController(), animated: false)
        case "terms":
            tabs.selectedIndex = 3
            navs[3].pushViewController(TermsViewController(), animated: false)
        case "register":
            tabs.selectedIndex = 3
            navs[3].pushViewController(LoginViewController(register: true), animated: false)
        case "support":
            tabs.selectedIndex = 3
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { (navs[3].viewControllers.first as? AccountViewController)?.openSupport() }
        case "requests":
            tabs.selectedIndex = 3
            navs[3].pushViewController(RequestsViewController(), animated: false)
        case "details":
            if let s = series { navs[0].pushViewController(DetailsViewController(show: s), animated: false) }
        case "movie":
            if let m = movie { navs[0].pushViewController(DetailsViewController(show: m), animated: false) }
        case "rows":
            for s in store.shows.dropFirst(3).prefix(6) where !Library.shared.inMyList(s.key) { Library.shared.toggleMyList(s.key) }
            if let s = series { Library.shared.record(s, episode: s.episodes.dropFirst(2).first) }
            if let m = movie { Library.shared.record(m, episode: nil) }
            tabs.selectedIndex = 0
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                (navs[0].viewControllers.first as? HomeViewController)?.scrollToRows()
            }
        case "player":
            if let s = series {
                let p = PlayerViewController(show: s, episode: s.episodes.first)
                p.modalPresentationStyle = .fullScreen
                navs[0].present(p, animated: false)
            }
        default: break
        }
    }
}
