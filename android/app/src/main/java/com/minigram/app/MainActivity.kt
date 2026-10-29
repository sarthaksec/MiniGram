package com.minigram.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private val injectionScript = """
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
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        webView = findViewById(R.id.webView)

        setupSwipeRefresh()
        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl("https://www.instagram.com/")
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#E1306C"), Color.WHITE)
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Color.parseColor("#121212"))
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
            webView.postDelayed({
                swipeRefreshLayout.isRefreshing = false
            }, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                android.util.Log.d("MiniGramConsole", "${message?.messageLevel()}: ${message?.message()} [${message?.sourceId()}:${message?.lineNumber()}]")
                return true
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.contains("/api/v1/feed/timeline/") || url.contains("PolarisFeedQuery")) {
                    val emptyJson = "{\"data\":{\"xdt_api__v1__feed__timeline\":{\"edges\":[]}},\"status\":\"ok\"}"
                    return WebResourceResponse(
                        "application/json",
                        "UTF-8",
                        ByteArrayInputStream(emptyJson.toByteArray(StandardCharsets.UTF_8))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                val path = (uri.path ?: "").lowercase()

                if (scheme != "http" && scheme != "https") {
                    return true
                }

                // Total Reels & Explore Block: If any link tries to go to Reels or Explore, cancel it!
                if (path.contains("/reel") || path.contains("/explore")) {
                    return true
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectFilter(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectFilter(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectFilter(view)
                swipeRefreshLayout.isRefreshing = false
                view?.evaluateJavascript("""
                    (function() {
                        function getElInfo(sel) {
                            var el = document.querySelector(sel);
                            if (!el) return sel + ': null';
                            var r = el.getBoundingClientRect();
                            var cs = window.getComputedStyle(el);
                            return sel + ': rect=(' + Math.round(r.x) + ',' + Math.round(r.y) + ',' + Math.round(r.width) + ',' + Math.round(r.height) + ') disp=' + cs.display + ' vis=' + cs.visibility + ' op=' + cs.opacity;
                        }
                        console.log('[MiniGramDebug] ' + getElInfo('header') + ' | ' + getElInfo('nav') + ' | ' + getElInfo('div[role="tablist"]') + ' | ' + getElInfo('svg[aria-label="Instagram"]') + ' | ' + getElInfo('article'));
                    })();
                """.trimIndent(), null)
            }

            private fun injectFilter(view: WebView?) {
                view?.evaluateJavascript(injectionScript, null)
            }
        }
    }
}
