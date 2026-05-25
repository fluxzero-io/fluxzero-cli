import AppKit
import SwiftUI

@MainActor
enum FluxzeroWindowActivation {
    private static var visibleWindows: Set<ObjectIdentifier> = []

    static func windowWillShow(_ window: NSWindow) {
        visibleWindows.insert(ObjectIdentifier(window))
        NSApp.setActivationPolicy(.regular)
    }

    static func windowDidClose(_ window: NSWindow) {
        visibleWindows.remove(ObjectIdentifier(window))
        guard visibleWindows.isEmpty else { return }
        NSApp.setActivationPolicy(.accessory)
    }
}

@MainActor
final class LaunchpadWindowController: NSObject, NSWindowDelegate {
    static let shared = LaunchpadWindowController()

    private var window: NSWindow?

    func show() {
        let window = window ?? makeWindow()
        self.window = window
        FluxzeroWindowActivation.windowWillShow(window)
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func hide() {
        window?.close()
    }

    private func makeWindow() -> NSWindow {
        let view = LaunchpadView()
            .environmentObject(LaunchpadModel.shared)
            .frame(minWidth: 700, minHeight: 520)
            .background(WindowSizeLimiter(minSize: NSSize(width: 700, height: 520), maxSize: NSSize(width: 760, height: 640)))

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 720, height: 540),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Fluxzero Launchpad"
        window.minSize = NSSize(width: 700, height: 520)
        window.maxSize = NSSize(width: 760, height: 640)
        window.contentView = NSHostingView(rootView: view)
        window.isReleasedWhenClosed = false
        window.delegate = self
        return window
    }

    func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSWindow else { return }
        FluxzeroWindowActivation.windowDidClose(window)
    }
}
