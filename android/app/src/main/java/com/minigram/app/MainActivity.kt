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
                return /^\/(reel|reels|p)\/[A-Za-z0-9_-]+\/?/i.test(p);
            }

            // Track the specific reel URL when opened from DMs
            let lockedReelPath = isSpecificReel() ? window.location.pathname : null;

            // Touch event suppressor to kill vertical swiping to the next reel
            let touchStartY = 0;
            window.addEventListener('touchstart', function(e) {
                if (isSpecificReel() && e.touches.length > 0) {
                    touchStartY = e.touches[0].clientY;
                }
            }, { passive: true });

            window.addEventListener('touchmove', function(e) {
                if (isSpecificReel() && e.touches.length > 0) {
                    let diffY = Math.abs(e.touches[0].clientY - touchStartY);
                    // If user tries to drag or swipe vertically, kill the event so it won't snap to next reel
                    if (diffY > 8) {
                        e.stopPropagation();
                    }
                }
            }, { passive: false });

            function updateStyles() {
                var onSingleReel = isSpecificReel();

                if (onSingleReel && !lockedReelPath) {
                    lockedReelPath = window.location.pathname;
                } else if (!onSingleReel) {
                    lockedReelPath = null;
                }

                // 1. Single Reel Lock: Freeze vertical scrolling completely
                var reelLockStyle = document.getElementById('minigram-reel-lock-style');
                if (onSingleReel) {
                    if (!reelLockStyle) {
                        reelLockStyle = document.createElement('style');
                        reelLockStyle.id = 'minigram-reel-lock-style';
                        reelLockStyle.type = 'text/css';
                        reelLockStyle.innerHTML = `
                            /* Completely lock scroll & touch drag on single reel view */
                            html, body, main, div[id^="mount"] {
                                overflow-y: hidden !important;
                                height: 100vh !important;
                                max-height: 100vh !important;
                                touch-action: pan-x !important;
                            }
                            /* Disable CSS scroll-snap lists */
                            div[style*="scroll-snap"], div[style*="overflow-y"] {
                                scroll-snap-type: none !important;
                                overflow-y: hidden !important;
                            }
                            /* Hide bottom tablist on reel player for focus */
                            div[role="tablist"], nav[role="navigation"]:last-of-type {
                                display: none !important;
                            }
                            /* Floating Back Button */
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

                    // Add Floating Back button if missing
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

                // 2. Feed Blocker (Active when logged in and not on login page)
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

                // If already on a shared reel, DO NOT allow pushState to a DIFFERENT next reel!
                if (lockedReelPath && isSpecificReel(targetUrl) && targetUrl !== lockedReelPath) {
                    console.warn('[MiniGram] Blocked advancing to next reel:', targetUrl);
                    return;
                }

                // If generic algorithmic reels or explore, bounce home
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

                // Only block generic algorithmic Reels tab (/reels or /reels/) and Explore
                // Allow specific shared reels like /reel/CODE/ or /p/CODE/
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
