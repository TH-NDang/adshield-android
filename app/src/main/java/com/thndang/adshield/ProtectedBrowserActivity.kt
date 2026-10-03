package com.thndang.adshield

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File

class ProtectedBrowserActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var statusText: TextView

    private var adBlocklist: DomainBlocklist = DomainBlocklist.empty()
    private var popupBlocklist: DomainBlocklist = DomainBlocklist.empty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 40)

        installCrashReporter()

        try {
            loadLightweightFilters()
            buildUi()
        } catch (error: Throwable) {
            recordBrowserCrash(error)
            showBrowserInitError(error)
            return
        }

        val initial = intent.getStringExtra(EXTRA_URL)
            ?: "https://animevietsub.nl"

        addressBar.setText(initial)
        openAddress()
    }

    private fun installCrashReporter() {
        val previous =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                recordBrowserCrash(error)
            } catch (_: Throwable) {
            }

            previous?.uncaughtException(thread, error)
        }
    }

    private fun recordBrowserCrash(error: Throwable) {
        try {
            val logText = buildString {
                appendLine("AdShield protected browser crash")
                appendLine("Time: " + System.currentTimeMillis())
                appendLine(
                    "Device: " +
                        android.os.Build.MANUFACTURER +
                        " " +
                        android.os.Build.MODEL
                )
                appendLine(
                    "Android: " +
                        android.os.Build.VERSION.RELEASE
                )
                appendLine()
                appendLine(Log.getStackTraceString(error))
            }

            File(
                filesDir,
                BROWSER_CRASH_FILE
            ).writeText(logText)
        } catch (_: Throwable) {
        }
    }

    private fun showBrowserInitError(error: Throwable) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(24),
                dp(24),
                dp(24),
                dp(24)
            )
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "Không thể khởi tạo trình duyệt bảo vệ"
            textSize = 20f
            setTextColor(Color.rgb(180, 35, 24))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text =
                "AdShield đã lưu log lỗi.\n\n" +
                    error.javaClass.simpleName +
                    ": " +
                    (error.message ?: "không có thông báo")
            textSize = 14f
            setTextColor(Color.rgb(52, 64, 84))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
        })

        root.addView(Button(this).apply {
            text = "Quay lại"
            isAllCaps = false
            setOnClickListener { finish() }
        })

        setContentView(root)
    }

    private fun loadLightweightFilters() {
        adBlocklist = DomainBlocklist.load(
            this,
            "blocklist.txt"
        )

        popupBlocklist = DomainBlocklist.load(
            this,
            "popup_redirect_blocklist.txt"
        )
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                Color.rgb(247, 249, 252)
            )
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8)
            )
            setBackgroundColor(Color.WHITE)
        }

        toolbar.addView(Button(this).apply {
            text = "‹"
            textSize = 22f
            isAllCaps = false

            setOnClickListener {
                if (
                    ::webView.isInitialized &&
                    webView.canGoBack()
                ) {
                    webView.goBack()
                } else {
                    finish()
                }
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
            setOnClickListener {
                openAddress()
            }
        })

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        statusText = TextView(this).apply {
            text =
                "🛡 Lọc theo sự kiện · không quét toàn trang"
            textSize = 12f
            setTextColor(Color.rgb(3, 152, 85))
            setPadding(
                dp(12),
                dp(6),
                dp(12),
                dp(6)
            )
            setBackgroundColor(
                Color.rgb(236, 253, 243)
            )
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
            settings.javaScriptCanOpenWindowsAutomatically =
                false

            addJavascriptInterface(
                ShieldBridge(),
                "AdShield"
            )

            webChromeClient =
                object : WebChromeClient() {
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

            webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url =
                            request?.url
                                ?: return null

                        return if (shouldBlock(url)) {
                            blockedResponse()
                        } else {
                            null
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url =
                            request?.url
                                ?: return false

                        if (!shouldBlock(url)) {
                            return false
                        }

                        runOnUiThread {
                            Toast.makeText(
                                this@ProtectedBrowserActivity,
                                "Đã chặn chuyển hướng: " +
                                    (
                                        url.host
                                            ?: url.toString()
                                    ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        return true
                    }

                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        super.onPageStarted(
                            view,
                            url,
                            favicon
                        )

                        if (
                            !WebViewFeature
                                .isFeatureSupported(
                                    WebViewFeature
                                        .DOCUMENT_START_SCRIPT
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
                        super.onPageFinished(
                            view,
                            url
                        )

                        addressBar.setText(
                            url ?: ""
                        )
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
        try {
            if (
                WebViewFeature.isFeatureSupported(
                    WebViewFeature.DOCUMENT_START_SCRIPT
                )
            ) {
                WebViewCompat
                    .addDocumentStartJavaScript(
                        webView,
                        buildDocumentStartScript(),
                        setOf("*")
                    )

                statusText.text =
                    "🛡 Lọc từ document-start · mọi iframe"
            } else {
                statusText.text =
                    "🛡 Chế độ tương thích WebView"
            }
        } catch (error: Throwable) {
            recordBrowserCrash(error)

            statusText.text =
                "🛡 Chế độ tương thích WebView"
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
              if (window.__adShieldV8) return;
              window.__adShieldV8 = true;

              const POPUP_ENABLED = ${popupEnabled};
              const OVERLAY_ENABLED = ${overlayEnabled};

              const BASE_SELECTORS = [
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
                '[id*="popup-ad"]',
                '[class*="pause-ad"]',
                '[class*="pause_ad"]',
                '[id*="pause-ad"]',
                '[id*="pause_ad"]',
                '[class*="video-ad"]',
                '[id*="video-ad"]'
              ];

              const HIDE_STYLE =
                'display:none!important;' +
                'visibility:hidden!important;' +
                'opacity:0!important;' +
                'pointer-events:none!important;' +
                'height:0!important;' +
                'min-height:0!important;' +
                'max-height:0!important;' +
                'margin:0!important;' +
                'padding:0!important;';

              const normalizeText = (value) => {
                try {
                  return String(value || '')
                    .toLowerCase()
                    .normalize('NFD')
                    .replace(/[\\u0300-\\u036f]/g, '')
                    .replace(/đ/g, 'd')
                    .replace(/\\s+/g, ' ')
                    .trim();
                } catch (_) {
                  return String(value || '')
                    .toLowerCase()
                    .trim();
                }
              };

              const hide = (el) => {
                if (!el || !el.style) return;

                try {
                  el.style.cssText += ';' + HIDE_STYLE;
                  el.setAttribute(
                    'data-adshield-hidden',
                    '1'
                  );
                } catch (_) {}
              };

              const installStyle = () => {
                try {
                  if (
                    document.getElementById(
                      'adshield-v8-style'
                    )
                  ) {
                    return true;
                  }

                  const root =
                    document.head ||
                    document.documentElement;

                  if (!root) return false;

                  const style =
                    document.createElement('style');

                  style.id =
                    'adshield-v8-style';

                  style.textContent =
                    BASE_SELECTORS.join(',') +
                    '{' +
                    HIDE_STYLE +
                    '}';

                  root.appendChild(style);
                  return true;
                } catch (_) {
                  return false;
                }
              };

              if (!installStyle()) {
                try {
                  const rootObserver =
                    new MutationObserver(
                      (_, observer) => {
                        if (installStyle()) {
                          observer.disconnect();
                        }
                      }
                    );

                  rootObserver.observe(
                    document,
                    {
                      childList: true,
                      subtree: true
                    }
                  );
                } catch (_) {}
              }

              if (POPUP_ENABLED) {
                try {
                  Object.defineProperty(
                    window,
                    'open',
                    {
                      configurable: true,
                      writable: false,
                      value: function() {
                        return null;
                      }
                    }
                  );
                } catch (_) {
                  try {
                    window.open =
                      function() {
                        return null;
                      };
                  } catch (_) {}
                }
              }

              const findPauseContainer =
                (source) => {
                  let node = source;
                  let best = source;

                  for (
                    let depth = 0;
                    depth < 9 && node;
                    depth++
                  ) {
                    try {
                      const text =
                        normalizeText(
                          node.innerText
                        );

                      const hasClose =
                        text.includes(
                          'dong quang cao'
                        );

                      const hasContinue =
                        text.includes(
                          'dong va xem tiep'
                        );

                      const hasAd =
                        text === 'quang cao' ||
                        text.includes(
                          ' quang cao '
                        ) ||
                        hasClose ||
                        hasContinue;

                      if (hasAd) {
                        best = node;
                      }

                      if (
                        hasClose &&
                        hasContinue
                      ) {
                        return node;
                      }
                    } catch (_) {}

                    node = node.parentElement;
                  }

                  return best;
                };

              const removePauseAdFromRoot =
                (root) => {
                  if (!root) return;

                  const elements = [];

                  try {
                    if (
                      root.nodeType === 1
                    ) {
                      elements.push(root);
                    }

                    if (
                      root.querySelectorAll
                    ) {
                      root.querySelectorAll(
                        'button,a,[role="button"],' +
                        'div,span'
                      ).forEach(
                        el => elements.push(el)
                      );
                    }
                  } catch (_) {}

                  let checked = 0;

                  for (const el of elements) {
                    if (checked++ > 300) break;

                    let text = '';

                    try {
                      text =
                        normalizeText(
                          el.innerText
                        );
                    } catch (_) {}

                    if (
                      text !== 'dong quang cao' &&
                      text !== 'dong va xem tiep'
                    ) {
                      continue;
                    }

                    const container =
                      findPauseContainer(el);

                    if (
                      text ===
                        'dong quang cao'
                    ) {
                      try {
                        el.click();
                      } catch (_) {}
                    }

                    hide(container);
                  }

                  try {
                    const rootText =
                      normalizeText(
                        root.innerText
                      );

                    if (
                      rootText.includes(
                        'dong quang cao'
                      ) &&
                      rootText.includes(
                        'dong va xem tiep'
                      )
                    ) {
                      hide(
                        findPauseContainer(
                          root
                        )
                      );
                    }
                  } catch (_) {}
              };

              const removeKnownAdsFromRoot =
                (root) => {
                  if (
                    !root ||
                    root.nodeType !== 1
                  ) {
                    return;
                  }

                  try {
                    for (
                      const selector
                      of BASE_SELECTORS
                    ) {
                      if (
                        root.matches &&
                        root.matches(selector)
                      ) {
                        hide(root);
                      }

                      if (
                        root.querySelectorAll
                      ) {
                        root
                          .querySelectorAll(
                            selector
                          )
                          .forEach(hide);
                      }
                    }
                  } catch (_) {}
              };

              const looksLikeFloatingAd =
                (el) => {
                  if (
                    !OVERLAY_ENABLED ||
                    !el ||
                    el.nodeType !== 1
                  ) {
                    return false;
                  }

                  try {
                    const text =
                      normalizeText(
                        (
                          el.id || ''
                        ) +
                        ' ' +
                        (
                          el.className || ''
                        ) +
                        ' ' +
                        (
                          el.innerText || ''
                        ).slice(0, 180)
                      );

                    const adWord =
                      /(^|[ _-])(ad|ads|advert|banner|promo|popup|popunder|qc|quangcao)([ _-]|$)/i
                        .test(text);

                    const gambling =
                      /(bom88|casino|bet|betting|debet|yo88|win79|sun88|kubet|jun88|new88|fb88|m88|w88)/
                        .test(text);

                    if (
                      !adWord &&
                      !gambling
                    ) {
                      return false;
                    }

                    const style =
                      getComputedStyle(el);

                    if (
                      style.position !==
                        'fixed' &&
                      style.position !==
                        'sticky' &&
                      style.position !==
                        'absolute'
                    ) {
                      return false;
                    }

                    const rect =
                      el.getBoundingClientRect();

                    return (
                      rect.width >= 100 &&
                      rect.height >= 35
                    );
                  } catch (_) {
                    return false;
                  }
                };

              const processNode = (node) => {
                if (!node) return;

                removeKnownAdsFromRoot(
                  node
                );

                removePauseAdFromRoot(
                  node
                );

                if (
                  looksLikeFloatingAd(
                    node
                  )
                ) {
                  hide(node);
                }
              };

              const cleanDocumentOnce =
                () => {
                  try {
                    BASE_SELECTORS
                      .forEach(
                        selector => {
                          document
                            .querySelectorAll(
                              selector
                            )
                            .forEach(hide);
                        }
                      );
                  } catch (_) {}

                  removePauseAdFromRoot(
                    document
                  );
              };

              const schedulePauseClean =
                () => {
                  [
                    0,
                    40,
                    120,
                    300,
                    650
                  ].forEach(
                    delay => {
                      setTimeout(
                        cleanDocumentOnce,
                        delay
                      );
                    }
                  );
                };

              document.addEventListener(
                'pause',
                function(event) {
                  try {
                    if (
                      event.target &&
                      event.target.tagName ===
                        'VIDEO'
                    ) {
                      schedulePauseClean();
                    }
                  } catch (_) {}
                },
                true
              );

              document.addEventListener(
                'play',
                function(event) {
                  try {
                    if (
                      event.target &&
                      event.target.tagName ===
                        'VIDEO'
                    ) {
                      cleanDocumentOnce();
                    }
                  } catch (_) {}
                },
                true
              );

              document.addEventListener(
                'click',
                function(event) {
                  if (!POPUP_ENABLED) {
                    return;
                  }

                  try {
                    const anchor =
                      event.target &&
                      event.target.closest
                        ? event.target.closest(
                            'a[target="_blank"]'
                          )
                        : null;

                    if (anchor) {
                      anchor.removeAttribute(
                        'target'
                      );
                    }
                  } catch (_) {}
                },
                true
              );

              try {
                const observer =
                  new MutationObserver(
                    mutations => {
                      for (
                        const mutation
                        of mutations
                      ) {
                        for (
                          const node
                          of mutation.addedNodes
                        ) {
                          processNode(node);
                        }
                      }
                    }
                  );

                const startObserver =
                  () => {
                    const root =
                      document.documentElement;

                    if (!root) return false;

                    observer.observe(
                      root,
                      {
                        childList: true,
                        subtree: true
                      }
                    );

                    return true;
                  };

                if (!startObserver()) {
                  const bootstrap =
                    new MutationObserver(
                      (_, bootstrapObserver) => {
                        if (
                          startObserver()
                        ) {
                          bootstrapObserver
                            .disconnect();
                        }
                      }
                    );

                  bootstrap.observe(
                    document,
                    {
                      childList: true,
                      subtree: true
                    }
                  );
                }
              } catch (_) {}

              if (
                document.readyState ===
                  'loading'
              ) {
                document.addEventListener(
                  'DOMContentLoaded',
                  cleanDocumentOnce,
                  {
                    once: true
                  }
                );
              } else {
                cleanDocumentOnce();
              }
            })();
        """.trimIndent()
    }

    private fun openAddress() {
        var url =
            addressBar.text
                .toString()
                .trim()

        if (url.isEmpty()) {
            return
        }

        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {
            url = "https://" + url
        }

        webView.loadUrl(url)
    }

    private fun shouldBlock(
        uri: Uri
    ): Boolean {
        val host =
            uri.host
                ?.lowercase()
                ?: return false

        if (
            DomainRules.isAllowed(
                this,
                host
            )
        ) {
            return false
        }

        if (
            DomainRules.isBlocked(
                this,
                host
            )
        ) {
            return true
        }

        if (
            adBlocklist.isBlocked(
                host
            )
        ) {
            return true
        }

        val popupEnabled =
            getSharedPreferences(
                MainActivity.PREFS,
                MODE_PRIVATE
            ).getBoolean(
                MainActivity
                    .KEY_REDIRECT_PROTECTION,
                true
            )

        return (
            popupEnabled &&
                popupBlocklist
                    .isBlocked(host)
            )
    }

    private fun blockedResponse():
        WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "Blocked by AdShield",
            mapOf(
                "Cache-Control" to
                    "no-store"
            ),
            ByteArrayInputStream(
                ByteArray(0)
            )
        )
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            try {
                webView.stopLoading()
                webView.removeJavascriptInterface(
                    "AdShield"
                )
                webView.webChromeClient =
                    null
                webView.webViewClient =
                    WebViewClient()
                webView.loadUrl(
                    "about:blank"
                )
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            } catch (_: Throwable) {
            }
        }

        super.onDestroy()
    }

    inner class ShieldBridge {
        @JavascriptInterface
        fun isBlockedUrl(
            rawUrl: String
        ): Boolean {
            return try {
                shouldBlock(
                    Uri.parse(rawUrl)
                )
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"

        const val BROWSER_CRASH_FILE =
            "protected_browser_crash.log"
    }
}
