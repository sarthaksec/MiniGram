import SwiftUI
import WebKit

struct InstagramWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()

        let script = """
        (function() {
            function isAuthPage() {
                var p = window.location.pathname;
                return p.includes('/accounts/login') ||
                       p.includes('/accounts/emailsignup') ||
                       p.includes('/accounts/onetap') ||
                       document.querySelector('form#loginForm') !== null ||
                       document.querySelector('input[name="username"]') !== null;
            }

            function isLoggedIn() {
                return document.cookie.includes('ds_user_id') && !isAuthPage();
            }

            function isSpecificReel(path) {
                var p = path || window.location.pathname;
                return /^\\/(reel|reels|p)\\/[A-Za-z0-9_-]+\\/?/i.test(p);
            }

            // Track active reel path to prevent advancing to next reels
            let lockedReelPath = isSpecificReel() ? window.location.pathname : null;

            // Kill vertical swipe gestures on single reel view
            let touchStartY = 0;
            window.addEventListener('touchstart', function(e) {
                if (isSpecificReel() && e.touches.length > 0) {
                    touchStartY = e.touches[0].clientY;
                }
            }, { passive: true });

            window.addEventListener('touchmove', function(e) {
                if (isSpecificReel() && e.touches.length > 0) {
                    let diffY = Math.abs(e.touches[0].clientY - touchStartY);
                    if (diffY > 8) {
                        e.stopPropagation();
                    }
                }
            }, { passive: false });

            // Intercept timeline feed queries to eliminate CPU lag
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

            function updateStyles() {
                var onSingleReel = isSpecificReel();

                if (onSingleReel && !lockedReelPath) {
                    lockedReelPath = window.location.pathname;
                } else if (!onSingleReel) {
                    lockedReelPath = null;
                }

                // 1. Single Reel Lock: Freeze vertical scrolling
                var reelLockStyle = document.getElementById('minigram-reel-lock-style');
                if (onSingleReel) {
                    if (!reelLockStyle) {
                        reelLockStyle = document.createElement('style');
                        reelLockStyle.id = 'minigram-reel-lock-style';
                        reelLockStyle.type = 'text/css';
                        reelLockStyle.innerHTML = `
                            html, body, main, div[id^="mount"] {
                                overflow-y: hidden !important;
                                height: 100vh !important;
                                max-height: 100vh !important;
                                touch-action: pan-x !important;
                            }
                            div[style*="scroll-snap"], div[style*="overflow-y"] {
                                scroll-snap-type: none !important;
                                overflow-y: hidden !important;
                            }
                            div[role="tablist"], nav[role="navigation"]:last-of-type {
                                display: none !important;
                            }
                            #minigram-reel-back {
                                position: fixed !important;
                                top: 16px !important;
                                left: 16px !important;
                                z-index: 9999999 !important;
                                background: rgba(18, 18, 18, 0.85) !important;
                                backdrop-filter: blur(12px) !important;
                                color: #FFFFFF !important;
                                border: 1px solid rgba(255, 255, 255, 0.25) !important;
                                border-radius: 20px !important;
                                padding: 8px 16px !important;
                                font-size: 14px !important;
                                font-weight: 600 !important;
                                box-shadow: 0 4px 12px rgba(0,0,0,0.6) !important;
                                cursor: pointer !important;
                            }
                        `;
                        (document.head || document.documentElement).appendChild(reelLockStyle);
                    }

                    var backBtn = document.getElementById('minigram-reel-back');
                    if (!backBtn) {
                        backBtn = document.createElement('button');
                        backBtn.id = 'minigram-reel-back';
                        backBtn.innerHTML = '‹ Back to Chat';
                        backBtn.onclick = function() {
                            window.history.back();
                        };
                        document.body.appendChild(backBtn);
                    }
                } else {
                    if (reelLockStyle) reelLockStyle.remove();
                    var backBtn = document.getElementById('minigram-reel-back');
                    if (backBtn) backBtn.remove();
                }

                // 2. Base & Blocker Styles
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

                var filterStyle = document.getElementById('minigram-filter-style');
                if (!isLoggedIn()) {
                    if (filterStyle) filterStyle.remove();
                    return;
                }

                if (!filterStyle) {
                    filterStyle = document.createElement('style');
                    filterStyle.id = 'minigram-filter-style';
                    filterStyle.type = 'text/css';
                    filterStyle.innerHTML = `
                        main article {
                            display: none !important;
                        }
                        a[href*="/reels/"], a[aria-label*="Reels"], svg[aria-label*="Reels"],
                        div[role="tablist"] a[href*="/reels"] {
                            display: none !important;
                        }
                        a[href*="/explore/"], a[aria-label*="Search and explore"], svg[aria-label*="Search and explore"],
                        div[role="tablist"] a[href*="/explore"] {
                            display: none !important;
                        }
                        main section > div:not([role="menu"]) > div > div:has(a[href*="/explore/people/"]) {
                            display: none !important;
                        }
                        div[data-visualcompletion="loading-state"] {
                            display: none !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(filterStyle);
                }
            }

            updateStyles();
            document.addEventListener('DOMContentLoaded', updateStyles);

            let debounceTimer = null;
            const observer = new MutationObserver(function() {
                if (debounceTimer) return;
                debounceTimer = setTimeout(function() {
                    updateStyles();
                    debounceTimer = null;
                }, 150);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // 3. Route Blocker with Next-Reel Lockdown
            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                var targetUrl = String(url || '');

                if (lockedReelPath && isSpecificReel(targetUrl) && targetUrl !== lockedReelPath) {
                    console.warn('[MiniGram] Blocked advancing to next reel:', targetUrl);
                    return;
                }

                if (isLoggedIn() && (targetUrl === '/reels' || targetUrl === '/reels/' || targetUrl.includes('/explore'))) {
                    return originalPushState.apply(this, [state, title, '/']);
                }

                var res = originalPushState.apply(this, arguments);
                setTimeout(updateStyles, 50);
                return res;
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(state, title, url) {
                var targetUrl = String(url || '');

                if (lockedReelPath && isSpecificReel(targetUrl) && targetUrl !== lockedReelPath) {
                    console.warn('[MiniGram] Blocked replacing state with next reel:', targetUrl);
                    return;
                }

                if (isLoggedIn() && (targetUrl === '/reels' || targetUrl === '/reels/' || targetUrl.includes('/explore'))) {
                    return originalReplaceState.apply(this, [state, title, '/']);
                }

                var res = originalReplaceState.apply(this, arguments);
                setTimeout(updateStyles, 50);
                return res;
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

            // Allow single shared media
            let isSingleMedia = path.hasPrefix("/reel/") || path.hasPrefix("/p/") || (path.hasPrefix("/reels/") && path.count > 8)

            if (!isSingleMedia && (path == "/reels" || path == "/reels/")) || path.contains("/explore") {
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
