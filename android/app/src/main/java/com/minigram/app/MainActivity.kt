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
                       (document.querySelector && (
                           document.querySelector('form#loginForm') !== null ||
                           document.querySelector('input[name="username"]') !== null ||
                           document.querySelector('input[type="password"]') !== null
                       ));
            }

            // =========================================================================
            // 1. NETWORK INTERCEPTION: Kill Reels Pagination & Chaining Queries
            // =========================================================================
            if (!window.__minigram_fetch_patched__) {
                window.__minigram_fetch_patched__ = true;
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
            }

            // =========================================================================
            // 1b. MEDIA INTERCEPTION: Definitively Block Video Playback & Loading in DMs
            // =========================================================================
            if (!window.__minigram_media_patched__ && typeof HTMLMediaElement !== 'undefined') {
                window.__minigram_media_patched__ = true;

                const originalPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    if (this.tagName === 'VIDEO' && !isStories()) {
                        this.pause();
                        this.muted = true;
                        return Promise.reject(new DOMException('Playback blocked by MiniGram', 'NotAllowedError')).catch(function() {});
                    }
                    return originalPlay.apply(this, arguments);
                };

                const originalLoad = HTMLMediaElement.prototype.load;
                HTMLMediaElement.prototype.load = function() {
                    if (this.tagName === 'VIDEO' && !isStories()) {
                        return;
                    }
                    return originalLoad.apply(this, arguments);
                };

                const originalSrcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (originalSrcDesc && originalSrcDesc.set) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                        get: function() {
                            return originalSrcDesc.get.call(this);
                        },
                        set: function(val) {
                            if (this.tagName === 'VIDEO' && !isStories() && (window.location.pathname.toLowerCase().includes('/direct') || (this.closest && this.closest('a[href*="/reel/"], a[href*="/p/"], [data-minigram-card="true"]')))) {
                                return originalSrcDesc.set.call(this, '');
                            }
                            return originalSrcDesc.set.call(this, val);
                        }
                    });
                }
            }

            // =========================================================================
            // 2. DOM SHIELDING: 100% Full-Card Inert Click Shield with Scroll Preservation
            // =========================================================================
            function findReelCard(link) {
                if (!link) return null;

                var article = link.closest ? link.closest('article') : null;
                if (article) {
                    var bubble = article.closest('div[role="button"], div[tabindex="0"], div[data-pressable-container="true"]');
                    return bubble || article;
                }

                var curr = link;
                var candidate = link.parentElement;

                while (curr && curr !== document.body && curr !== document.documentElement) {
                    var role = (curr.getAttribute('role') || '').toLowerCase();
                    if (role === 'row' || role === 'feed' || role === 'list' || role === 'main' || role === 'grid') {
                        break;
                    }

                    var parent = curr.parentElement;
                    if (!parent) break;

                    var pRole = (parent.getAttribute('role') || '').toLowerCase();
                    if (pRole === 'row' || pRole === 'feed' || pRole === 'list' || pRole === 'main') {
                        candidate = curr;
                        break;
                    }

                    // If parent contains reaction/action buttons as siblings of the message bubble
                    if (parent.querySelector('div[aria-label*="React"], button[aria-label*="React"], svg[aria-label*="React"], div[aria-label*="reply"], button[aria-label*="reply"]')) {
                        if (!curr.querySelector('div[aria-label*="React"], button[aria-label*="React"], svg[aria-label*="React"]')) {
                            candidate = curr;
                            break;
                        }
                    }

                    var style = window.getComputedStyle(curr);
                    var br = parseFloat(style.borderRadius) || 0;
                    if (br >= 12 && curr.offsetHeight > 80) {
                        candidate = curr;
                    }

                    curr = parent;
                }

                return candidate;
            }

            function applyReelShields() {
                if (!document.body || isStories() || isAuthPage()) return;

                const mediaLinks = document.querySelectorAll('a[href*="/reel/"], a[href*="/reels/"], a[href*="/p/"]');
                mediaLinks.forEach(function(link) {
                    var card = findReelCard(link);
                    if (!card) card = (link.closest ? link.closest('div[role="button"], div[tabindex="0"], div[data-pressable-container="true"]') : null) || link.parentElement;
                    if (!card || card.dataset.minigramShielded === 'true') return;

                    card.dataset.minigramShielded = 'true';
                    card.dataset.minigramCard = 'true';
                    card.classList.add('minigram-reel-card');

                    var compPos = window.getComputedStyle(card).position;
                    if (compPos === 'static' || !compPos) {
                        card.style.position = 'relative';
                    }

                    // Neutralize all video elements inside this card without destructive DOM removal
                    var videos = card.querySelectorAll('video');
                    videos.forEach(function(v) {
                        v.pause();
                        v.muted = true;
                        v.removeAttribute('autoplay');
                        v.preload = 'none';
                        v.removeAttribute('src');
                        v.style.display = 'none';
                    });

                    var shield = document.createElement('div');
                    shield.className = 'minigram-reel-shield';
                    shield.style.cssText = 'position:absolute!important;inset:0!important;width:100%!important;height:100%!important;z-index:99!important;touch-action:pan-y!important;background:rgba(0,0,0,0.72)!important;border-radius:inherit!important;overflow:hidden!important;display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;cursor:not-allowed!important;backdrop-filter:blur(8px)!important;-webkit-backdrop-filter:blur(8px)!important;box-sizing:border-box!important;pointer-events:auto!important;';

                    shield.innerHTML = '<div style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:12px;text-align:center;pointer-events:none;">' +
                        '<div style="background:rgba(255,255,255,0.12);border-radius:50%;width:40px;height:40px;display:flex;align-items:center;justify-content:center;border:1px solid rgba(255,255,255,0.18);">' +
                            '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
                                '<rect x="2" y="2" width="20" height="20" rx="5" ry="5"></rect>' +
                                '<line x1="16" y1="2" x2="16" y2="22"></line>' +
                                '<line x1="8" y1="2" x2="8" y2="22"></line>' +
                                '<line x1="2" y1="12" x2="22" y2="12"></line>' +
                                '<line x1="2" y1="7" x2="8" y2="7"></line>' +
                                '<line x1="2" y1="17" x2="8" y2="17"></line>' +
                                '<line x1="16" y1="7" x2="22" y2="7"></line>' +
                                '<line x1="16" y1="17" x2="22" y2="17"></line>' +
                            '</svg>' +
                        '</div>' +
                        '<span style="background:rgba(20,20,20,0.92);color:#eee;font-size:12px;font-weight:600;padding:5px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.14);letter-spacing:0.3px;">Reel Blocked</span>' +
                    '</div>';

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
                if (!document.body || isStories() || isAuthPage()) return;

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
                if (!document.documentElement) return;

                if (isAuthPage()) {
                    var filter = document.getElementById('minigram-filter-style');
                    if (filter) filter.remove();
                    return;
                }

                var filterStyle = document.getElementById('minigram-filter-style');
                if (!filterStyle) {
                    var targetParent = document.head || document.documentElement;
                    if (targetParent) {
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

                            /* 6. Definitively prevent video elements inside DM reel cards from rendering or playing */
                            .minigram-reel-card video,
                            [data-minigram-card="true"] video,
                            div[role="region"] a[href*="/reel/"] video,
                            div[role="region"] a[href*="/reels/"] video,
                            div[role="region"] a[href*="/p/"] video {
                                display: none !important;
                                visibility: hidden !important;
                                pointer-events: none !important;
                                opacity: 0 !important;
                                height: 0 !important;
                                width: 0 !important;
                            }

                            /* 7. Ensure shielded reel cards maintain relative positioning and clipping */
                            .minigram-reel-card,
                            [data-minigram-card="true"] {
                                position: relative !important;
                                overflow: hidden !important;
                            }
                        `;
                        targetParent.appendChild(filterStyle);
                    }
                }

                if (!isStories() && window.location.pathname.toLowerCase().includes('/direct')) {
                    var dmVideos = document.querySelectorAll('div[role="region"] video, div[role="feed"] video, .minigram-reel-card video');
                    dmVideos.forEach(function(v) {
                        if (!v.paused) v.pause();
                        v.muted = true;
                        v.preload = 'none';
                    });
                }

                applyReelShields();
                killModalViewer();
            }

            window.__minigram_updateStyles__ = updateStyles;

            updateStyles();
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', updateStyles);
            }

            if (!window.__minigram_observer_installed__) {
                window.__minigram_observer_installed__ = true;

                let debounceTimer = null;
                const observer = new MutationObserver(function() {
                    if (debounceTimer) return;
                    debounceTimer = setTimeout(function() {
                        updateStyles();
                        debounceTimer = null;
                    }, 80);
                });

                function attachObserver() {
                    if (document.documentElement) {
                        observer.observe(document.documentElement, { childList: true, subtree: true });
                    } else {
                        setTimeout(attachObserver, 20);
                    }
                }
                attachObserver();
            }

            // =========================================================================
            // 5. CLIENT-SIDE ROUTING GUARDS
            // =========================================================================
            function isBlocked(url) {
                if (!url) return false;
                var target = String(url).toLowerCase();
                return target.includes('/reel') || target.includes('/explore') || target.includes('/p/');
            }

            if (!window.__minigram_routing_guarded__) {
                window.__minigram_routing_guarded__ = true;

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
            }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
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

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
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
        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            webView.canScrollVertically(-1)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                android.util.Log.d("MiniGramConsole", "${message?.messageLevel()}: ${message?.message()} [${message?.sourceId()}:${message?.lineNumber()}]")
                return true
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        @Suppress("DEPRECATION")
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
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val friendlyName = request.requestHeaders?.get("X-FB-Friendly-Name")
                    ?: request.requestHeaders?.get("x-fb-friendly-name")
                    ?: ""

                // 1. Intercept timeline feed queries
                if (url.contains("/api/v1/feed/timeline/") ||
                    url.contains("PolarisFeedQuery") ||
                    friendlyName.contains("PolarisFeed")
                ) {
                    val emptyJson = "{\"data\":{\"xdt_api__v1__feed__timeline\":{\"edges\":[]}},\"status\":\"ok\"}"
                    return WebResourceResponse(
                        "application/json",
                        "UTF-8",
                        ByteArrayInputStream(emptyJson.toByteArray(StandardCharsets.UTF_8))
                    )
                }

                // 2. Intercept clips / reels pagination and chaining queries
                if (url.contains("/api/v1/clips/") ||
                    url.contains("PolarisClips") ||
                    friendlyName.contains("PolarisClips") ||
                    friendlyName.contains("Clips")
                ) {
                    val emptyClips = "{\"data\":{\"xdt_api__v1__clips\":{\"edges\":[],\"page_info\":{\"has_next_page\":false,\"end_cursor\":null}}},\"status\":\"ok\"}"
                    return WebResourceResponse(
                        "application/json",
                        "UTF-8",
                        ByteArrayInputStream(emptyClips.toByteArray(StandardCharsets.UTF_8))
                    )
                }

                // 3. Intercept explore queries
                if (url.contains("/api/v1/explore/") ||
                    url.contains("PolarisExplore") ||
                    friendlyName.contains("PolarisExplore") ||
                    friendlyName.contains("Explore")
                ) {
                    val emptyExplore = "{\"data\":{\"items\":[],\"more_available\":false},\"status\":\"ok\"}"
                    return WebResourceResponse(
                        "application/json",
                        "UTF-8",
                        ByteArrayInputStream(emptyExplore.toByteArray(StandardCharsets.UTF_8))
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

                // Total Reels, Posts & Explore Block: If any link attempts navigation to Reel, Post, or Explore, cancel it
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
            }

            private fun injectFilter(view: WebView?) {
                view?.evaluateJavascript(injectionScript, null)
            }
        }
    }
}
