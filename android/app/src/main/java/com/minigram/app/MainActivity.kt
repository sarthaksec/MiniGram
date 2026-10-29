package com.minigram.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private val injectionScript = """
        (function() {
            function isLoggedIn() {
                return document.cookie.includes('ds_user_id') ||
                       document.querySelector('a[href*="/direct/inbox/"]') !== null ||
                       document.querySelector('div[role="tablist"]') !== null;
            }

            function updateStyles() {
                var loggedIn = isLoggedIn();

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

            updateStyles();
            document.addEventListener('DOMContentLoaded', updateStyles);

            let debounceTimer = null;
            const observer = new MutationObserver(function() {
                if (debounceTimer) return;
                debounceTimer = setTimeout(function() {
                    updateStyles();
                    debounceTimer = null;
                }, 200);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });

            const blockedPaths = ['/reels', '/explore'];
            function checkBlocked(path) {
                if (!isLoggedIn() || !path) return false;
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
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

            // Native Network Interception: Kill timeline feed queries at the C++/Java network stack
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

            // Hard URL Interception: Block generic Reels and Explore navigation
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val path = (uri.path ?: "").lowercase()

                if (path.contains("/reels") || path.contains("/explore")) {
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
