import Foundation
import Security

/// «قائمتي» and «شاهدتها مؤخراً» on this iPhone (same idea as the Android app's watch-later list and history).
final class Library {
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

    // MARK: شاهدتها مؤخراً

    struct Entry: Codable {
        let key: String
        let season: Int
        let episode: Int
        let at: Double
    }

    var history: [Entry] {
        guard let d = defaults.data(forKey: historyKey), let list = try? JSONDecoder().decode([Entry].self, from: d) else { return [] }
        return list
    }

    func record(_ show: Show, episode: CatalogItem?) {
        var list = history.filter { $0.key != show.key }
        list.insert(Entry(key: show.key, season: episode?.season ?? 0, episode: episode?.episode ?? 0, at: Date().timeIntervalSince1970), at: 0)
        if let d = try? JSONEncoder().encode(Array(list.prefix(60))) { defaults.set(d, forKey: historyKey) }
        NotificationCenter.default.post(name: Library.changed, object: nil)
    }

    func lastEpisode(of show: Show) -> CatalogItem? {
        guard let e = history.first(where: { $0.key == show.key }), e.episode > 0 else { return nil }
        return show.episodes.first { $0.season == e.season && $0.episode == e.episode }
    }

    func recent() -> [Show] { return history.compactMap { CatalogStore.shared.show(forKey: $0.key) } }

    func clearHistory() {
        defaults.removeObject(forKey: historyKey)
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
