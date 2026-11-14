import UIKit

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        let nav = UINavigationController(rootViewController: CatalogueViewController())
        nav.navigationBar.barStyle = .black
        nav.navigationBar.tintColor = .white
        w.rootViewController = nav
        w.makeKeyAndVisible()
        window = w
        return true
    }
}
