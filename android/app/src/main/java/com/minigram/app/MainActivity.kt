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
            'use strict';

            function isStories() {
                return window.location.pathname.toLowerCase().startsWith('/stories');
            }

            function isAuthPage() {
                var p = window.location.pathname.toLowerCase();
                return p.includes('/accounts/login') ||
                       p.includes('/accounts/emailsignup') ||
                       document.querySelector('form#loginForm') !== null ||
                       document.querySelector('input[name="username"]') !== null ||
                       document.querySelector('input[type="password"]') !== null;
            }

            // =========================================================================
            // 1. NETWORK INTERCEPTION: Kill Reels Pagination & Chaining Queries
            // =========================================================================
            const originalFetch = window.fetch;
            window.fetch = async function(...args) {
                const url = String(args[0]?.url || args[0] || '');
                const options = args[1] || {};
                const headers = options.headers || {};
                const friendlyName = headers['X-FB-Friendly-Name'] || headers['x-fb-friendly-name'] || '';

                const isClipsFeed = url.includes('/api/v1/clips/') ||
                                    url.includes('PolarisClips') ||
                                    friendlyName.includes('PolarisClips') ||
                                    (url.includes('/graphql/query') && options.body && String(options.body).includes('xdt_api__v1__clips'));

                if (isClipsFeed) {
                    console.warn('[MiniGram Network] Blocked Reels pagination query:', friendlyName || url);
                    const emptyPayload = JSON.stringify({
                        data: {
                            xdt_api__v1__clips: {
                                edges: [],
                                page_info: { has_next_page: false, end_cursor: null }
                            }
                        },
                        status: 'ok'
                    });
                    return new Response(emptyPayload, {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' }
                    });
                }
                return originalFetch.apply(this, args);
            };

            // =========================================================================
            // 2. DOM SHIELDING: Physical Inert Click Sink with Scroll Preservation
            // =========================================================================
            function applyReelShields() {
                if (isStories() || isAuthPage()) return;

                const mediaLinks = document.querySelectorAll('a[href*="/reel/"], a[href*="/reels/"], a[href*="/p/"]');
                mediaLinks.forEach(function(link) {
                    var card = link.closest('div[role="button"], div[tabindex="0"], div[data-pressable-container="true"]') || link.parentElement;
                    if (!card || card.dataset.minigramShielded === 'true') return;

                    card.dataset.minigramShielded = 'true';
                    card.style.position = 'relative';

                    var shield = document.createElement('div');
                    shield.className = 'minigram-reel-shield';
                    shield.style.cssText = 'position:absolute!important;inset:0!important;z-index:99!important;touch-action:pan-y!important;background:rgba(0,0,0,0.55)!important;border-radius:12px!important;display:flex!important;align-items:center!important;justify-content:center!important;cursor:not-allowed!important;backdrop-filter:blur(3px)!important;';
                    shield.innerHTML = '<span style="background:rgba(20,20,20,0.92);color:#aaa;font-size:11px;font-weight:600;padding:5px 10px;border-radius:8px;pointer-events:none;border:1px solid rgba(255,255,255,0.12);letter-spacing:0.3px;">Reel Blocked</span>';

                    var swallow = function(e) {
                        e.stopPropagation();
                        e.stopImmediatePropagation();
                        if (e.cancelable && e.type !== 'touchend') e.preventDefault();
                    };

                    shield.addEventListener('click', swallow, true);
                    shield.addEventListener('pointerup', swallow, true);
                    shield.addEventListener('touchend', swallow, true);

                    card.appendChild(shield);
                });
            }

            // =========================================================================
            // 3. FULLSCREEN VIEWER CLEAN DISMISSAL (React-Safe, No DOM Surgery)
            // =========================================================================
            function killModalViewer() {
                if (isStories() || isAuthPage()) return;

                var overlays = document.querySelectorAll('div[role="dialog"], div[style*="position: fixed"]');
                overlays.forEach(function(overlay) {
                    var hasReel = overlay.querySelector('video, a[href*="/reel/"], svg[aria-label="Audio"], svg[aria-label="Remix"]');
                    if (!hasReel) return;

                    if (overlay.querySelector('[aria-label*="Story"], [aria-label*="story"]')) return;

                    console.warn('[MiniGram] Dismissing Reel viewer cleanly.');

                    var vids = overlay.querySelectorAll('video');
                    vids.forEach(function(v) { v.pause(); v.src = ''; });

                    var closeBtn = overlay.querySelector(
                        'div[role="button"][aria-label="Back"], ' +
                        'div[role="button"][aria-label="Close"], ' +
                        'button[aria-label="Back"], ' +
                        'button[aria-label="Close"], ' +
                        'svg[aria-label="Back"], ' +
                        'svg[aria-label="Close"]'
                    );

                    if (closeBtn) {
                        var target = closeBtn.closest('div[role="button"], button') || closeBtn;
                        target.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                    } else {
                        window.history.back();
                    }
                });
            }

            // =========================================================================
            // 4. CSS INJECTION & MUTATION OBSERVER ENGINE
            // =========================================================================
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
                    `;
                    (document.head || document.documentElement).appendChild(filterStyle);
                }

                applyReelShields();
                killModalViewer();
            }

            updateStyles();
            document.addEventListener('DOMContentLoaded', updateStyles);

            let debounceTimer = null;
            const observer = new MutationObserver(function() {
                if (debounceTimer) return;
                debounceTimer = setTimeout(function() {
                    updateStyles();
                    debounceTimer = null;
                }, 80);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // =========================================================================
            // 5. CLIENT-SIDE ROUTING GUARDS
            // =========================================================================
            function isBlocked(url) {
                if (!url) return false;
                var target = String(url).toLowerCase();
                return target.includes('/reel') || target.includes('/explore') || target.includes('/p/');
            }

            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                if (isBlocked(url)) {
                    console.warn('[MiniGram] Blocked pushState to reel/post/explore:', url);
                    return;
                }
                var res = originalPushState.apply(this, arguments);
                setTimeout(updateStyles, 50);
                return res;
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(state, title, url) {
                if (isBlocked(url)) {
                    console.warn('[MiniGram] Blocked replaceState to reel/post/explore:', url);
                    return;
                }
                var res = originalReplaceState.apply(this, arguments);
                setTimeout(updateStyles, 50);
                return res;
            };

            window.addEventListener('popstate', function() {
                setTimeout(updateStyles, 50);
            });

            // Watchdog: If URL somehow lands on a reel or post outside stories, return to chat
            setInterval(function() {
                if (!isStories()) {
                    var path = window.location.pathname.toLowerCase();
                    if (path.includes('/reel/') || path.includes('/reels/') || path.includes('/p/')) {
                        console.warn('[MiniGram] Reel/Post URL detected in watchdog, redirecting to /direct/inbox/');
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
                if (url.contains("/api/v1/clips/") || url.contains("PolarisClips")) {
                    val emptyClips = "{\"data\":{\"xdt_api__v1__clips\":{\"edges\":[],\"page_info\":{\"has_next_page\":false}}},\"status\":\"ok\"}"
                    return WebResourceResponse(
                        "application/json",
                        "UTF-8",
                        ByteArrayInputStream(emptyClips.toByteArray(StandardCharsets.UTF_8))
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

                // Total Reels, Posts & Explore Block: If any link tries to go to Reels, Posts or Explore, cancel it!
                if (path.contains("/reel") || path.contains("/explore") || path.contains("/p/")) {
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
