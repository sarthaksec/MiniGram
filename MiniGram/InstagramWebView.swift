import SwiftUI
import WebKit

struct InstagramWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()

        let script = """
        (function() {
            // Helper: Check if logged in
            function isLoggedIn() {
                return document.cookie.includes('ds_user_id') ||
                       document.querySelector('a[href*="/direct/inbox/"]') !== null ||
                       document.querySelector('div[role="tablist"]') !== null;
            }

            // 1. Intercept timeline feed network calls to eliminate infinite-scroll lag
            const originalFetch = window.fetch;
            window.fetch = async function(...args) {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
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

            // 2. Simple, clean styling
            function updateStyles() {
                var loggedIn = isLoggedIn();

                // Base Dark Theme (Below the notch, natural layout)
                var baseStyle = document.getElementById('minigram-base-style');
                if (!baseStyle) {
                    baseStyle = document.createElement('style');
                    baseStyle.id = 'minigram-base-style';
                    baseStyle.type = 'text/css';
                    baseStyle.innerHTML = `
                        html, body {
                            background-color: #000000 !important;
                            color: #FFFFFF !important;
                            overflow-x: hidden !important;
                            -webkit-tap-highlight-color: transparent;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(baseStyle);
                }

                // Blocker Filter: Only active when logged in
                var filterStyle = document.getElementById('minigram-filter-style');
                if (!loggedIn) {
                    if (filterStyle) filterStyle.remove();
                    return;
                }

                if (!filterStyle) {
                    filterStyle = document.createElement('style');
                    filterStyle.id = 'minigram-filter-style';
                    filterStyle.type = 'text/css';
                    filterStyle.innerHTML = `
                        /* Hide post feed articles on home screen */
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

                        /* Hide Suggested follow cards */
                        main section > div:not([role="menu"]) > div > div:has(a[href*="/explore/people/"]) {
                            display: none !important;
                        }

                        /* Hide bottom loading spinners */
                        div[data-visualcompletion="loading-state"] {
                            display: none !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(filterStyle);
                }
            }

            updateStyles();
            document.addEventListener('DOMContentLoaded', updateStyles);

            // Debounced observer to prevent typing / scrolling lag in DMs
            let debounceTimer = null;
            const observer = new MutationObserver(function() {
                if (debounceTimer) return;
                debounceTimer = setTimeout(function() {
                    updateStyles();
                    debounceTimer = null;
                }, 200);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // 3. Hard Client-Side Route Blocker
            const blockedPaths = ['/reels', '/explore'];
            function checkBlocked(path) {
                if (!isLoggedIn() || !path) return false;
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
            source: script,
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

            // Block Reels & Explore
            if path.contains("/reels") || path.contains("/explore") {
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
