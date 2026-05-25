import AppKit
import Combine

@MainActor
final class StatusItemController: NSObject, NSMenuDelegate {
    private let model: LaunchpadModel
    private let statusItem: NSStatusItem
    private let menu = NSMenu()
    private var cancellables: Set<AnyCancellable> = []
    private var animationTimer: Timer?
    private var rotation: CGFloat = 0

    init(model: LaunchpadModel = .shared) {
        self.model = model
        statusItem = NSStatusBar.system.statusItem(withLength: 28)
        super.init()
        configureStatusItem()
        bindModel()
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        rebuildMenu()
    }

    private func configureStatusItem() {
        statusItem.isVisible = true
        statusItem.menu = menu
        menu.delegate = self

        if let button = statusItem.button {
            button.image = FluxzeroMenuBarAssets.templateImage
            button.imagePosition = .imageOnly
            button.imageScaling = .scaleProportionallyDown
            button.toolTip = "Fluxzero Launchpad"
            button.setAccessibilityLabel("Fluxzero")
        }
    }

    private func bindModel() {
        model.$isBusy
            .receive(on: RunLoop.main)
            .sink { [weak self] isBusy in
                self?.setAnimating(isBusy)
            }
            .store(in: &cancellables)

        model.$errorMessage
            .compactMap { $0 }
            .receive(on: RunLoop.main)
            .sink { message in
                FluxzeroAlertPresenter.show(message) {
                    LaunchpadModel.shared.errorMessage = nil
                }
            }
            .store(in: &cancellables)
    }

    private func rebuildMenu() {
        menu.removeAllItems()

        menu.addItem(item("Create Project...", action: #selector(showLaunchpad), enabled: !model.isBusy))

        menu.addItem(.separator())
        if model.isBusy {
            menu.addItem(statusItem(title: model.statusMessage))
        } else if model.isLaunchpadUpToDate {
            menu.addItem(statusItem(title: "Fluxzero Launchpad is up to date."))
        } else {
            menu.addItem(item("Check for Updates", action: #selector(refresh)))
        }
        menu.addItem(item("Settings", action: #selector(showSettings)))

        menu.addItem(.separator())
        menu.addItem(item("Quit", action: #selector(quit)))
    }

    private func item(_ title: String, action: Selector, enabled: Bool = true) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        item.isEnabled = enabled
        return item
    }

    private func statusItem(title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func setAnimating(_ isAnimating: Bool) {
        if isAnimating {
            guard animationTimer == nil else { return }
            animationTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 24.0, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    self?.tick()
                }
            }
        } else {
            animationTimer?.invalidate()
            animationTimer = nil
            rotation = 0
            statusItem.button?.image = FluxzeroMenuBarAssets.templateImage
        }
    }

    private func tick() {
        rotation = (rotation + 360.0 / (8.0 * 24.0)).truncatingRemainder(dividingBy: 360.0)
        statusItem.button?.image = FluxzeroMenuBarAssets.rotatedTemplateImage(degrees: rotation)
    }

    @objc private func showLaunchpad() {
        LaunchpadWindowController.shared.show()
    }

    @objc private func refresh() {
        model.refresh()
    }

    @objc private func showSettings() {
        SettingsWindowController.shared.show()
    }

    @objc private func quit() {
        FluxzeroTerminationPolicy.quit()
    }
}
