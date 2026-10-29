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

            // 2. State Helpers
            function isLoggedIn() {
                return document.cookie.includes('ds_user_id') ||
                       document.querySelector('a[href*="/direct/inbox/"]') !== null ||
                       document.querySelector('div[role="tablist"]') !== null;
            }

            function isSpecificReel() {
                // Matches /reel/CODE/ or /reels/CODE/ or /p/CODE/ (shared via DM)
                return /^\\/(reel|reels|p)\\/[A-Za-z0-9_-]+\\/?/i.test(window.location.pathname);
            }

            function isAlgorithmicReels() {
                var p = window.location.pathname.toLowerCase();
                return p === '/reels' || p === '/reels/';
            }

            // 3. Intercept timeline feed queries to eliminate lag & CPU thrashing
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

            // 4. Inject Dedicated Back Button for Single Shared Reels
            function ensureReelBackButton() {
                if (isSpecificReel()) {
                    var existingBtn = document.getElementById('minigram-reel-back');
                    if (!existingBtn) {
                        var btn = document.createElement('button');
                        btn.id = 'minigram-reel-back';
                        btn.innerHTML = '‹ Back to Chat';
                        btn.onclick = function() {
                            window.history.back();
                        };
                        document.body.appendChild(btn);
                    }
                } else {
                    var btn = document.getElementById('minigram-reel-back');
                    if (btn) btn.remove();
                }
            }

            // 5. Dynamic CSS Management
            function updateStyles() {
                var loggedIn = isLoggedIn();
                var onSingleReel = isSpecificReel();

                // Core Layout & Notch/Dynamic Island Styling
                var layoutStyle = document.getElementById('minigram-layout-style');
                if (!layoutStyle) {
                    layoutStyle = document.createElement('style');
                    layoutStyle.id = 'minigram-layout-style';
                    layoutStyle.type = 'text/css';
                    layoutStyle.innerHTML = `
                        html, body {
                            background-color: #000000 !important;
                            color: #FFFFFF !important;
                            overflow-x: hidden !important;
                            -webkit-tap-highlight-color: transparent;
                        }

                        /* Safe Area padding so content never gets stuck behind Dynamic Island */
                        body {
                            padding-top: max(env(safe-area-inset-top), 50px) !important;
                            padding-bottom: max(env(safe-area-inset-bottom), 20px) !important;
                            box-sizing: border-box !important;
                        }

                        /* DM / Header Navigation Clearance & High-Touch Hit Area */
                        header, 
                        div[role="banner"],
                        div[aria-label="Direct message thread"] > div:first-child {
                            padding-top: max(env(safe-area-inset-top), 44px) !important;
                            background-color: rgba(0, 0, 0, 0.95) !important;
                        }

                        /* Make Back Arrow in DMs prominent, padded, and easily tappable */
                        svg[aria-label*="Back"], 
                        svg[aria-label*="Chevron left"],
                        a[href*="/direct/"]:has(svg),
                        button:has(svg[aria-label*="Back"]) {
                            padding: 10px !important;
                            margin-top: 4px !important;
                            min-width: 44px !important;
                            min-height: 44px !important;
                            touch-action: manipulation !important;
                            cursor: pointer !important;
                        }

                        /* Single Reel Dedicated Floating Back Button */
                        #minigram-reel-back {
                            position: fixed !important;
                            top: max(env(safe-area-inset-top), 52px) !important;
                            left: 16px !important;
                            z-index: 9999999 !important;
                            background: rgba(20, 20, 20, 0.85) !important;
                            -webkit-backdrop-filter: blur(16px) !important;
                            backdrop-filter: blur(16px) !important;
                            color: #ffffff !important;
                            border: 1px solid rgba(255, 255, 255, 0.25) !important;
                            border-radius: 20px !important;
                            padding: 8px 16px !important;
                            font-size: 14px !important;
                            font-weight: 600 !important;
                            display: flex !important;
                            align-items: center !important;
                            box-shadow: 0 4px 12px rgba(0,0,0,0.5) !important;
                            cursor: pointer !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(layoutStyle);
                }

                // Dedicated Single Reel App-Like View (No infinite scroll to next reel)
                var reelSingleStyle = document.getElementById('minigram-single-reel-style');
                if (onSingleReel) {
                    if (!reelSingleStyle) {
                        reelSingleStyle = document.createElement('style');
                        reelSingleStyle.id = 'minigram-single-reel-style';
                        reelSingleStyle.type = 'text/css';
                        reelSingleStyle.innerHTML = `
                            /* Lock vertical scroll so you can't doomscroll to next reel */
                            body, html {
                                overflow-y: hidden !important;
                                touch-action: pan-x !important;
                            }
                            /* Hide bottom nav bar on reel player for immersive view */
                            div[role="tablist"], nav[role="navigation"]:last-of-type {
                                display: none !important;
                            }
                        `;
                        (document.head || document.documentElement).appendChild(reelSingleStyle);
                    }
                } else {
                    if (reelSingleStyle) reelSingleStyle.remove();
                }

                ensureReelBackButton();

                // Blocker Filter (When logged in and NOT on a single reel)
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

            // Run immediately
            updateStyles();
            document.addEventListener('DOMContentLoaded', updateStyles);

            // 6. Debounced Mutation Observer to prevent DM typing / scrolling lag
            let debounceTimer = null;
            const observer = new MutationObserver(function() {
                if (debounceTimer) return;
                debounceTimer = setTimeout(function() {
                    updateStyles();
                    debounceTimer = null;
                }, 200);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // 7. Route Navigation Guard
            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                if (isLoggedIn() && isAlgorithmicReels()) {
                    console.warn('[MiniGram] Blocked pushState to algorithmic Reels');
                    return originalPushState.apply(this, [state, title, '/']);
                }
                setTimeout(updateStyles, 50);
                return originalPushState.apply(this, arguments);
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(state, title, url) {
                if (isLoggedIn() && isAlgorithmicReels()) {
                    console.warn('[MiniGram] Blocked replaceState to algorithmic Reels');
                    return originalReplaceState.apply(this, [state, title, '/']);
                }
                setTimeout(updateStyles, 50);
                return originalReplaceState.apply(this, arguments);
            };

            window.addEventListener('popstate', function() {
                setTimeout(updateStyles, 50);
            });
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

            // Allow single Reel / Post links shared in DMs (e.g., /reel/C7xY9z/ or /p/C7xY9z/)
            let isSingleMedia = path.hasPrefix("/reel/") || path.hasPrefix("/p/") || (path.hasPrefix("/reels/") && path.count > 8)

            // Only block the generic algorithmic Reels tab (/reels or /reels/) and Explore
            if (!isSingleMedia && (path == "/reels" || path == "/reels/")) || path.contains("/explore") {
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
