import UIKit
import GoogleMobileAds

/// AdMob on the iPhone.
/// App ID: the «Cigram» AdMob app (ca-app-pub-7010031756978837~1229517223), set in project.yml (GADApplicationIdentifier).
/// Units — taken from the Android app (CigramAdConfig.java), exactly like Android:
///   • interstitial before a title starts  = REAL_PLAYER_INTERSTITIAL (…/3504326778);
///   • banner                              = the admin's remote ad config key "banner_unit" (empty = no banner, as on Android).
/// Debug builds (the simulator check) use Google's test units, so testing never touches the real ones.
/// If AdMob reports no fill for these (they were created for the Android app), create an iOS app + units in AdMob and swap `real…` below.
enum AdIDs {
    static let testBanner = "ca-app-pub-3940256099942544/2934735716"
    static let testInterstitial = "ca-app-pub-3940256099942544/4411468910"
    static let realInterstitial = "ca-app-pub-7010031756978837/3504326778"

    static var interstitial: String {
        #if DEBUG
        return testInterstitial
        #else
        return realInterstitial
        #endif
    }
}

/// Same switches as the Android app: the admin's /ads/config (ads on/off, player ads, cooldown, per-hour cap,
/// ad-free accounts). An interstitial may show before a title starts; a banner sits at the end of Home.
final class Ads: NSObject, FullScreenContentDelegate {
    static let shared = Ads()
    static let changed = Notification.Name("cigram.ads.changed")

    private(set) var enabled = true
    private var playerAds = true
    private var cooldownMin = 10
    private var maxPerHour = 4
    private var adFreeIds: [String] = []
    private var remoteBannerUnit = ""
    private var bannerView: BannerView?
    private var interstitial: InterstitialAd?
    private var loading = false
    private var shown: [Date] = []
    private var after: (() -> Void)?

    func start() {
        MobileAds.shared.start(completionHandler: nil)
        loadConfig()
    }

    private func loadConfig() {
        guard let url = URL(string: Backend.admin + "/ads/config") else { return }
        URLSession.shared.dataTask(with: URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)) { data, _, _ in
            guard let d = data, let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else {
                DispatchQueue.main.async { self.loadInterstitial() }
                return
            }
            DispatchQueue.main.async {
                self.enabled = (o["ads_enabled"] as? Bool) ?? true
                self.playerAds = (o["player_ads_enabled"] as? Bool) ?? true
                self.cooldownMin = (o["cooldown_min"] as? NSNumber)?.intValue ?? 10
                self.maxPerHour = (o["max_ads_per_hour"] as? NSNumber)?.intValue ?? 4
                self.adFreeIds = (o["ad_free_user_ids"] as? [Any])?.map { String(describing: $0) } ?? []
                let unit = ((o["banner_unit"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
                self.remoteBannerUnit = (unit.hasPrefix("ca-app-pub-") && unit.contains("/")) ? unit : ""
                self.loadInterstitial()
                NotificationCenter.default.post(name: Ads.changed, object: nil)
            }
        }.resume()
    }

    private var active: Bool {
        if !enabled { return false }
        if let id = Account.shared.userId, adFreeIds.contains(id) { return false }
        return true
    }

    private func loadInterstitial() {
        guard active, playerAds, interstitial == nil, !loading else { return }
        loading = true
        InterstitialAd.load(with: AdIDs.interstitial, request: Request()) { [weak self] ad, _ in
            guard let self = self else { return }
            self.loading = false
            self.interstitial = ad
            ad?.fullScreenContentDelegate = self
        }
    }

    private func allowedNow() -> Bool {
        let now = Date()
        shown = shown.filter { now.timeIntervalSince($0) < 3600 }
        if shown.count >= maxPerHour { return false }
        if let last = shown.last, now.timeIntervalSince(last) < Double(cooldownMin * 60) { return false }
        return true
    }

    /// Shows an interstitial before playback when the admin's rules allow it; `then` runs right after (or at once).
    func beforePlay(from vc: UIViewController, then: @escaping () -> Void) {
        guard active, playerAds, let ad = interstitial, allowedNow() else {
            then()
            loadInterstitial()
            return
        }
        after = then
        shown.append(Date())
        ad.present(from: vc)
    }

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) { finish() }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) { finish() }

    private func finish() {
        interstitial = nil
        let next = after
        after = nil
        next?()
        loadInterstitial()
    }

    /// The banner unit for this build: the admin's `banner_unit` (like Android); Debug builds use Google's test banner.
    private var bannerUnit: String {
        #if DEBUG
        return AdIDs.testBanner
        #else
        return remoteBannerUnit
        #endif
    }

    /// A standard banner for the end of Home (nil when ads are off for this viewer or the admin set no banner unit).
    /// One instance is reused, so a screen refresh never asks AdMob for a new ad.
    func banner(in vc: UIViewController) -> UIView? {
        guard active, !bannerUnit.isEmpty else { return nil }
        if let b = bannerView, b.adUnitID == bannerUnit {
            b.rootViewController = vc
            return b
        }
        let b = BannerView(adSize: AdSizeBanner)
        b.adUnitID = bannerUnit
        b.rootViewController = vc
        b.load(Request())
        bannerView = b
        return b
    }
}
