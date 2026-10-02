package com.thndang.adshield

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream

class ProtectedBrowserActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var statusText: TextView

    private var adBlocklist: DomainBlocklist = DomainBlocklist.empty()
    private var popupBlocklist: DomainBlocklist = DomainBlocklist.empty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 40)

        loadFilters()
        buildUi()

        val initial = intent.getStringExtra(EXTRA_URL)
            ?: "https://animevietsub.nl"
        addressBar.setText(initial)
        openAddress()
    }

    private fun loadFilters() {
        adBlocklist = DomainBlocklist.load(
            this,
            "blocklist.txt",
            FilterUpdater.MAIN_REMOTE_FILE
        )
        popupBlocklist = DomainBlocklist.load(
            this,
            "popup_redirect_blocklist.txt",
            FilterUpdater.POPUP_REMOTE_FILE
        )
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 249, 252))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }

        toolbar.addView(Button(this).apply {
            text = "‹"
            textSize = 22f
            isAllCaps = false
            setOnClickListener {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        addressBar = EditText(this).apply {
            hint = "Nhập địa chỉ web"
            setSingleLine(true)
            textSize = 14f
            setOnEditorActionListener { _, _, _ ->
                openAddress()
                true
            }
        }
        toolbar.addView(
            addressBar,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        )

        toolbar.addView(Button(this).apply {
            text = "Đi"
            isAllCaps = false
            setOnClickListener { openAddress() }
        })

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        statusText = TextView(this).apply {
            text = "🛡 Lọc request + quảng cáo trong trang"
            textSize = 12f
            setTextColor(Color.rgb(3, 152, 85))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.rgb(236, 253, 243))
        }
        root.addView(statusText)

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode =
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false

            addJavascriptInterface(
                ShieldBridge(),
                "AdShield"
            )

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (shouldBlock(url)) {
                        return blockedResponse()
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return false
                    if (shouldBlock(url)) {
                        runOnUiThread {
                            Toast.makeText(
                                this@ProtectedBrowserActivity,
                                "Đã chặn chuyển hướng: " +
                                    (url.host ?: url.toString()),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)
                    addressBar.setText(url ?: "")
                    injectContentFilters()
                }
            }
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun openAddress() {
        var url = addressBar.text.toString().trim()
        if (url.isEmpty()) return

        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {
            url = "https://" + url
        }

        webView.loadUrl(url)
    }

    private fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false

        if (DomainRules.isAllowed(this, host)) {
            return false
        }

        if (DomainRules.isBlocked(this, host)) {
            return true
        }

        if (adBlocklist.isBlocked(host)) {
            return true
        }

        val popupEnabled = getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        ).getBoolean(
            MainActivity.KEY_REDIRECT_PROTECTION,
            true
        )

        return popupEnabled && popupBlocklist.isBlocked(host)
    }

    private fun blockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "Blocked by AdShield",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun injectContentFilters() {
        val popupEnabled = getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        ).getBoolean(
            MainActivity.KEY_REDIRECT_PROTECTION,
            true
        )

        val js = """
            (function() {
              if (window.__adShieldInstalled) {
                if (window.__adShieldClean) window.__adShieldClean();
                return;
              }
              window.__adShieldInstalled = true;

              const selectors = [
                '.ads-300',
                '.ads_player',
                '.pc-catfixx',
                '.Ads',
                '.Adv',
                '#invideo_wrapper',
                '[class*="banner-ads"]',
                '[class*="banner_ads"]',
                '[class^="ads-"]',
                '[id^="ads-"]',
                '[id*="_ads"]',
                'iframe[src*="doubleclick"]',
                'iframe[src*="googlesyndication"]',
                'iframe[src*="adservice"]',
                'iframe[src*="popads"]',
                'iframe[src*="adsterra"]'
              ];

              const hide = (el) => {
                try {
                  el.style.setProperty('display', 'none', 'important');
                  el.style.setProperty('visibility', 'hidden', 'important');
                  el.style.setProperty('height', '0', 'important');
                  el.style.setProperty('min-height', '0', 'important');
                  el.style.setProperty('margin', '0', 'important');
                  el.style.setProperty('padding', '0', 'important');
                } catch (_) {}
              };

              window.__adShieldClean = function() {
                for (const selector of selectors) {
                  try {
                    document.querySelectorAll(selector).forEach(hide);
                  } catch (_) {}
                }

                try {
                  document.querySelectorAll('a[href]').forEach(a => {
                    const href = a.href || '';
                    if (href && window.AdShield &&
                        window.AdShield.isBlockedUrl(href)) {
                      a.removeAttribute('target');
                      a.onclick = function(e) {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                        return false;
                      };
                    }
                  });
                } catch (_) {}
              };

              const style = document.createElement('style');
              style.id = 'adshield-style';
              style.textContent = selectors.join(',') +
                '{display:none!important;visibility:hidden!important;' +
                'height:0!important;min-height:0!important;' +
                'margin:0!important;padding:0!important;}';
              (document.head || document.documentElement).appendChild(style);

              window.__adShieldClean();

              try {
                new MutationObserver(function() {
                  window.__adShieldClean();
                }).observe(document.documentElement, {
                  childList: true,
                  subtree: true,
                  attributes: true
                });
              } catch (_) {}

              if (${popupEnabled}) {
                window.open = function(url) {
                  try {
                    if (!url || (window.AdShield &&
                        window.AdShield.isBlockedUrl(String(url)))) {
                      return null;
                    }
                  } catch (_) {
                    return null;
                  }
                  return null;
                };

                document.addEventListener('click', function(e) {
                  try {
                    const a = e.target && e.target.closest
                      ? e.target.closest('a[href]')
                      : null;
                    if (!a) return;

                    const href = a.href || '';
                    if (window.AdShield &&
                        window.AdShield.isBlockedUrl(href)) {
                      e.preventDefault();
                      e.stopPropagation();
                      e.stopImmediatePropagation();
                      return false;
                    }

                    if (a.target === '_blank') {
                      a.removeAttribute('target');
                    }
                  } catch (_) {}
                }, true);
              }

              try {
                const host = location.hostname.toLowerCase();
                if (host.includes('animevietsub')) {
                  const animeSelectors = [
                    '.ads-300',
                    '.ads_player',
                    '.pc-catfixx',
                    '.Ads',
                    '.Adv',
                    '#invideo_wrapper'
                  ];

                  animeSelectors.forEach(s => {
                    document.querySelectorAll(s).forEach(hide);
                  });

                  try {
                    if ('PopupManager' in window) {
                      window.PopupManager = {
                        open: function(){},
                        show: function(){},
                        init: function(){}
                      };
                    }
                  } catch (_) {}
                }
              } catch (_) {}
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    inner class ShieldBridge {
        @JavascriptInterface
        fun isBlockedUrl(rawUrl: String): Boolean {
            return try {
                shouldBlock(Uri.parse(rawUrl))
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
