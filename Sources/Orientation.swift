import UIKit

/// Which screen orientations the app allows right now.
///  - the app itself is portrait only (every page is drawn for it);
///  - while a film / series / channel player is on screen landscape is allowed too (the player opens in landscape);
///  - the «القنوات» tab may rotate (portrait / landscape), its list re-flows to the width;
///  - the moment the last player closes (or the viewer leaves the channels tab) the app is turned back to portrait by
///    force: before this, a player closed in landscape left the home page lying on its side.
/// iPad keeps every orientation.
enum AppOrientation {
    /// Players on screen (a film player that opens the browser player counts once).
    private(set) static var players = 0
    static var channelsTab = false

    static var mask: UIInterfaceOrientationMask {
        if UIDevice.current.userInterfaceIdiom == .pad { return .all }
        return (players > 0 || channelsTab) ? .allButUpsideDown : .portrait
    }

    private static var window: UIWindow? { return (UIApplication.shared.delegate as? AppDelegate)?.window }

    static func enterPlayer() {
        players += 1
        refresh()
    }

    static func leavePlayer() {
        players = max(0, players - 1)
        if players == 0 && !channelsTab { force(.portrait) } else { refresh() }
    }

    static func channelsTabChanged(_ on: Bool) {
        channelsTab = on
        if !on && players == 0 { force(.portrait) } else { refresh() }
    }

    /// Asks iOS to read the allowed orientations again.
    static func refresh() {
        if #available(iOS 16.0, *) { window?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations() }
        UIViewController.attemptRotationToDeviceOrientation()
    }

    /// The rotate button of a player: landscape <-> portrait.
    static func rotate(from view: UIView) {
        let toLandscape = view.bounds.height > view.bounds.width
        force(toLandscape ? .landscapeRight : .portrait)
    }

    /// Turns the interface to `o` now (the allowed orientations were already narrowed by the caller).
    static func force(_ o: UIInterfaceOrientation) {
        if UIDevice.current.userInterfaceIdiom == .pad { refresh(); return }
        if #available(iOS 16.0, *) {
            window?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            let m: UIInterfaceOrientationMask = o.isLandscape ? .landscape : .portrait
            window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: m), errorHandler: { _ in })
        } else {
            UIDevice.current.setValue(o.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }
}
