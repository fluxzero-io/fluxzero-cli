# macOS Menu Bar Spike

This branch explores turning Fluxzero Launchpad into a menu-bar-first app that can stay out of the user's way when a browser opens a `fluxzero://` URL.

## Apple Defaults Checked

- Use SwiftUI `MenuBarExtra` for a persistent control in the system menu bar. Apple documents it for commonly used functionality while the app is inactive, and also for utility apps that only show in the menu bar: <https://developer.apple.com/documentation/swiftui/menubarextra>
- Use `LSUIElement=true` for an agent app that runs in the background and does not appear in the Dock: <https://developer.apple.com/documentation/bundleresources/information-property-list/lsuielement>
- Keep the menu-bar icon as a black/transparent template image so macOS can tint it correctly for light, dark, and selected menu bars. Apple calls this out in the macOS menu bar HIG: <https://developer.apple.com/design/human-interface-guidelines/the-menu-bar>
- Keep error alerts sparse and specific. Apple recommends alerts only for important, immediate problems and direct, useful copy: <https://developer.apple.com/design/human-interface-guidelines/alerts>
- Keep a fallback path because Apple notes menu-bar extras can be hidden when there is not enough space. The URL scheme and Finder fallback remain the real recovery path, not the visible icon alone.

## Prototype Behavior

- The app starts as a menu-bar extra and no longer opens the Launchpad window on launch.
- The existing Launchpad window is still available through `Create Project...` in the menu bar.
- `fluxzero://new?...` is handled headlessly when received as an external URL. It maps to the direct create flow, so a cold-start link can prepare the managed CLI before generation.
- `fluxzero://create?...` and `fluxzero://open?...` now open Finder when no `agent` is provided.
- `agent=codex`, `agent=claude`, and `agent=both` keep opening the requested agent after generation.
- During work, the menu-bar logo rotates slowly. The committed image is a template PNG generated from the transparent SVG logo.
- Failures are shown with a standard `NSAlert`; the menu bar status text also keeps the latest status.

## Open Questions Before Productizing

- Confirm whether `fluxzero://new` should always generate headlessly, or whether it should only do so when a required `name` query parameter is present.
- Add a real user preference for hiding/showing the menu-bar extra if this becomes a long-running user setting.
- Decide if the app should use a colored logo in the menu while idle. The current prototype follows Apple's template-icon guidance and lets the system tint it.
- Consider adding a nonmodal notification for successful generation. I left success quiet because the next app or Finder opens immediately.
