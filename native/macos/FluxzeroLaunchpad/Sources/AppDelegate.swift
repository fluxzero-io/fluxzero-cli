import AppKit
import Carbon
import Foundation

@MainActor
enum FluxzeroTerminationPolicy {
    private(set) static var allowsTermination = false

    static func quit() {
        allowsTermination = true
        NSApp.terminate(nil)
    }
}

@main
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItemController: StatusItemController?

    func applicationWillFinishLaunching(_ notification: Notification) {
        NSAppleEventManager.shared().setEventHandler(
            self,
            andSelector: #selector(handleGetURLEvent(_:withReplyEvent:)),
            forEventClass: AEEventClass(kInternetEventClass),
            andEventID: AEEventID(kAEGetURL)
        )
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        Task { @MainActor in
            statusItemController = StatusItemController(model: LaunchpadModel.shared)
            await LaunchpadModel.shared.prepare()
        }
    }

    @MainActor
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard FluxzeroTerminationPolicy.allowsTermination else {
            LaunchpadWindowController.shared.hide()
            SettingsWindowController.shared.hide()
            return .terminateCancel
        }
        return .terminateNow
    }

    @objc private func handleGetURLEvent(_ event: NSAppleEventDescriptor, withReplyEvent replyEvent: NSAppleEventDescriptor) {
        guard let urlString = event.paramDescriptor(forKeyword: keyDirectObject)?.stringValue,
              let url = URL(string: urlString) else {
            return
        }
        Task { @MainActor in
            LaunchpadModel.shared.handle(url: url, presentationMode: .background)
        }
    }
}
