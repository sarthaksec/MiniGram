import SwiftUI
import WebKit

struct InstagramWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()

        // 1. INJECTED CSS: Hides feed posts, reels tab, and explore tab
        let cssRules = """
        /* Hide all feed posts (Photos, Videos, Carousels) */
        article {
            display: none !important;
        }

        /* Hide the Reels navigation icon & links */
        a[href*="/reels/"], 
        a[aria-label*="Reels"], 
        svg[aria-label*="Reels"],
        div[role="tablist"] a[href*="/reels"] {
            display: none !important;
        }

        /* Hide the Explore / Search navigation icon & links */
        a[href*="/explore/"], 
        a[aria-label*="Search and explore"], 
        svg[aria-label*="Search and explore"],
        div[role="tablist"] a[href*="/explore"] {
            display: none !important;
        }

        /* Hide infinite scroll loading spinners */
        div[data-visualcompletion="loading-state"] {
            display: none !important;
        }

        /* Hide suggested accounts / follow banners below stories */
        main section > div > div > div:has(a[href*="/explore/people/"]) {
            display: none !important;
        }
        """

        let styleInjectionScript = """
        (function() {
            function injectCSS() {
                var style = document.getElementById('minigram-injected-styles');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'minigram-injected-styles';
                    style.type = 'text/css';
                    style.innerHTML = `\(cssRules)`;
                    (document.head || document.documentElement).appendChild(style);
                }
            }

            // Inject immediately
            injectCSS();

            // Re-check after DOM is loaded to ensure style permanence
            document.addEventListener('DOMContentLoaded', injectCSS);

            // 2. SPA ROUTE INTERCEPTION: Monkey-patch history pushState / replaceState
            const blockedPaths = ['/reels', '/explore'];
            function isPathBlocked(path) {
                if (!path) return false;
                const lower = String(path).toLowerCase();
                return blockedPaths.some(bp => lower.includes(bp));
            }

            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                if (isPathBlocked(url)) {
                    console.warn('[MiniGram] Blocked client navigation to:', url);
                    return originalPushState.apply(this, [state, title, '/']);
                }
                return originalPushState.apply(this, arguments);
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(state, title, url) {
                if (isPathBlocked(url)) {
                    console.warn('[MiniGram] Blocked client navigation to:', url);
                    return originalReplaceState.apply(this, [state, title, '/']);
                }
                return originalReplaceState.apply(this, arguments);
            };
        })();
        """

        let userScript = WKUserScript(
            source: styleInjectionScript,
            injectionTime: .atDocumentStart, // Injects before DOM renders (No flash of feed!)
            forMainFrameOnly: false
        )
        contentController.addUserScript(userScript)

        configuration.userContentController = contentController
        // Persistent cookie store so login state is saved
        configuration.websiteDataStore = WKWebsiteDataStore.default()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.bounces = true
        
        // Native pull-to-refresh
        let refreshControl = UIRefreshControl()
        refreshControl.addTarget(context.coordinator, action: #selector(Coordinator.handleRefresh(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = refreshControl
        context.coordinator.webView = webView

        // Load root Instagram (displays Stories carousel cleanly at the top)
        if let url = URL(string: "https://www.instagram.com/") {
            let request = URLRequest(url: url)
            webView.load(request)
        }

        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    class Coordinator: NSObject, WKNavigationDelegate {
        weak var webView: WKWebView?

        @objc func handleRefresh(_ sender: UIRefreshControl) {
            webView?.reload()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                sender.endRefreshing()
            }
        }

        // 3. HARD NAVIGATION GUARD: Intercepts standard link clicks and network requests
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }

            let path = url.path.lowercased()

            // If any navigation targets Reels or Explore, cancel and bounce back home
            if path.contains("/reels") || path.contains("/explore") {
                print("[MiniGram] Blocked navigation attempt to: \(url.absoluteString)")
                decisionHandler(.cancel)
                if let homeURL = URL(string: "https://www.instagram.com/") {
                    webView.load(URLRequest(url: homeURL))
                }
                return
            }

            decisionHandler(.allow)
        }
    }
}
