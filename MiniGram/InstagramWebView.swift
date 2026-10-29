import SwiftUI
import WebKit

struct InstagramWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()

        let modernResponsiveScript = """
        (function() {
            // 1. Force modern viewport-fit=cover so WebKit unlocks env(safe-area-inset-*)
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

            // Helper: Auth page check
            function isAuthPage() {
                var p = window.location.pathname;
                return p.includes('/accounts/login') ||
                       p.includes('/accounts/emailsignup') ||
                       p.includes('/accounts/onetap') ||
                       document.querySelector('form#loginForm') !== null ||
                       document.querySelector('input[name="username"]') !== null;
            }

            // 2. Intercept timeline feed network calls to eliminate infinite-scroll lag
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

            // 3. Modern Dynamic Island & Notch fluid styles
            function applyResponsiveStyles() {
                var existing = document.getElementById('minigram-responsive-styles');
                if (isAuthPage()) {
                    if (existing) existing.remove();
                    return;
                }

                if (!existing) {
                    var style = document.createElement('style');
                    style.id = 'minigram-responsive-styles';
                    style.type = 'text/css';
                    style.innerHTML = `
                        /* Global OLED display optimization */
                        html, body {
                            background-color: #000000 !important;
                            overscroll-behavior-y: contain;
                            overflow-x: hidden !important;
                            -webkit-tap-highlight-color: transparent;
                        }

                        /* Fluid top header under Dynamic Island (iPhone 14 Pro, 15, 16 series) */
                        header, div[role="banner"] {
                            padding-top: max(env(safe-area-inset-top), 48px) !important;
                            background-color: rgba(0, 0, 0, 0.85) !important;
                            -webkit-backdrop-filter: blur(20px) !important;
                            backdrop-filter: blur(20px) !important;
                        }

                        /* Bottom tab bar with Home Indicator clearance */
                        div[role="tablist"], nav[role="navigation"]:last-of-type {
                            padding-bottom: max(env(safe-area-inset-bottom), 20px) !important;
                            background-color: rgba(0, 0, 0, 0.9) !important;
                            -webkit-backdrop-filter: blur(20px) !important;
                            backdrop-filter: blur(20px) !important;
                        }

                        /* Hide post feed articles */
                        main article {
                            display: none !important;
                        }

                        /* Hide Reels navigation icon & links */
                        a[href*="/reels/"], 
                        a[aria-label*="Reels"], 
                        svg[aria-label*="Reels"],
                        div[role="tablist"] a[href*="/reels"] {
                            display: none !important;
                        }

                        /* Hide Explore / Search navigation icon & links */
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
                    (document.head || document.documentElement).appendChild(style);
                }
            }

            applyResponsiveStyles();
            document.addEventListener('DOMContentLoaded', applyResponsiveStyles);

            const observer = new MutationObserver(applyResponsiveStyles);
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // 4. Hard client-side route blocker
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
            source: modernResponsiveScript,
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
        
        // Let CSS handle safe-area padding dynamically via env(safe-area-inset-*)
        webView.scrollView.contentInsetAdjustmentBehavior = .never

        // Native pull-to-refresh
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
