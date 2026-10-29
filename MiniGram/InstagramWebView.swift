import SwiftUI
import WebKit

struct InstagramWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()

        // Comprehensive script to fix:
        // 1. Never break Login/Auth pages
        // 2. Prevent infinite scroll CPU thrashing
        // 3. Cleanly hide Feed, Reels, Explore while keeping Stories & DMs intact
        let injectedScript = """
        (function() {
            // Helper: Check if currently on an authentication/login page
            function isAuthPage() {
                var p = window.location.pathname;
                return p.includes('/accounts/login') ||
                       p.includes('/accounts/emailsignup') ||
                       p.includes('/accounts/onetap') ||
                       document.querySelector('form#loginForm') !== null ||
                       document.querySelector('input[name="username"]') !== null;
            }

            // 1. Intercept network fetch for timeline feed to prevent infinite scroll freeze
            const originalFetch = window.fetch;
            window.fetch = async function(...args) {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                // Block timeline feed network calls so Instagram stops looping
                if (url.includes('/api/v1/feed/timeline/') || url.includes('PolarisFeedQuery')) {
                    return new Response(JSON.stringify({
                        data: { xdt_api__v1__feed__timeline: { edges: [] } },
                        status: 'ok'
                    }), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' }
                    });
                }
                return originalFetch.apply(this, args);
            };

            // 2. Inject CSS rules carefully
            function applyStyles() {
                var existing = document.getElementById('minigram-styles');
                
                // If on login/auth page, NEVER hide elements
                if (isAuthPage()) {
                    if (existing) existing.remove();
                    return;
                }

                if (!existing) {
                    var style = document.createElement('style');
                    style.id = 'minigram-styles';
                    style.type = 'text/css';
                    style.innerHTML = `
                        /* Hide post feed articles only on main content */
                        main article {
                            display: none !important;
                        }

                        /* Hide Reels navigation tab & icons */
                        a[href*="/reels/"], 
                        a[aria-label*="Reels"], 
                        svg[aria-label*="Reels"],
                        div[role="tablist"] a[href*="/reels"] {
                            display: none !important;
                        }

                        /* Hide Explore / Search navigation tab & icons */
                        a[href*="/explore/"], 
                        a[aria-label*="Search and explore"], 
                        svg[aria-label*="Search and explore"],
                        div[role="tablist"] a[href*="/explore"] {
                            display: none !important;
                        }

                        /* Hide Suggested accounts carousel under stories */
                        main section > div:not([role="menu"]) > div > div:has(a[href*="/explore/people/"]) {
                            display: none !important;
                        }

                        /* Hide loading spinners at bottom */
                        div[data-visualcompletion="loading-state"] {
                            display: none !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                }
            }

            // Run immediately
            applyStyles();

            // Observe page changes (Instagram is a SPA)
            const observer = new MutationObserver(function() {
                applyStyles();
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // 3. SPA Route Navigation Blocker
            const blockedPaths = ['/reels', '/explore'];
            function checkBlocked(path) {
                if (!path) return false;
                const lower = String(path).toLowerCase();
                return blockedPaths.some(bp => lower.includes(bp));
            }

            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                if (checkBlocked(url)) {
                    console.warn('[MiniGram] Blocked pushState to:', url);
                    return originalPushState.apply(this, [state, title, '/']);
                }
                return originalPushState.apply(this, arguments);
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(state, title, url) {
                if (checkBlocked(url)) {
                    console.warn('[MiniGram] Blocked replaceState to:', url);
                    return originalReplaceState.apply(this, [state, title, '/']);
                }
                return originalReplaceState.apply(this, arguments);
            };
        })();
        """

        let userScript = WKUserScript(
            source: injectedScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        )
        contentController.addUserScript(userScript)

        configuration.userContentController = contentController
        configuration.websiteDataStore = WKWebsiteDataStore.default()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.bounces = true
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black

        // Pull to refresh
        let refreshControl = UIRefreshControl()
        refreshControl.tintColor = .white
        refreshControl.addTarget(context.coordinator, action: #selector(Coordinator.handleRefresh(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = refreshControl
        context.coordinator.webView = webView

        if let url = URL(string: "https://www.instagram.com/") {
            webView.load(URLRequest(url: url))
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

            // Hard barrier against Reels and Explore routes
            if path.contains("/reels") || path.contains("/explore") {
                print("[MiniGram] Blocked link navigation to: \(url.absoluteString)")
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
