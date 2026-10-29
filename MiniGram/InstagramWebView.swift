import SwiftUI
import WebKit

struct InstagramWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()

        let script = """
        (function() {
            function isAuthPage() {
                var p = window.location.pathname.toLowerCase();
                return p.includes('/accounts/login') ||
                       p.includes('/accounts/emailsignup') ||
                       document.querySelector('form#loginForm') !== null ||
                       document.querySelector('input[name="username"]') !== null ||
                       document.querySelector('input[type="password"]') !== null;
            }

            function isStories() {
                return window.location.pathname.toLowerCase().startsWith('/stories');
            }

            function updateStyles() {
                if (isAuthPage()) {
                    var filter = document.getElementById('minigram-filter-style');
                    if (filter) filter.remove();
                    return;
                }

                var filterStyle = document.getElementById('minigram-filter-style');
                if (!filterStyle) {
                    filterStyle = document.createElement('style');
                    filterStyle.id = 'minigram-filter-style';
                    filterStyle.type = 'text/css';
                    filterStyle.innerHTML = `
                        /* 1. Feed posts completely hidden on home screen */
                        article, main article {
                            display: none !important;
                        }

                        /* 2. Reels tab completely hidden in navigation bar */
                        a[href*="/reels/"], 
                        a[href*="/reel/"],
                        a[aria-label*="Reels"], 
                        svg[aria-label*="Reels"],
                        div[role="tablist"] a[href*="/reels"] {
                            display: none !important;
                        }

                        /* 3. Explore tab completely hidden in navigation bar */
                        a[href*="/explore/"], 
                        a[aria-label*="Search and explore"], 
                        svg[aria-label*="Search and explore"],
                        div[role="tablist"] a[href*="/explore"] {
                            display: none !important;
                        }

                        /* 4. Hide suggestions carousel & banners */
                        main section > div:not([role="menu"]) > div > div:has(a[href*="/explore/people/"]) {
                            display: none !important;
                        }

                        /* 5. Hide loading spinners */
                        div[data-visualcompletion="loading-state"] {
                            display: none !important;
                        }

                        /* 6. In DMs: mute shared Reel links visually */
                        a[href*="/reel/"], 
                        a[href*="/reels/"] {
                            opacity: 0.3 !important;
                            filter: grayscale(1) !important;
                            cursor: not-allowed !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(filterStyle);
                }

                neutralizeReels();
            }

            function neutralizeReels() {
                if (isStories()) return;

                // Find all links to reels
                var links = document.querySelectorAll('a[href*="/reel/"], a[href*="/reels/"]');
                links.forEach(function(link) {
                    link.style.pointerEvents = 'none';
                    link.style.cursor = 'not-allowed';
                    link.style.opacity = '0.3';
                    link.style.filter = 'grayscale(100%)';

                    // Lock the clickable message card container
                    var card = link.closest('div[role="button"], div[tabindex="0"]');
                    if (card && card.getAttribute('data-minigram-locked') !== 'true') {
                        card.setAttribute('data-minigram-locked', 'true');
                        card.style.pointerEvents = 'none';
                        card.style.cursor = 'not-allowed';
                        card.style.opacity = '0.3';
                        card.style.filter = 'grayscale(100%)';
                    }
                });

                // Remove any reel modal dialogs mounted outside Stories
                var dialogs = document.querySelectorAll('div[role="dialog"]');
                dialogs.forEach(function(d) {
                    if (d.querySelector('video') || d.querySelector('a[href*="/reel/"]')) {
                        console.warn('[MiniGram] Removed Reel modal dialog');
                        d.remove();
                    }
                });

                // Pause any non-story videos
                var videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    v.pause();
                    v.removeAttribute('src');
                });
            }

            // Capture-phase click blocker: stops clicks on any reel element before React sees it
            function isReelClick(target) {
                if (!target) return false;
                if (target.closest && target.closest('a[href*="/reel/"], a[href*="/reels/"]')) return true;

                var curr = target;
                for (var i = 0; i < 5 && curr && curr !== document.body && curr !== document.documentElement; i++) {
                    if (curr.getAttribute && curr.getAttribute('data-minigram-locked') === 'true') return true;
                    var aria = (curr.getAttribute && curr.getAttribute('aria-label')) || '';
                    if (aria && aria.toLowerCase().includes('reel')) return true;
                    if (curr.tagName === 'VIDEO') return true;
                    curr = curr.parentElement;
                }
                return false;
            }

            document.addEventListener('click', function(e) {
                if (isStories()) return;
                if (isReelClick(e.target)) {
                    console.warn('[MiniGram] Captured and blocked click on DM Reel');
                    e.preventDefault();
                    e.stopPropagation();
                    e.stopImmediatePropagation();
                    return false;
                }
            }, true);

            updateStyles();
            document.addEventListener('DOMContentLoaded', updateStyles);

            let debounceTimer = null;
            const observer = new MutationObserver(function() {
                if (debounceTimer) return;
                debounceTimer = setTimeout(function() {
                    updateStyles();
                    debounceTimer = null;
                }, 100);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // Client-Side Blocker for Reels & Explore pushState/replaceState
            function isBlocked(url) {
                if (!url) return false;
                var target = String(url).toLowerCase();
                return target.includes('/reel') || target.includes('/explore');
            }

            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                if (isBlocked(url)) {
                    console.warn('[MiniGram] Blocked pushState to reel/explore:', url);
                    return;
                }
                var res = originalPushState.apply(this, arguments);
                setTimeout(updateStyles, 50);
                return res;
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(state, title, url) {
                if (isBlocked(url)) {
                    console.warn('[MiniGram] Blocked replaceState to reel/explore:', url);
                    return;
                }
                var res = originalReplaceState.apply(this, arguments);
                setTimeout(updateStyles, 50);
                return res;
            };

            window.addEventListener('popstate', function() {
                setTimeout(updateStyles, 50);
            });

            // Watchdog: If the browser URL somehow lands on a reel outside stories, redirect back to chat
            setInterval(function() {
                if (!isStories()) {
                    var path = window.location.pathname.toLowerCase();
                    if (path.includes('/reel/') || path.includes('/reels/')) {
                        console.warn('[MiniGram] Reel URL detected in watchdog, redirecting to /direct/inbox/');
                        window.location.replace('/direct/inbox/');
                    }
                }
            }, 500);
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

            if path.contains("/reel") || path.contains("/explore") {
                decisionHandler(.cancel)
                return
            }

            decisionHandler(.allow)
        }
    }
}
