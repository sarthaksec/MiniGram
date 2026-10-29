# MiniGram

A lightweight, distraction-free Instagram client for iOS.

MiniGram wraps Instagram Web in a native SwiftUI `WKWebView` container, stripping out algorithmic retention loops (the infinite Home Feed, Reels, and Explore) while preserving direct communication (DMs, Stories, Profile, and Notifications).

---

## Why MiniGram?

When people delete the native Instagram app to reduce screen time, muscle memory often leads them to `instagram.com` on mobile Safari. Over time, Meta has updated its mobile web interface to mirror the native app, complete with infinite feed pagination, algorithmic suggested posts, and the Reels tab.

MiniGram eliminates the compulsive feed loop while keeping the utility:
- **Stories**: Kept intact at the top of the interface.
- **Direct Messages**: Fully functional with real-time conversations.
- **Home Feed**: Blocked. The infinite post feed below stories is removed.
- **Reels & Explore**: Blocked both in the navigation bar and at the routing layer.
- **Zero Credential Risk**: Uses Apple's native `WKWebsiteDataStore`. The app never accesses, logs, or stores your login credentials; authentication is handled directly by Instagram's official login system.

---

## Technical Architecture

- **Pre-Paint CSS Injection**: Style overrides are injected at `WKUserScriptInjectionTime.atDocumentStart`, preventing any brief "flash of feed" before content hides.
- **Timeline Query Interception**: When feed posts are removed from the DOM, Instagram's infinite scroll listener normally triggers endless network calls to fetch more posts. MiniGram intercepts timeline GraphQL and timeline API calls at the `fetch` level, eliminating CPU thrashing, overheating, and UI lag.
- **Single Page App (SPA) Guard**: Monkey-patches HTML5 `history.pushState` and `history.replaceState` in addition to native `WKNavigationDelegate` handlers, ensuring internal client-side transitions cannot navigate to Reels or Explore.
- **Safe Area Insets**: Positioned natively below the iOS Dynamic Island and notch, keeping headers, back buttons, and chat navigation responsive to touch.
- **No-Mac CI/CD Build Pipeline**: The repository includes a GitHub Actions workflow that compiles the SwiftUI project on a macOS runner via `XcodeGen` and `xcodebuild`, outputting an unsigned `.ipa` for sideloading from Windows or Linux.

---

## Installation

### Method 1: Sideload via Windows / Mac (Recommended)

1. **Download the IPA**:
   - Go to the [Actions](https://github.com/sarthaksec/MiniGram/actions) tab in this repository.
   - Click the latest successful build.
   - Download `MiniGram-iOS.zip` under **Artifacts** and unzip to get `MiniGram.ipa`.

2. **Install using Sideloadly**:
   - Download [Sideloadly](https://sideloadly.io/) (Windows/macOS).
   - Connect your iPhone via USB.
   - Drag `MiniGram.ipa` into Sideloadly, enter your Apple ID, and click **Start**.

3. **Trust Developer Certificate**:
   - On your iPhone: Go to **Settings > General > VPN & Device Management**.
   - Tap your Apple ID under *Developer App* and select **Trust**.
   - On iOS 17+: Enable **Settings > Privacy & Security > Developer Mode** (requires a device restart).

### Method 2: Build locally in Xcode (macOS)

```bash
brew install xcodegen
xcodegen generate
open MiniGram.xcodeproj
```
Select your Development Team, connect your iPhone, and hit **Cmd + R**.

---

## License

MIT
