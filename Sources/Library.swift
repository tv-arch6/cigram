import Foundation
import Security

/// «قائمتي» and «شاهدتها مؤخراً» on this iPhone (same idea as the Android app's watch-later list and history).
final class Library: NSObject {
    static let shared = Library()
    static let changed = Notification.Name("cigram.library.changed")

    private let defaults = UserDefaults.standard
    private let listKey = "cg.lib.mylist"
    private let historyKey = "cg.lib.history"
    private let likesKey = "cg.lib.likes"

    // MARK: قائمتي

    var myListKeys: [String] { return defaults.stringArray(forKey: listKey) ?? [] }

    func inMyList(_ key: String) -> Bool { return myListKeys.contains(key) }

    @discardableResult
    func toggleMyList(_ key: String) -> Bool {
        var list = myListKeys
        let added: Bool
        if let i = list.firstIndex(of: key) { list.remove(at: i); added = false } else { list.insert(key, at: 0); added = true }
        defaults.set(Array(list.prefix(300)), forKey: listKey)
        NotificationCenter.default.post(name: Library.changed, object: nil)
        return added
    }

    func myList() -> [Show] { return myListKeys.compactMap { CatalogStore.shared.show(forKey: $0) } }

    // MARK: شاهدتها مؤخراً  (a database, one set per account — see HistoryDB)

    struct Entry: Codable {
        let key: String
        let season: Int
        let episode: Int
        let at: Double
    }

    private var cached: (account: String, list: [Entry])?

    override init() {
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(accountChanged), name: Account.changed, object: nil)
    }

    /// The account changed (sign in / sign out): the screens read the new account's history.
    @objc private func accountChanged() {
        cached = nil
        NotificationCenter.default.post(name: Library.changed, object: nil)
    }

    var history: [Entry] {
        let account = HistoryDB.accountId
        if let c = cached, c.account == account { return c.list }
        let list = HistoryDB.shared.history(account: account).map { Entry(key: $0.key, season: $0.season, episode: $0.episode, at: $0.at) }
        cached = (account: account, list: list)
        return list
    }

    func record(_ show: Show, episode: CatalogItem?) {
        HistoryDB.shared.addHistory(account: HistoryDB.accountId, key: show.key, season: episode?.season ?? 0, episode: episode?.episode ?? 0,
                                    at: Date().timeIntervalSince1970)
        cached = nil
        NotificationCenter.default.post(name: Library.changed, object: nil)
    }

    func lastEpisode(of show: Show) -> CatalogItem? {
        guard let e = history.first(where: { $0.key == show.key }), e.episode > 0 else { return nil }
        return show.episodes.first { $0.season == e.season && $0.episode == e.episode }
    }

    func recent() -> [Show] { return history.compactMap { CatalogStore.shared.show(forKey: $0.key) } }

    func clearHistory() {
        HistoryDB.shared.clearHistory(account: HistoryDB.accountId)
        cached = nil
        NotificationCenter.default.post(name: Library.changed, object: nil)
    }

    // MARK: أعجبني / لم يعجبني (also sent to the account when signed in)

    func reaction(_ key: String) -> Int {
        return (defaults.dictionary(forKey: likesKey) as? [String: Int])?[key] ?? 0
    }

    func setReaction(_ key: String, _ value: Int) {
        var d = (defaults.dictionary(forKey: likesKey) as? [String: Int]) ?? [:]
        d[key] = value == 0 ? nil : value
        defaults.set(d, forKey: likesKey)
    }
}

/// The sign-in token lives in the Keychain (never in UserDefaults): another app or a backup cannot read it.
enum Keychain {
    private static let service = "com.cigram.vid.auth"

    static func set(_ value: String?, for account: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        guard let v = value, let data = v.data(using: .utf8) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: account,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
}
