package com.minigram.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
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
    @Volatile private var isReelActive = false

    private val injectionScript = """
        (function() {
            // Strictly check for login/signup forms
            function isAuthPage() {
                var p = window.location.pathname.toLowerCase();
                return p.includes('/accounts/login') ||
                       p.includes('/accounts/emailsignup') ||
                       document.querySelector('form#loginForm') !== null ||
                       document.querySelector('input[name="username"]') !== null ||
                       document.querySelector('input[name="password"]') !== null;
            }

            function isReelView() {
                var p = window.location.pathname.toLowerCase();
                var isReelUrl = p.startsWith('/reel/') || p.startsWith('/reels/') || p.startsWith('/p/');
                var hasVideoModal = document.querySelector('div[role="dialog"] video') !== null;
                var hasFeedVideo = document.querySelector('video') !== null && p.includes('/direct/');
                return isReelUrl || hasVideoModal || hasFeedVideo;
            }

            function updateStyles() {
                if (isAuthPage()) {
                    var filter = document.getElementById('minigram-filter-style');
                    if (filter) filter.remove();
                    var reelLock = document.getElementById('minigram-reel-lock-style');
                    if (reelLock) reelLock.remove();
                    return;
                }

                var onReel = isReelView();

                // Report reel state to Native Kotlin
                if (window.MiniGramNative && typeof window.MiniGramNative.setReelActive === 'function') {
                    window.MiniGramNative.setReelActive(onReel);
                }

                // 1. Reel Lockdown: Freeze scroll & hide subsequent videos
                var reelLockStyle = document.getElementById('minigram-reel-lock-style');
                if (onReel) {
                    if (!reelLockStyle) {
                        reelLockStyle = document.createElement('style');
                        reelLockStyle.id = 'minigram-reel-lock-style';
                        reelLockStyle.type = 'text/css';
                        reelLockStyle.innerHTML = `
                            html, body, main, div[id^="mount"], div[role="dialog"] {
                                overflow-y: hidden !important;
                                touch-action: pan-x !important;
                            }
                            div[style*="scroll-snap"], div[style*="overflow-y"] {
                                scroll-snap-type: none !important;
                                overflow-y: hidden !important;
                            }
                            /* Delete all subsequent algorithmic reels from the DOM */
                            div[style*="scroll-snap"] > div:nth-child(n+2),
                            div[role="dialog"] > div > div:nth-child(n+2),
                            main section > div > div:nth-child(n+2):has(video) {
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

                // 2. Feed Blocker: Hides Home Posts, Reels Icon, Explore Icon
                var filterStyle = document.getElementById('minigram-filter-style');
                if (!filterStyle) {
                    filterStyle = document.createElement('style');
                    filterStyle.id = 'minigram-filter-style';
                    filterStyle.type = 'text/css';
                    filterStyle.innerHTML = `
                        /* Completely hide feed post cards */
                        article, main article {
                            display: none !important;
                        }

                        /* Completely hide Reels tab in bottom bar */
                        a[href*="/reels/"], 
                        a[aria-label*="Reels"], 
                        svg[aria-label*="Reels"],
                        div[role="tablist"] a[href*="/reels"] {
                            display: none !important;
                        }

                        /* Completely hide Explore tab in bottom bar */
                        a[href*="/explore/"], 
                        a[aria-label*="Search and explore"], 
                        svg[aria-label*="Search and explore"],
                        div[role="tablist"] a[href*="/explore"] {
                            display: none !important;
                        }

                        /* Hide suggested accounts banners */
                        main section > div:not([role="menu"]) > div > div:has(a[href*="/explore/people/"]) {
                            display: none !important;
                        }

                        /* Hide loading spinners */
                        div[data-visualcompletion="loading-state"] {
                            display: none !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(filterStyle);
                }
            }

            // Execute immediately and on DOM changes
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

            // 3. SPA Route Interception
            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                var targetUrl = String(url || '').toLowerCase();

                if (targetUrl === '/reels' || targetUrl === '/reels/' || targetUrl.includes('/explore')) {
                    return originalPushState.apply(this, [state, title, '/']);
                }

                var res = originalPushState.apply(this, arguments);
                setTimeout(updateStyles, 50);
                return res;
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(state, title, url) {
                var targetUrl = String(url || '').toLowerCase();

                if (targetUrl === '/reels' || targetUrl === '/reels/' || targetUrl.includes('/explore')) {
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
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WebView.setWebContentsDebuggingEnabled(true)

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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setReelActive(active: Boolean) {
                isReelActive = active
            }
        }, "MiniGramNative")

        var touchStartY = 0f
        var touchStartX = 0f

        webView.setOnTouchListener { _, event ->
            if (!isReelActive) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.y
                    touchStartX = event.x
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffY = Math.abs(event.y - touchStartY)
                    val diffX = Math.abs(event.x - touchStartX)
                    if (diffY > 12 && diffY > diffX) {
                        true // Drop vertical swipe on reel natively
                    } else {
                        false
                    }
                }
                else -> false
            }
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

                val isSingleMedia = path.startsWith("/reel/") || path.startsWith("/p/") || (path.startsWith("/reels/") && path.length > 8)

                if (!isSingleMedia && (path == "/reels" || path == "/reels/")) {
                    view?.loadUrl("https://www.instagram.com/")
                    return true
                }

                if (path.contains("/explore")) {
                    view?.loadUrl("https://www.instagram.com/")
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
