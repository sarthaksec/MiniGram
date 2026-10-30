# MiniGram

Dual-platform (iOS & Android) distraction-free client for Instagram Web, engineered to surgically excise algorithmic retention loops (algorithmic feed pagination, Reels feeds, and Explore discovery) while keeping real-time communication channels (Direct Messages, Stories tray, User Profiles, and Notifications) fully operational.

Implemented in **SwiftUI / WebKit** on iOS and **Kotlin / Android WebView** on Android, featuring zero local credential storage and a multi-tiered reverse-engineering runtime injection architecture.

---

## Technical Architecture & Deep Tech Stack

MiniGram does not rely on static element hiding or external proxies. It operates directly at the rendering engine, ECMAScript prototype, and network virtualization layers inside native WebViews.

```
┌────────────────────────────────────────────────────────────────────────┐
│                              MiniGram Core                             │
├────────────────────────────────────────────────────────────────────────┤
│  [Native Layer]   iOS (WKWebView / SwiftUI)  │ Android (WebView / KTX) │
├────────────────────────────────────────────────────────────────────────┤
│  [Network Layer]  window.fetch Hook          │ shouldInterceptRequest  │
│                   Empty Edge Telemetry       │ ByteStream Interceptor  │
├────────────────────────────────────────────────────────────────────────┤
│  [Media Layer]    HTMLMediaElement.prototype (play / load / src)       │
│                   Hardware Decoding Killer   │ Stories Stream Bypass   │
├────────────────────────────────────────────────────────────────────────┤
│  [DOM & Touch]    findReelCard DOM Traversal │ touch-action: pan-y     │
│                   MutationObserver Engine    │ React Fiber Safe Exit   │
└────────────────────────────────────────────────────────────────────────┘
```

### 1. Multi-Tiered Algorithmic Neutralization Engine

#### Tier 1: Network & GraphQL Interception
Instagram Web dynamically queries GraphQL and REST endpoints to paginate post feeds and chain subsequent Reels.
- **Client-Side `window.fetch` Monkey-Patching**: Intercepts outgoing requests targeting `/api/v1/clips/`, `PolarisClips`, `PolarisFeedQuery`, `xdt_api__v1__clips`, and `xdt_api__v1__feed__timeline`. Returns synthetic HTTP 200 responses containing zero-edge JSON payloads with `"has_next_page": false` and `"end_cursor": null`. This halts pagination listeners before background workers attempt downstream fetches.
- **Native Android Request Interception (`WebViewClient.shouldInterceptRequest`)**: Inspects low-level HTTP headers, specifically `X-FB-Friendly-Name`, directly intercepting `PolarisFeedQuery`, `PolarisClipsFeedQuery`, and `PolarisExploreFeedQuery` at the Chromium socket layer before data reaches the radio.

#### Tier 2: `HTMLMediaElement` Prototype Trapping
To prevent shared video reels in direct messages from preloading, buffering chunks, or initiating background autoplay:
- **`HTMLMediaElement.prototype.play`**: Intercepted at document boot. If `this.tagName === 'VIDEO'` and `!isStories()`, execution immediately pauses the element, mutes the track, and rejects the returned promise with a silent `NotAllowedError` DOMException.
- **`HTMLMediaElement.prototype.load`**: Suppressed for video elements outside of `/stories/`, preventing background buffer allocation.
- **Property Descriptor Override (`Object.defineProperty`)**: Overrides the `src` setter on `HTMLMediaElement.prototype`. When evaluating video sources within `/direct/` or inside elements flagged with `[data-minigram-card="true"]`, incoming media CDN URLs (`*.cdninstagram.com`, `*.fbcdn.net`) and blob object URLs (`blob:https://...`) are rewritten to empty strings (`""`), completely preventing hardware decoders from acquiring media bytes.
- **Voice Note Preservation**: Explicitly isolates `HTMLAudioElement` and audio tag instances, ensuring voice notes in DMs retain full playback functionality.

#### Tier 3: Semantic DOM Boundary Resolution & Inert Click Shields
Shared Reels in DMs are rendered as nested components with complex sub-containers (media thumbnail boxes, preview headers, and bottom metadata containers).
- **`findReelCard` Traversal Algorithm**: Instead of terminating at the nearest click target (`element.closest()`), `findReelCard` recursively traverses parent nodes until it locates the top-level message bubble container (bounded before semantic ARIA containers `role="row"`, `role="feed"`, `role="list"`, or sibling reaction/reply button groups).
- **100% Geometry Coverage Shield**: Appends a `.minigram-reel-shield` overlay directly over the outer card container with `position: absolute !important; inset: 0 !important; width: 100% !important; height: 100% !important; border-radius: inherit !important;`.
- **Gesture Preservation via `touch-action: pan-y`**: By setting `touch-action: pan-y !important;` on the shield, WebKit and Blink gesture recognizers treat vertical drag inputs as native scroll pan gestures for the chat history container, while click, `pointerup`, and `touchend` events are captured and stopped via `stopImmediatePropagation()` in capture phase.

#### Tier 4: React Fiber-Safe Modal Watchdog & CSS Suppression
- **Clean Modal Dismissal**: Avoids destructive DOM surgery (e.g. `node.remove()`), which fatally corrupts React's virtual fiber reconciler tree (`NotFoundError: Failed to execute 'removeChild' on 'Node'`). Instead, when modal video players trigger outside of Stories, synthetic click events are dispatched directly to the official React-registered back buttons (`svg[aria-label="Back"]`, `div[role="button"][aria-label="Close"]`), allowing React to cleanly unmount its own component state.
- **Hardware Decoder Suppression**: Injects high-specificity stylesheet rules forcing `.minigram-reel-card video` and `div[role="region"] a[href*="/reel/"] video` to `display: none !important; opacity: 0 !important; height: 0 !important; width: 0 !important; pointer-events: none !important;`, stripping hardware video rendering layers from the compositor.

### 2. Platform-Specific Native Implementations

#### iOS (SwiftUI + WebKit)
- **Injection Lifecycle**: Custom JavaScript and CSS injection at `WKUserScriptInjectionTime.atDocumentStart` (`forMainFrameOnly: false`), ensuring zero flash of unstyled feed content (FOUC).
- **Navigation Guard**: `WKNavigationDelegate.decidePolicyForNavigationAction` intercepts and cancels top-level navigation intents attempting to route to `/reel/`, `/explore/`, or `/p/`.
- **Remote Inspection**: Conditionally exposes `WKWebView.isInspectable = true` on iOS 16.4+ / iOS 17+ to facilitate real-time Safari Web Inspector diagnostics over USB.
- **Safe Area Conformance**: Anchored natively below the dynamic island and notch via SwiftUI safe area insets.

#### Android (Kotlin + Android WebView)
- **Lifecycle Evaluation & Idempotency**: Evaluated across `onPageStarted`, `onPageCommitVisible`, and `onPageFinished`. Multi-evaluation idempotency flags (`__minigram_media_patched__`, `__minigram_fetch_patched__`, `__minigram_observer_installed__`, `__minigram_routing_guarded__`) guarantee that prototype hooks and mutation listeners are never redundantly chained.
- **Scroll Conflict Resolution**: Custom `swipeRefreshLayout.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }` prevents parent refresh gestures from hijacking upward scroll gestures inside chat threads.
- **Cookie & Storage Synchronization**: Integrates `CookieManager.getInstance().flush()` in `onPause()` to guarantee session tokens persist across operating system process trims.
- **Blink Hardware Acceleration**: Hardware acceleration enabled via `View.LAYER_TYPE_HARDWARE` with scrollbar chrome stripped (`isVerticalScrollBarEnabled = false`, `isHorizontalScrollBarEnabled = false`).

---

## Technical Use Cases

- **Deterministic High-Signal Communication**: Dedicated messaging workstation retaining 100% of Instagram DM features (media attachments, voice memos, reactions) and peer Stories, with zero algorithmic feed intrusion.
- **Data & Radio Conservation**: Prevents continuous background media streaming, pre-buffering of vertical videos, and infinite post feed pagination, significantly lowering cellular bandwidth and radio power consumption.
- **Hardware Thermal & Battery Optimization**: Eliminates timeline virtualization churn, infinite scroll polling loops, and WebKit/Blink GPU video composition layers.
- **Zero-Trust Ephemeral Authentication**: Sandboxed natively inside Apple `WKWebsiteDataStore` and Android `CookieManager`. No reverse proxy, no third-party credential ingestion, and no intermediate token relay.

---

## CI/CD Pipeline Architecture

MiniGram features a fully automated cross-platform continuous integration pipeline hosted on GitHub Actions (`.github/workflows/build.yml`):

- **iOS Build**:
  - **Runner**: `macos-15` (Apple Silicon / arm64)
  - **Toolset**: Xcode 16.4 command line tools + `xcodegen`
  - **Artifact**: Unsigned, code-signing-stripped `.ipa` packaged for direct sideloading via Sideloadly / AltStore / TrollStore without requiring an active Apple Developer Program membership.
- **Android Build**:
  - **Runner**: `ubuntu-latest`
  - **Toolset**: Temurin OpenJDK 17 + Gradle 8.14
  - **Artifact**: Signed release `.apk` ready for direct installation via ADB or package installer.

---

## Building Locally

### iOS

```bash
# Generate Xcode project from project.yml specification
brew install xcodegen
xcodegen generate

# Compile unsigned archive and bundle IPA
xcodebuild -project MiniGram.xcodeproj \
  -scheme MiniGram \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/MiniGram.xcarchive \
  archive CODE_SIGNING_ALLOWED=NO CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO

mkdir -p Payload
cp -r build/MiniGram.xcarchive/Products/Applications/MiniGram.app Payload/
zip -r MiniGram.ipa Payload
```

### Android

```bash
cd android
./gradlew assembleRelease
# Output located at android/app/build/outputs/apk/release/app-release.apk
```

---

## License

MIT License. See [LICENSE](LICENSE) for details.
