import UIKit
import MachO
import Darwin

/// Integrity checks («حماية التطبيق»). They run at start and when the app returns to the front.
///
/// What blocks the app (release builds only): a debugger attached to it, or an instrumentation library (Frida and
/// friends) loaded into it. Both are how people crack an app or change what it sends.
/// What only warns: a jailbroken phone (many honest users have one).
/// Network: App Transport Security stays ON for every Cigram server (HTTPS with a valid certificate, TLS 1.2+). Only the
/// players' web pages and media streams have their own exception (see Info.plist).
enum Integrity {
    enum Verdict { case ok, jailbroken, tampered(String) }

    static func check() -> Verdict {
        #if DEBUG
        return .ok
        #else
        if debuggerAttached() { return .tampered("debugger") }
        if let lib = injectedLibrary() { return .tampered(lib) }
        if jailbroken() { return .jailbroken }
        return .ok
        #endif
    }

    static func debuggerAttached() -> Bool {
        var info = kinfo_proc()
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        var size = MemoryLayout<kinfo_proc>.stride
        let r = sysctl(&mib, UInt32(mib.count), &info, &size, nil, 0)
        return r == 0 && (info.kp_proc.p_flag & P_TRACED) != 0
    }

    static func injectedLibrary() -> String? {
        let suspects = ["frida", "fridagadget", "libcycript", "cynject", "sslkillswitch", "flexloader", "libinjector"]
        for i in 0..<_dyld_image_count() {
            guard let c = _dyld_get_image_name(i) else { continue }
            let name = String(cString: c).lowercased()
            for s in suspects where name.contains(s) { return s }
        }
        return nil
    }

    static func jailbroken() -> Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        let paths = ["/Applications/Cydia.app", "/Applications/Sileo.app", "/var/jb", "/usr/sbin/sshd", "/etc/apt",
                     "/private/var/lib/apt/", "/usr/bin/ssh", "/Library/MobileSubstrate/MobileSubstrate.dylib"]
        for p in paths where FileManager.default.fileExists(atPath: p) { return true }
        let probe = "/private/cigram_jb_probe.txt"
        if (try? "x".write(toFile: probe, atomically: true, encoding: .utf8)) != nil {
            try? FileManager.default.removeItem(atPath: probe)
            return true                                                     // the sandbox should never allow this
        }
        return false
        #endif
    }
}

/// The screen shown instead of the app when the integrity check fails.
final class BlockedViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        let icon = UIImageView(image: UIImage(systemName: "lock.shield.fill"))
        icon.tintColor = Theme.red
        icon.contentMode = .scaleAspectFit
        let title = UILabel()
        title.text = "هذه النسخة غير آمنة"
        title.font = Theme.font(22, .heavy)
        title.textColor = .white
        title.textAlignment = .center
        let body = UILabel()
        body.text = "تم اكتشاف أداة تعديل أو تتبّع داخل التطبيق. ثبّت سيكرام من المصدر الرسمي ثم افتحه من جديد."
        body.font = Theme.font(15)
        body.textColor = Theme.text2
        body.textAlignment = .center
        body.numberOfLines = 0
        let stack = UIStackView(arrangedSubviews: [icon, title, body])
        stack.axis = .vertical
        stack.spacing = 14
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            icon.heightAnchor.constraint(equalToConstant: 64), icon.widthAnchor.constraint(equalToConstant: 64),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 30),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -30),
        ])
    }
}
