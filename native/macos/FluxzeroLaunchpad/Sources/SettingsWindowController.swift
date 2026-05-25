import AppKit
import SwiftUI

@MainActor
final class SettingsWindowController: NSObject, NSWindowDelegate {
    static let shared = SettingsWindowController()

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
        let view = ProjectDefaultsSettingsView()
            .environmentObject(LaunchpadModel.shared)
            .frame(width: 560, height: 390)

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 560, height: 390),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Settings"
        window.minSize = NSSize(width: 520, height: 360)
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

struct ProjectDefaultsSettingsView: View {
    @EnvironmentObject private var model: LaunchpadModel
    private let controlWidth: CGFloat = 240

    var body: some View {
        Form {
            Section("Project Defaults") {
                LabeledContent("Location") {
                    HStack(spacing: 8) {
                        Text(model.creationDefaults.location)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Button("Choose...") {
                            model.chooseDefaultLocation()
                        }
                    }
                    .frame(width: controlWidth, alignment: .trailing)
                }

                LabeledContent("Template") {
                    TrailingControlColumn(width: controlWidth) {
                        Picker("Template", selection: templateBinding) {
                            ForEach(model.templates, id: \.self) {
                                Text($0).tag($0)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.menu)
                        .fixedSize()
                    }
                }

                LabeledContent("Build System") {
                    Picker("Build System", selection: binding(\.buildSystem)) {
                        ForEach(DesktopBuildSystem.allCases) { option in
                            Text(option.label).tag(option)
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.segmented)
                    .frame(width: controlWidth)
                }

                LabeledContent("Group ID") {
                    TextField("", text: binding(\.groupId))
                        .textFieldStyle(.roundedBorder)
                        .labelsHidden()
                        .frame(width: controlWidth)
                }

                Toggle("Initialize Git repository", isOn: binding(\.initGit))
            }

            Section("After Creation") {
                LabeledContent("Open Project In") {
                    TrailingControlColumn(width: controlWidth) {
                        Picker("Open Project In", selection: binding(\.agentChoice)) {
                            ForEach(AgentChoice.openDestinations) { option in
                                Label(option.label, systemImage: option.systemImage).tag(option)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.menu)
                        .fixedSize()
                    }
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .padding(.top, 14)
        .padding(.horizontal, 18)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    private var templateBinding: Binding<String> {
        Binding(
            get: { model.creationDefaults.template },
            set: { model.updateDefaultTemplate($0) }
        )
    }

    private func binding<Value>(_ keyPath: WritableKeyPath<ProjectCreationDefaults, Value>) -> Binding<Value> {
        Binding(
            get: { model.creationDefaults[keyPath: keyPath] },
            set: { model.updateCreationDefault(keyPath, to: $0) }
        )
    }
}

struct TrailingControlColumn<Content: View>: View {
    let width: CGFloat
    @ViewBuilder var content: Content

    var body: some View {
        HStack {
            Spacer(minLength: 0)
            content
        }
        .frame(width: width, alignment: .trailing)
    }
}
