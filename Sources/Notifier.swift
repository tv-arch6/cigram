import UIKit
import UserNotifications
#if canImport(BackgroundTasks)
import BackgroundTasks
#endif

/// «فيلم جديد / مسلسل جديد» on the iPhone — only once the title is complete:
///  1. it is in the catalogue the phone just downloaded;
///  2. name, poster, a watch link, and a story or year + genres are there;
///  3. the poster really downloads (it is attached to the notification, shown big);
///  4. the watch link answers (the VidBolt page loads with a player).
/// A title that is not ready yet waits (persisted) and is checked again on the next refresh — never announced half-done.
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()
    static let taskId = "com.cigram.vid.refresh"

    private let defaults = UserDefaults.standard
    private let queue = DispatchQueue(label: "cigram.notifier", qos: .utility)
    private let knownKey = "cg.notify.known"
    private let pendingKey = "cg.notify.pending"
    var openShow: ((String) -> Void)?

    func setUp(_ app: UIApplication, ask: Bool = true) {
        UNUserNotificationCenter.current().delegate = self
        if ask { UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in } }
        #if canImport(BackgroundTasks)
        if #available(iOS 13.0, *) {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: Notifier.taskId, using: nil) { task in
                guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
                self.handle(refresh)
            }
        } else {
            app.setMinimumBackgroundFetchInterval(UIApplication.backgroundFetchIntervalMinimum)
        }
        #else
        app.setMinimumBackgroundFetchInterval(UIApplication.backgroundFetchIntervalMinimum)
        #endif
        NotificationCenter.default.addObserver(self, selector: #selector(catalogChanged), name: .cigramCatalogChanged, object: nil)
    }

    func schedule() {
        #if canImport(BackgroundTasks)
        if #available(iOS 13.0, *) {
            let req = BGAppRefreshTaskRequest(identifier: Notifier.taskId)
            req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
            try? BGTaskScheduler.shared.submit(req)
        }
        #endif
    }

    #if canImport(BackgroundTasks)
    @available(iOS 13.0, *)
    private func handle(_ task: BGAppRefreshTask) {
        schedule()
        task.expirationHandler = { }
        backgroundRefresh { ok in task.setTaskCompleted(success: ok) }
    }
    #endif

    /// iOS 12 background fetch and iOS 13+ refresh task: catalogue check, then the gated alerts.
    func backgroundRefresh(_ done: @escaping (Bool) -> Void) {
        CatalogStore.shared.refresh(force: false) { _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                self.check { done(true) }
            }
        }
    }

    @objc private func catalogChanged() { check(nil) }

    /// Finds titles that are new since the last check, and announces the ones that pass the gate.
    func check(_ done: (() -> Void)?) {
        let shows = CatalogStore.shared.shows
        guard !shows.isEmpty else { done?(); return }
        queue.async {
            var known = Set(self.defaults.stringArray(forKey: self.knownKey) ?? [])
            var pending = self.defaults.stringArray(forKey: self.pendingKey) ?? []
            let baseline = known.isEmpty
            var keys = shows.map { $0.key }
            for s in shows where s.isSeries {
                if let last = s.episodes.last { keys.append(s.key + "#" + String(last.season) + "x" + String(last.episode)) }
            }
            if baseline {
                self.defaults.set(keys, forKey: self.knownKey)       // first run: nothing is "new"
                DispatchQueue.main.async { done?() }
                return
            }
            let before = known
            for s in shows where !known.contains(s.key) {
                known.insert(s.key)
                if !pending.contains(s.key) { pending.append(s.key) }
            }
            // an existing series that got new episodes is announced too (keyed by its newest episode)
            for s in shows where s.isSeries {
                guard let last = s.episodes.last else { continue }
                let ek = s.key + "#" + String(last.season) + "x" + String(last.episode)
                if known.contains(ek) { continue }
                known.insert(ek)
                if before.contains(s.key) && !pending.contains(ek) { pending.append(ek) }
            }
            self.defaults.set(Array(known), forKey: self.knownKey)
            if pending.count > 12 { pending = Array(pending.suffix(12)) }    // an import of hundreds is not 100 alerts
            var still: [String] = []
            var announced = 0
            for p in pending {
                let showKey = p.components(separatedBy: "#").first ?? p
                guard let s = shows.first(where: { $0.key == showKey }) else { still.append(p); continue }
                let tries = self.defaults.integer(forKey: "cg.notify.tries." + p)
                if tries >= 7 { continue }
                if announced < 3, let image = self.ready(s) {
                    self.post(s, episodeAlert: p.contains("#"), image: image)
                    announced += 1
                    self.defaults.removeObject(forKey: "cg.notify.tries." + p)
                } else {
                    self.defaults.set(tries + 1, forKey: "cg.notify.tries." + p)
                    still.append(p)
                }
            }
            self.defaults.set(still, forKey: self.pendingKey)
            DispatchQueue.main.async { done?() }
        }
    }

    /// The gate. Returns the downloaded poster when everything is complete, nil when something is missing or broken.
    private func ready(_ s: Show) -> Data? {
        guard !s.title.isEmpty, let poster = s.posterURL else { return nil }
        let story = s.head.overview.count >= 12
        let info = !s.head.year.isEmpty && !s.head.category.isEmpty
        guard story || info else { return nil }
        let link = s.watchLink
        guard link.hasPrefix("http"), let watch = URL(string: link.replacingOccurrences(of: "vidbolt.xyz", with: "vidbolt.pro")) else { return nil }
        guard let img = ImageLoader.shared.data(poster), UIImage(data: img) != nil else { return nil }
        guard let page = ImageLoader.shared.data(watch, timeout: 15), page.count > 300 else { return nil }
        let html = String(data: page.prefix(200_000), encoding: .utf8)?.lowercased() ?? ""
        if html.contains("not found") && !html.contains("<video") && !html.contains("player") { return nil }
        return img
    }

    private func post(_ s: Show, episodeAlert: Bool, image: Data) {
        let c = UNMutableNotificationContent()
        if episodeAlert, let last = s.episodes.last {
            c.title = "حلقة جديدة وصلت"
            c.body = "الحلقة " + String(last.episode) + " من الموسم " + String(last.season) + " من " + s.title
        } else {
            c.title = s.isSeries ? "مسلسل جديد وصل" : "فيلم جديد وصل"
            c.body = s.title + (s.meta.isEmpty ? "" : "\n" + s.meta)
        }
        c.sound = .default
        c.userInfo = ["show": s.key]
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("poster-" + String(s.key.hashValue & 0x7fffffff) + ".jpg")
        if (try? image.write(to: file)) != nil,
           let att = try? UNNotificationAttachment(identifier: "poster", url: file, options: nil) {
            c.attachments = [att]
        }
        let req = UNNotificationRequest(identifier: "new-" + s.key + (episodeAlert ? "-ep" : ""), content: c, trigger: nil)
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }

    // MARK: UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if #available(iOS 14.0, *) { completionHandler([.banner, .sound]) } else { completionHandler([.alert, .sound]) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let key = response.notification.request.content.userInfo["show"] as? String {
            DispatchQueue.main.async { self.openShow?(key) }
        }
        completionHandler()
    }
}
