package com.thndang.adshield

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var totalText: TextView
    private lateinit var actionButton: Button
    private lateinit var redirectSwitch: Switch
    private lateinit var redirectStatsText: TextView
    private lateinit var rulesContainer: LinearLayout

    private var dialogShowing = false
    private var lastRulesSignature = ""

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshUi()
            showPendingRedirectAlert()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 40)

        ensureDefaults()
        buildUi()
        captureIntentDomain(intent)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureIntentDomain(intent)
        handler.post { showPendingRedirectAlert() }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    @Deprecated(
        "Deprecated in Android SDK; retained to keep this MVP dependency-free."
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (
            requestCode == VPN_PERMISSION_REQUEST &&
            resultCode == RESULT_OK
        ) {
            startShield()
        }
    }

    private fun ensureDefaults() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.contains(KEY_REDIRECT_PROTECTION)) {
            prefs.edit()
                .putBoolean(KEY_REDIRECT_PROTECTION, true)
                .apply()
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(28))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(TextView(this).apply {
            text = "🛡️"
            textSize = 64f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            setTextColor(Color.rgb(16, 24, 40))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        })

        root.addView(TextView(this).apply {
            text = "DNS ad blocker + bảo vệ popup / redirect"
            textSize = 15f
            setTextColor(Color.rgb(102, 112, 133))
            gravity = Gravity.CENTER
        })

        val statusCard = card()
        root.addView(
            statusCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(30)
            }
        )

        statusText = TextView(this).apply {
            textSize = 21f
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusText)

        countText = TextView(this).apply {
            textSize = 38f
            setTextColor(Color.rgb(16, 24, 40))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        statusCard.addView(countText)

        statusCard.addView(TextView(this).apply {
            text = "yêu cầu quảng cáo / theo dõi đã chặn"
            textSize = 13f
            setTextColor(Color.rgb(102, 112, 133))
            gravity = Gravity.CENTER
        })

        totalText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(102, 112, 133))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        statusCard.addView(totalText)

        actionButton = Button(this).apply {
            isAllCaps = false
            textSize = 18f
            minHeight = dp(58)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isShieldRunning()) {
                    stopShield()
                } else {
                    requestVpnAndStart()
                }
            }
        }
        root.addView(
            actionButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60)
            ).apply {
                topMargin = dp(22)
            }
        )

        val redirectCard = card()
        root.addView(
            redirectCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        switchRow.addView(
            TextView(this).apply {
                text = "Bảo vệ popup / redirect"
                textSize = 17f
                setTextColor(Color.rgb(16, 24, 40))
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        redirectSwitch = Switch(this).apply {
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_REDIRECT_PROTECTION, true)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_REDIRECT_PROTECTION, enabled)
                    .apply()

                Toast.makeText(
                    this@MainActivity,
                    if (enabled) {
                        "Đã bật bảo vệ popup / redirect"
                    } else {
                        "Đã tắt bảo vệ popup / redirect"
                    },
                    Toast.LENGTH_SHORT
                ).show()

                refreshUi()
            }
        }
        switchRow.addView(redirectSwitch)
        redirectCard.addView(switchRow)

        redirectCard.addView(TextView(this).apply {
            text =
                "Khi phát hiện domain thường dùng cho popup hoặc chuyển hướng, " +
                    "AdShield sẽ chặn và cảnh báo. Bạn có thể giữ chặn riêng " +
                    "phần redirect, chặn toàn bộ domain hoặc cho phép domain."
            textSize = 13f
            setTextColor(Color.rgb(102, 112, 133))
            setPadding(0, dp(8), 0, 0)
        })

        redirectStatsText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(105, 65, 198))
            setPadding(0, dp(10), 0, 0)
        }
        redirectCard.addView(redirectStatsText)

        val rulesCard = card()
        root.addView(
            rulesCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )

        rulesCard.addView(TextView(this).apply {
            text = "Quy tắc domain của bạn"
            textSize = 17f
            setTextColor(Color.rgb(16, 24, 40))
        })

        rulesCard.addView(TextView(this).apply {
            text =
                "Các domain bạn chọn “Chặn cả domain” hoặc “Cho phép” " +
                    "sẽ hiện ở đây. Bấm Xóa để hoàn tác."
            textSize = 13f
            setTextColor(Color.rgb(102, 112, 133))
            setPadding(0, dp(5), 0, dp(8))
        })

        rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rulesCard.addView(rulesContainer)

        root.addView(
            infoRow(
                "Giới hạn kỹ thuật",
                "AdShield thấy tên miền đích của truy vấn DNS, nhưng không thể " +
                    "xác định chắc chắn tab hoặc đoạn JavaScript nào đã tạo popup. " +
                    "Popup cùng domain với trang chính có thể không chặn được bằng DNS."
            )
        )

        root.addView(
            infoRow(
                "DNS thượng nguồn",
                "Tên miền không bị chặn hiện được chuyển tiếp tới Cloudflare 1.1.1.1."
            )
        )

        setContentView(scroll)
        refreshUi()
        refreshRulesUi(force = true)
    }

    private fun card(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(Color.WHITE, dp(18).toFloat())
        }
    }

    private fun infoRow(title: String, body: String): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return card().apply {
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTextColor(Color.rgb(16, 24, 40))
            })

            addView(TextView(this@MainActivity).apply {
                text = body
                textSize = 13f
                setTextColor(Color.rgb(102, 112, 133))
                setPadding(0, dp(5), 0, 0)
            })

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
    }

    private fun makeRuleRow(
        domain: String,
        label: String,
        onRemove: () -> Unit
    ): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))

            addView(
                TextView(this@MainActivity).apply {
                    text = "$label\n$domain"
                    textSize = 13f
                    setTextColor(Color.rgb(52, 64, 84))
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            addView(Button(this@MainActivity).apply {
                text = "Xóa"
                isAllCaps = false
                setOnClickListener {
                    onRemove()
                    refreshRulesUi(force = true)
                }
            })
        }
    }

    private fun refreshRulesUi(force: Boolean = false) {
        val blocks = DomainRules.customBlocks(this).sorted()
        val allows = DomainRules.customAllows(this).sorted()
        val signature =
            "b:" + blocks.joinToString(",") +
                "|a:" + allows.joinToString(",")

        if (!force && signature == lastRulesSignature) return
        lastRulesSignature = signature

        rulesContainer.removeAllViews()

        if (blocks.isEmpty() && allows.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "Chưa có quy tắc thủ công."
                textSize = 13f
                setTextColor(Color.rgb(102, 112, 133))
            })
            return
        }

        blocks.forEach { domain ->
            rulesContainer.addView(
                makeRuleRow(
                    domain,
                    "⛔ Chặn toàn bộ domain"
                ) {
                    DomainRules.removeBlock(
                        this@MainActivity,
                        domain
                    )
                    toast("Đã xóa quy tắc chặn $domain")
                }
            )
        }

        allows.forEach { domain ->
            rulesContainer.addView(
                makeRuleRow(
                    domain,
                    "✓ Cho phép domain"
                ) {
                    DomainRules.removeAllow(
                        this@MainActivity,
                        domain
                    )
                    toast("Đã xóa quy tắc cho phép $domain")
                }
            )
        }
    }

    private fun showPendingRedirectAlert() {
        if (dialogShowing) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val domain = prefs
            .getString(KEY_PENDING_REDIRECT_DOMAIN, null)
            ?.takeIf { it.isNotBlank() }
            ?: return

        dialogShowing = true

        AlertDialog.Builder(this)
            .setTitle("Phát hiện popup / redirect")
            .setMessage(
                "AdShield vừa chặn tên miền:\n\n$domain\n\n" +
                    "VPN DNS chỉ thấy tên miền đích nên không thể xác định " +
                    "chắc chắn tab nào đã tạo chuyển hướng. Bạn muốn xử lý thế nào?"
            )
            .setPositiveButton("Chặn cả domain") { _, _ ->
                DomainRules.addBlock(this, domain)
                clearPendingRedirect()
                refreshRulesUi(force = true)
                toast("$domain sẽ luôn bị chặn")
                dialogShowing = false
            }
            .setNegativeButton("Chỉ chặn redirect") { _, _ ->
                clearPendingRedirect()
                toast(
                    "Giữ chặn popup / redirect, không thêm quy tắc toàn domain"
                )
                dialogShowing = false
            }
            .setNeutralButton("Cho phép domain") { _, _ ->
                DomainRules.addAllow(this, domain)
                clearPendingRedirect()
                refreshRulesUi(force = true)
                toast("$domain đã được cho phép")
                dialogShowing = false
            }
            .setCancelable(false)
            .show()
    }

    private fun captureIntentDomain(intent: Intent) {
        val domain =
            intent.getStringExtra(EXTRA_REDIRECT_DOMAIN)
                ?.let(DomainRules::normalize)
                ?.takeIf { it.isNotEmpty() }
                ?: return

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_REDIRECT_DOMAIN, domain)
            .putLong(
                KEY_PENDING_REDIRECT_AT,
                System.currentTimeMillis()
            )
            .apply()
    }

    private fun clearPendingRedirect() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_REDIRECT_DOMAIN)
            .remove(KEY_PENDING_REDIRECT_AT)
            .apply()
    }

    private fun rounded(
        color: Int,
        radius: Float
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)

        if (prepareIntent == null) {
            startShield()
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(
                prepareIntent,
                VPN_PERMISSION_REQUEST
            )
        }
    }

    private fun startShield() {
        val intent = Intent(
            this,
            DnsVpnService::class.java
        ).setAction(DnsVpnService.ACTION_START)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        handler.postDelayed({ refreshUi() }, 400)
    }

    private fun stopShield() {
        startService(
            Intent(
                this,
                DnsVpnService::class.java
            ).setAction(DnsVpnService.ACTION_STOP)
        )

        handler.postDelayed({ refreshUi() }, 300)
    }

    private fun isShieldRunning(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_RUNNING, false)

    private fun refreshUi() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val running = prefs.getBoolean(KEY_RUNNING, false)
        val blocked = prefs.getLong(KEY_BLOCKED, 0)
        val total = prefs.getLong(KEY_TOTAL, 0)
        val redirectBlocked =
            prefs.getLong(KEY_REDIRECT_BLOCKED, 0)

        val baseFilters =
            prefs.getInt(KEY_BASE_FILTER_COUNT, 0)
        val redirectFilters =
            prefs.getInt(KEY_REDIRECT_FILTER_COUNT, 0)

        statusText.text =
            if (running) "Đang bảo vệ" else "Đang tắt"

        statusText.setTextColor(
            if (running) {
                Color.rgb(3, 152, 85)
            } else {
                Color.rgb(217, 45, 32)
            }
        )

        countText.text = "%,d".format(blocked)
        totalText.text =
            "%,d truy vấn DNS đã xử lý".format(total)

        redirectStatsText.text =
            "%,d redirect đã chặn · %,d domain redirect · %,d domain cơ bản"
                .format(
                    redirectBlocked,
                    redirectFilters,
                    baseFilters
                )

        actionButton.text =
            if (running) "Tắt bảo vệ" else "Bật bảo vệ"

        actionButton.background = rounded(
            if (running) {
                Color.rgb(217, 45, 32)
            } else {
                Color.rgb(105, 65, 198)
            },
            22f * resources.displayMetrics.density
        )

        refreshRulesUi()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 100
        private const val NOTIFICATION_PERMISSION_REQUEST = 200

        const val PREFS = "adshield_stats"
        const val KEY_RUNNING = "vpn_running"
        const val KEY_BLOCKED = "blocked_count"
        const val KEY_TOTAL = "total_count"

        const val KEY_REDIRECT_PROTECTION =
            "redirect_protection_enabled"
        const val KEY_REDIRECT_BLOCKED =
            "redirect_blocked_count"
        const val KEY_BASE_FILTER_COUNT =
            "base_filter_count"
        const val KEY_REDIRECT_FILTER_COUNT =
            "redirect_filter_count"

        const val KEY_PENDING_REDIRECT_DOMAIN =
            "pending_redirect_domain"
        const val KEY_PENDING_REDIRECT_AT =
            "pending_redirect_at"

        const val EXTRA_REDIRECT_DOMAIN =
            "redirect_domain"
    }
}
