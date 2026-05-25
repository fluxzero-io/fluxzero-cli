import AppKit
import Combine

@MainActor
final class StatusItemController: NSObject, NSMenuDelegate {
    private let model: LaunchpadModel
    private let statusItem: NSStatusItem
    private let menu = NSMenu()
    private var cancellables: Set<AnyCancellable> = []
    private var animationTimer: Timer?
    private var settleTimer: Timer?
    private var rotation: CGFloat = 0
    private static let animationFramesPerSecond: TimeInterval = 24
    private static let fullRotationDuration: CGFloat = 8
    private static let hexagonStep: CGFloat = 60
    private static let angleTolerance: CGFloat = 0.001
    private static let rotationStep = 360.0 / (fullRotationDuration * CGFloat(animationFramesPerSecond))

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
            menu.addItem(statusItem(title: "Fluxzero Launchpad is up to date"))
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
            settleTimer?.invalidate()
            settleTimer = nil
            guard animationTimer == nil else { return }
            animationTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / Self.animationFramesPerSecond, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    self?.advanceRotation()
                }
            }
        } else {
            let wasAnimating = animationTimer != nil
            animationTimer?.invalidate()
            animationTimer = nil
            guard wasAnimating else { return }
            settleToNextHexagonStep()
        }
    }

    private func advanceRotation() {
        setRotation(rotation + Self.rotationStep)
    }

    private func settleToNextHexagonStep() {
        settleTimer?.invalidate()
        let target = nextHexagonStep(after: rotation)
        if forwardDistance(from: rotation, to: target) <= Self.rotationStep {
            setRotation(target)
            return
        }

        settleTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / Self.animationFramesPerSecond, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }

                if self.forwardDistance(from: self.rotation, to: target) <= Self.rotationStep {
                    self.setRotation(target)
                    self.settleTimer?.invalidate()
                    self.settleTimer = nil
                } else {
                    self.advanceRotation()
                }
            }
        }
    }

    private func setRotation(_ degrees: CGFloat) {
        rotation = normalizedDegrees(degrees)
        statusItem.button?.image = FluxzeroMenuBarAssets.rotatedTemplateImage(degrees: rotation)
    }

    private func nextHexagonStep(after degrees: CGFloat) -> CGFloat {
        let normalized = normalizedDegrees(degrees)
        let currentStep = floor(normalized / Self.hexagonStep) * Self.hexagonStep
        let remainder = normalized - currentStep
        if remainder < Self.angleTolerance {
            return normalizedDegrees(currentStep)
        }
        return normalizedDegrees(currentStep + Self.hexagonStep)
    }

    private func forwardDistance(from start: CGFloat, to end: CGFloat) -> CGFloat {
        normalizedDegrees(end - start)
    }

    private func normalizedDegrees(_ degrees: CGFloat) -> CGFloat {
        let normalized = degrees.truncatingRemainder(dividingBy: 360)
        return normalized >= 0 ? normalized : normalized + 360
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
