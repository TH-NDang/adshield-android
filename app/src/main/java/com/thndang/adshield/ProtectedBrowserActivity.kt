package com.thndang.adshield

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false

            addJavascriptInterface(
                ShieldBridge(),
                "AdShield"
            )

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    runOnUiThread {
                        Toast.makeText(
                            this@ProtectedBrowserActivity,
                            "Đã chặn cửa sổ quảng cáo mới",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return false
                }
            }

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

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)

                    if (!WebViewFeature.isFeatureSupported(
                            WebViewFeature.DOCUMENT_START_SCRIPT
                        )
                    ) {
                        view?.evaluateJavascript(
                            buildDocumentStartScript(),
                            null
                        )
                    }
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
        installDocumentStartProtection()
    }

    private fun installDocumentStartProtection() {
        if (
            WebViewFeature.isFeatureSupported(
                WebViewFeature.DOCUMENT_START_SCRIPT
            )
        ) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                buildDocumentStartScript(),
                setOf("*")
            )

            statusText.text =
                "🛡 Lọc trước khi trang chạy + lọc quảng cáo trong player"
        } else {
            statusText.text =
                "🛡 Lọc request + fallback DOM filter"
        }
    }

    private fun buildDocumentStartScript(): String {
        val prefs = getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )

        val popupEnabled = prefs.getBoolean(
            MainActivity.KEY_REDIRECT_PROTECTION,
            true
        )

        val overlayEnabled = prefs.getBoolean(
            MainActivity.KEY_OVERLAY_PROTECTION,
            true
        )

        return """
            (function() {
              if (window.__adShieldDocumentStart) return;
              window.__adShieldDocumentStart = true;

              const css = [
                '.ads-300',
                '.ads_player',
                '.pc-catfixx',
                '.mobile-catfixx',
                '.mobile-catfish-top',
                '.Ads',
                '.Adv',
                '#invideo_wrapper',
                '#_preload-ads-1',
                '[class*="banner-ads"]',
                '[class*="banner_ads"]',
                '[class*="floating-ad"]',
                '[class*="float-ad"]',
                '[class*="popup-ad"]',
                '[id*="floating-ad"]',
                '[id*="popup-ad"]'
              ].join(',') +
                '{display:none!important;visibility:hidden!important;' +
                'opacity:0!important;pointer-events:none!important;' +
                'height:0!important;min-height:0!important;' +
                'max-height:0!important;margin:0!important;padding:0!important;}';

              const installStyle = function() {
                try {
                  if (document.getElementById('adshield-document-start-style')) {
                    return true;
                  }
                  const root = document.head || document.documentElement;
                  if (!root) return false;
                  const style = document.createElement('style');
                  style.id = 'adshield-document-start-style';
                  style.textContent = css;
                  root.appendChild(style);
                  return true;
                } catch (_) {
                  return false;
                }
              };

              if (!installStyle()) {
                try {
                  const rootWatcher = new MutationObserver(function(_, obs) {
                    if (installStyle()) obs.disconnect();
                  });
                  rootWatcher.observe(document, {
                    childList: true,
                    subtree: true
                  });
                } catch (_) {}
              }

              if (${popupEnabled}) {
                try {
                  Object.defineProperty(window, 'open', {
                    configurable: true,
                    writable: false,
                    value: function() { return null; }
                  });
                } catch (_) {
                  try {
                    window.open = function() { return null; };
                  } catch (_) {}
                }
              }

              try {
                if (
                  location.hostname &&
                  location.hostname.toLowerCase().includes('animevietsub')
                ) {
                  const popupStub = {
                    open: function(){},
                    show: function(){},
                    init: function(){},
                    create: function(){},
                    trigger: function(){}
                  };

                  try {
                    Object.defineProperty(window, 'PopupManager', {
                      configurable: true,
                      get: function() { return popupStub; },
                      set: function() {}
                    });
                  } catch (_) {}
                }
              } catch (_) {}

              const hideNow = function() {
                try {
                  document.querySelectorAll(
                    '.ads-300,.ads_player,.pc-catfixx,.mobile-catfixx,' +
                    '.mobile-catfish-top,.Ads,.Adv,#invideo_wrapper,' +
                    '#_preload-ads-1'
                  ).forEach(function(el) {
                    el.style.setProperty('display', 'none', 'important');
                    el.style.setProperty('visibility', 'hidden', 'important');
                    el.style.setProperty('pointer-events', 'none', 'important');
                  });
                } catch (_) {}
              };

              try {
                new MutationObserver(hideNow).observe(document, {
                  childList: true,
                  subtree: true
                });
              } catch (_) {}

              if (${overlayEnabled}) {
                window.__adShieldEarlyOverlayEnabled = true;
              }
            })();
        """.trimIndent()
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
        val prefs = getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )

        val popupEnabled = prefs.getBoolean(
            MainActivity.KEY_REDIRECT_PROTECTION,
            true
        )

        val overlayEnabled = prefs.getBoolean(
            MainActivity.KEY_OVERLAY_PROTECTION,
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


              if (${overlayEnabled}) {
                const adWords =
                  /(\\b|[_-])(ad|ads|advert|advertisement|banner|promo|sponsor|popup|popunder|floating|float|sticky[-_]?ad|qc|quangcao)(\\b|[_-])/i;
                const gamblingWords =
                  /(casino|bet|betting|jackpot|debet|yo88|win79|sun88|kubet|jun88|new88|fb88|m88|w88)/i;

                const textOf = (el) => {
                  try {
                    return (
                      (el.id || '') + ' ' +
                      (el.className || '') + ' ' +
                      (el.getAttribute && (el.getAttribute('aria-label') || '')) + ' ' +
                      (el.getAttribute && (el.getAttribute('title') || '')) + ' ' +
                      (el.textContent || '').slice(0, 180)
                    );
                  } catch (_) {
                    return '';
                  }
                };

                const linkedUrls = (el) => {
                  const urls = [];
                  try {
                    if (el.href) urls.push(String(el.href));
                    if (el.src) urls.push(String(el.src));
                    el.querySelectorAll &&
                      el.querySelectorAll('a[href],iframe[src],img[src]').forEach(node => {
                        if (node.href) urls.push(String(node.href));
                        if (node.src) urls.push(String(node.src));
                      });
                  } catch (_) {}
                  return urls.slice(0, 12);
                };

                const hasBlockedUrl = (el) => {
                  try {
                    return linkedUrls(el).some(url =>
                      window.AdShield && window.AdShield.isBlockedUrl(url)
                    );
                  } catch (_) {
                    return false;
                  }
                };

                const likelyAdDimensions = (r) => {
                  const w = Math.round(r.width);
                  const h = Math.round(r.height);
                  const presets = [
                    [320, 50], [320, 100], [300, 250], [336, 280],
                    [728, 90], [970, 90], [970, 250], [160, 600],
                    [300, 600], [468, 60]
                  ];
                  return presets.some(([pw, ph]) =>
                    Math.abs(w - pw) <= 45 && Math.abs(h - ph) <= 45
                  );
                };

                const isLikelyFloatingAd = (el) => {
                  try {
                    if (!el || el === document.body || el === document.documentElement) {
                      return false;
                    }

                    const style = getComputedStyle(el);
                    const pos = style.position;
                    if (pos !== 'fixed' && pos !== 'sticky') return false;
                    if (style.display === 'none' || style.visibility === 'hidden') return false;

                    const r = el.getBoundingClientRect();
                    if (r.width < 90 || r.height < 32) return false;

                    const viewportArea = Math.max(1, innerWidth * innerHeight);
                    const areaRatio = (r.width * r.height) / viewportArea;
                    const z = parseInt(style.zIndex || '0', 10) || 0;
                    const info = textOf(el);
                    const keywordHit = adWords.test(info) || gamblingWords.test(info);
                    const blockedLink = hasBlockedUrl(el);
                    const media = !!(
                      el.matches &&
                      el.matches('a,iframe,img,ins,aside') ||
                      (el.querySelector && el.querySelector('a[href],iframe,img,video'))
                    );

                    const nearEdge =
                      r.top <= 90 ||
                      r.bottom >= innerHeight - 90 ||
                      r.left <= 40 ||
                      r.right >= innerWidth - 40;

                    const hugeOverlay =
                      areaRatio >= 0.16 &&
                      areaRatio <= 0.80 &&
                      z >= 1000 &&
                      media;

                    const adSizedOverlay =
                      likelyAdDimensions(r) &&
                      z >= 50 &&
                      media;

                    const suspiciousFloating =
                      z >= 500 &&
                      nearEdge &&
                      media &&
                      (keywordHit || blockedLink);

                    // Preserve likely navigation, forms and video controls unless
                    // they explicitly point to a blocked/ad-like destination.
                    const hasForm =
                      !!(el.querySelector && el.querySelector('input,textarea,select,form'));
                    const semanticUi =
                      el.matches &&
                      el.matches('header,nav,[role="navigation"],[role="toolbar"]');

                    if ((hasForm || semanticUi) && !blockedLink && !keywordHit) {
                      return false;
                    }

                    return (
                      blockedLink ||
                      keywordHit ||
                      hugeOverlay ||
                      adSizedOverlay ||
                      suspiciousFloating
                    );
                  } catch (_) {
                    return false;
                  }
                };

                const cleanFloatingAds = () => {
                  try {
                    const candidates = document.querySelectorAll(
                      'div,section,aside,a,iframe,img,ins'
                    );

                    let scanned = 0;
                    for (let i = candidates.length - 1; i >= 0 && scanned < 4500; i--, scanned++) {
                      const el = candidates[i];
                      if (isLikelyFloatingAd(el)) {
                        hide(el);
                        el.setAttribute('data-adshield-overlay', 'blocked');
                      }
                    }

                    // AnimeVietSub and similar sites frequently use fixed wrappers
                    // with generic names but betting/casino images or external links.
                    if (location.hostname.toLowerCase().includes('animevietsub')) {
                      document.querySelectorAll(
                        'body > div, body > a, .modal, .popup, [style*="position: fixed"], [style*="position:fixed"]'
                      ).forEach(el => {
                        try {
                          const r = el.getBoundingClientRect();
                          const st = getComputedStyle(el);
                          const info = textOf(el);
                          const suspicious =
                            hasBlockedUrl(el) ||
                            gamblingWords.test(info) ||
                            (
                              st.position === 'fixed' &&
                              (parseInt(st.zIndex || '0', 10) || 0) >= 100 &&
                              r.width >= 120 &&
                              r.height >= 40 &&
                              !!el.querySelector('img,a[href],iframe')
                            );

                          if (suspicious) hide(el);
                        } catch (_) {}
                      });
                    }
                  } catch (_) {}
                };

                window.__adShieldCleanFloating = cleanFloatingAds;
                cleanFloatingAds();

                let overlayTimer = null;
                const scheduleOverlayClean = () => {
                  if (overlayTimer) return;
                  overlayTimer = setTimeout(() => {
                    overlayTimer = null;
                    cleanFloatingAds();
                  }, 120);
                };

                try {
                  new MutationObserver(scheduleOverlayClean)
                    .observe(document.documentElement, {
                      childList: true,
                      subtree: true,
                      attributes: true,
                      attributeFilter: ['style', 'class', 'src', 'href']
                    });
                } catch (_) {}

                setInterval(cleanFloatingAds, 1200);

                document.addEventListener('touchstart', cleanFloatingAds, true);
                document.addEventListener('click', cleanFloatingAds, true);
                window.addEventListener('scroll', scheduleOverlayClean, { passive: true });
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
