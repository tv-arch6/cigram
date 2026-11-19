import Foundation
import SQLite3

// The watch history and the places the viewer stopped, kept in a real database (SQLite, cigram.sqlite in the app's own folder) —
// one set of rows PER ACCOUNT. Signing out switches to the guest rows; signing in again brings the account's rows back. The rows
// survive app updates and restarts. The first start after this version moves what the old versions kept in UserDefaults into the
// database (once), for the account that is signed in at that moment (the guest otherwise).

struct HistoryRow {
    let key: String
    let season: Int
    let episode: Int
    let at: Double
}

struct ProgressRow {
    var pos: Double
    var dur: Double
    var finished: Bool
    var at: Double
}

final class HistoryDB {
    static let shared = HistoryDB()

    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "cigram.history.db")
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    // if the database cannot be opened the app still works (in memory, until it closes)
    private var memHistory: [String: [HistoryRow]] = [:]
    private var memProgress: [String: ProgressRow] = [:]

    /// «u<id>» for a signed-in account, «guest» otherwise.
    static var accountId: String {
        if let id = Account.shared.userId, !id.isEmpty { return "u" + id }
        return "guest"
    }

    private init() {
        queue.sync { open() }
    }

    // MARK: opening

    private func open() {
        let url = DownloadPaths.base.appendingPathComponent("cigram.sqlite")
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        if sqlite3_open_v2(url.path, &handle, flags, nil) != SQLITE_OK {
            NSLog("CIGRAM_CHECK history database not opened")
            if let h = handle { sqlite3_close(h) }
            return
        }
        db = handle
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA synchronous=NORMAL")
        exec("""
        CREATE TABLE IF NOT EXISTS history (
            account TEXT NOT NULL, key TEXT NOT NULL, season INTEGER NOT NULL DEFAULT 0, episode INTEGER NOT NULL DEFAULT 0,
            at REAL NOT NULL, PRIMARY KEY (account, key))
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS progress (
            account TEXT NOT NULL, pkey TEXT NOT NULL, pos REAL NOT NULL, dur REAL NOT NULL, finished INTEGER NOT NULL DEFAULT 0,
            at REAL NOT NULL, PRIMARY KEY (account, pkey))
        """)
        exec("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
        exec("CREATE INDEX IF NOT EXISTS history_at ON history (account, at DESC)")
        migrateOldData()
    }

    @discardableResult
    private func exec(_ sql: String) -> Bool {
        guard let db = db else { return false }
        return sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK
    }

    /// Runs one statement: `bind` fills the ? marks, `row` is called for every row it returns.
    private func run(_ sql: String, bind: (OpaquePointer) -> Void, row: ((OpaquePointer) -> Void)? = nil) {
        guard let db = db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let st = stmt else { return }
        defer { sqlite3_finalize(st) }
        bind(st)
        while sqlite3_step(st) == SQLITE_ROW { row?(st) }
    }

    private func bindText(_ st: OpaquePointer, _ i: Int32, _ s: String) { sqlite3_bind_text(st, i, s, -1, transient) }

    private func text(_ st: OpaquePointer, _ i: Int32) -> String {
        guard let c = sqlite3_column_text(st, i) else { return "" }
        return String(cString: c)
    }

    private func metaValue(_ k: String) -> String? {
        var out: String?
        run("SELECT v FROM meta WHERE k = ?", bind: { self.bindText($0, 1, k) }, row: { out = self.text($0, 0) })
        return out
    }

    private func setMeta(_ k: String, _ v: String) {
        run("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", bind: { self.bindText($0, 1, k); self.bindText($0, 2, v) })
    }

    /// The history and the resume positions the old versions kept in UserDefaults move here, once.
    private func migrateOldData() {
        if metaValue("migrated_v1") != nil { return }
        let d = UserDefaults.standard
        let owner = HistoryDB.accountId
        if let data = d.data(forKey: "cg.lib.history"), let list = try? JSONDecoder().decode([Library.Entry].self, from: data) {
            for e in list { insertHistory(account: owner, key: e.key, season: e.season, episode: e.episode, at: e.at) }
        }
        if let data = d.data(forKey: "cg.progress.v1"), let map = try? JSONDecoder().decode([String: PlaybackStore.Entry].self, from: data) {
            for (k, e) in map { upsertProgress(account: owner, key: k, row: ProgressRow(pos: e.pos, dur: e.dur, finished: e.finished, at: e.at)) }
        }
        setMeta("migrated_v1", String(Date().timeIntervalSince1970))
        d.removeObject(forKey: "cg.lib.history")
        d.removeObject(forKey: "cg.progress.v1")
    }

    // MARK: history

    private func insertHistory(account: String, key: String, season: Int, episode: Int, at: Double) {
        run("INSERT OR REPLACE INTO history (account, key, season, episode, at) VALUES (?, ?, ?, ?, ?)", bind: { st in
            self.bindText(st, 1, account)
            self.bindText(st, 2, key)
            sqlite3_bind_int64(st, 3, Int64(season))
            sqlite3_bind_int64(st, 4, Int64(episode))
            sqlite3_bind_double(st, 5, at)
        })
    }

    func addHistory(account: String, key: String, season: Int, episode: Int, at: Double) {
        queue.sync {
            if db == nil {
                var list = memHistory[account] ?? []
                list.removeAll { $0.key == key }
                list.insert(HistoryRow(key: key, season: season, episode: episode, at: at), at: 0)
                memHistory[account] = Array(list.prefix(200))
                return
            }
            insertHistory(account: account, key: key, season: season, episode: episode, at: at)
            // the newest 200 of each account are kept
            run("DELETE FROM history WHERE account = ? AND key NOT IN (SELECT key FROM history WHERE account = ? ORDER BY at DESC LIMIT 200)",
                bind: { self.bindText($0, 1, account); self.bindText($0, 2, account) })
        }
    }

    func history(account: String, limit: Int = 200) -> [HistoryRow] {
        return queue.sync {
            if db == nil { return Array((memHistory[account] ?? []).prefix(limit)) }
            var out: [HistoryRow] = []
            run("SELECT key, season, episode, at FROM history WHERE account = ? ORDER BY at DESC LIMIT ?", bind: { st in
                self.bindText(st, 1, account)
                sqlite3_bind_int64(st, 2, Int64(limit))
            }, row: { st in
                out.append(HistoryRow(key: self.text(st, 0), season: Int(sqlite3_column_int64(st, 1)),
                                      episode: Int(sqlite3_column_int64(st, 2)), at: sqlite3_column_double(st, 3)))
            })
            return out
        }
    }

    func clearHistory(account: String) {
        queue.sync {
            if db == nil { memHistory[account] = []; return }
            run("DELETE FROM history WHERE account = ?", bind: { self.bindText($0, 1, account) })
        }
    }

    // MARK: where the viewer stopped

    private func upsertProgress(account: String, key: String, row: ProgressRow) {
        run("INSERT OR REPLACE INTO progress (account, pkey, pos, dur, finished, at) VALUES (?, ?, ?, ?, ?, ?)", bind: { st in
            self.bindText(st, 1, account)
            self.bindText(st, 2, key)
            sqlite3_bind_double(st, 3, row.pos)
            sqlite3_bind_double(st, 4, row.dur)
            sqlite3_bind_int64(st, 5, row.finished ? 1 : 0)
            sqlite3_bind_double(st, 6, row.at)
        })
    }

    func saveProgress(account: String, key: String, row: ProgressRow) {
        queue.sync {
            if db == nil { memProgress[account + "|" + key] = row; return }
            upsertProgress(account: account, key: key, row: row)
        }
    }

    func progress(account: String, key: String) -> ProgressRow? {
        return queue.sync {
            if db == nil { return memProgress[account + "|" + key] }
            var out: ProgressRow?
            run("SELECT pos, dur, finished, at FROM progress WHERE account = ? AND pkey = ?", bind: { st in
                self.bindText(st, 1, account)
                self.bindText(st, 2, key)
            }, row: { st in
                out = ProgressRow(pos: sqlite3_column_double(st, 0), dur: sqlite3_column_double(st, 1),
                                  finished: sqlite3_column_int64(st, 2) != 0, at: sqlite3_column_double(st, 3))
            })
            return out
        }
    }

    func clearProgress(account: String) {
        queue.sync {
            if db == nil { for k in memProgress.keys where k.hasPrefix(account + "|") { memProgress[k] = nil }; return }
            run("DELETE FROM progress WHERE account = ?", bind: { self.bindText($0, 1, account) })
        }
    }

    /// Everything of one account (when the account is deleted).
    func wipe(account: String) {
        clearHistory(account: account)
        clearProgress(account: account)
    }
}
