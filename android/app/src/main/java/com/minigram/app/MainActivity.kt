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

            function isReelView() {
                var isReelUrl = /^\\/(reel|reels|p)\\/[A-Za-z0-9_-]+/i.test(window.location.pathname);
                var hasVideoModal = document.querySelector('div[role="dialog"] video') !== null;
                var hasFeedVideo = document.querySelector('video') !== null && window.location.pathname.includes('/direct/');
                return isReelUrl || hasVideoModal || hasFeedVideo;
            }

            function updateStyles() {
                var onReel = isReelView();

                // Notify Native Android layer to intercept touch gestures
                if (window.MiniGramNative && typeof window.MiniGramNative.setReelActive === 'function') {
                    window.MiniGramNative.setReelActive(onReel);
                }

                // 1. Reel Lockdown CSS: Deletes all subsequent reels from DOM & locks scroll
                var reelLockStyle = document.getElementById('minigram-reel-lock-style');
                if (onReel) {
                    if (!reelLockStyle) {
                        reelLockStyle = document.createElement('style');
                        reelLockStyle.id = 'minigram-reel-lock-style';
                        reelLockStyle.type = 'text/css';
                        reelLockStyle.innerHTML = `
                            /* Completely freeze vertical scroll */
                            html, body, main, div[id^="mount"], div[role="dialog"] {
                                overflow-y: hidden !important;
                                touch-action: pan-x !important;
                            }

                            /* Disable snap scroll containers */
                            div[style*="scroll-snap"], div[style*="overflow-y"] {
                                scroll-snap-type: none !important;
                                overflow-y: hidden !important;
                            }

                            /* DELETE all subsequent algorithmic reels from the DOM */
                            div[style*="scroll-snap"] > div:nth-child(n+2),
                            div[role="dialog"] > div > div:nth-child(n+2),
                            main section > div > div:nth-child(n+2):has(video) {
                                display: none !important;
                            }

                            /* Floating Back Button for clean return to DMs */
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

                // 2. Feed Blocker (Active when logged in)
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
                }, 100);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            // 3. SPA Route Interception
            const originalPushState = history.pushState;
            history.pushState = function(state, title, url) {
                var targetUrl = String(url || '');

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

        // Bridge JS to Native to report reel state
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setReelActive(active: Boolean) {
                isReelActive = active
            }
        }, "MiniGramNative")

        // NATIVE TOUCH INTERCEPTION: When a reel is active, drop vertical drag events at the OS level
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
                    // If vertical drag on reel, consume and cancel it completely!
                    if (diffY > 12 && diffY > diffX) {
                        true // Consumed natively! Chromium never sees the swipe!
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
