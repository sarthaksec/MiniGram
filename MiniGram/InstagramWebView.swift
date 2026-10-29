import SwiftUI
import WebKit

struct InstagramWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()

        let script = """
        (function() {
            // 1. Force modern viewport-fit=cover so env(safe-area-inset-*) works
            function setupViewport() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    (document.head || document.documentElement).appendChild(meta);
                }
                meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';
            }
            setupViewport();

            // 2. Reliable Check: Is the user logged in?
            function isLoggedIn() {
                // If ds_user_id exists in cookies, or bottom tablist/DM icon exists in DOM
                return document.cookie.includes('ds_user_id') ||
                       document.querySelector('a[href*="/direct/inbox/"]') !== null ||
                       document.querySelector('div[role="tablist"]') !== null;
            }

            // 3. Intercept timeline feed network calls to eliminate infinite-scroll lag
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

            // 4. Inject styles based on Login State
            function updateStyles() {
                var loggedIn = isLoggedIn();

                // Style for Global Layout & Safe Area (Always active)
                var layoutStyle = document.getElementById('minigram-layout-style');
                if (!layoutStyle) {
                    layoutStyle = document.createElement('style');
                    layoutStyle.id = 'minigram-layout-style';
                    layoutStyle.type = 'text/css';
                    layoutStyle.innerHTML = `
                        html, body {
                            background-color: #000000 !important;
                            color: #FFFFFF !important;
                            overscroll-behavior-y: contain;
                            overflow-x: hidden !important;
                            -webkit-tap-highlight-color: transparent;
                        }

                        /* Safe Area padding so content never gets stuck behind Dynamic Island */
                        body {
                            padding-top: max(env(safe-area-inset-top), 54px) !important;
                            padding-bottom: max(env(safe-area-inset-bottom), 24px) !important;
                            box-sizing: border-box !important;
                        }

                        /* If header exists, give it proper safe area top */
                        header, div[role="banner"] {
                            padding-top: max(env(safe-area-inset-top), 48px) !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(layoutStyle);
                }

                // Filter Style (ONLY when logged in - NEVER when on Login Screen)
                var filterStyle = document.getElementById('minigram-filter-style');
                if (!loggedIn) {
                    // User is not logged in: Remove filter rules so login form renders 100% naturally
                    if (filterStyle) filterStyle.remove();
                    return;
                }

                // User is logged in: Apply distraction-free blocker
                if (!filterStyle) {
                    filterStyle = document.createElement('style');
                    filterStyle.id = 'minigram-filter-style';
                    filterStyle.type = 'text/css';
                    filterStyle.innerHTML = `
                        /* Hide post feed articles */
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

            const observer = new MutationObserver(updateStyles);
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // 5. Route Blocker (Only active when logged in)
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
        webView.scrollView.contentInsetAdjustmentBehavior = .never

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

            // If it's a Reels or Explore link, block it
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
