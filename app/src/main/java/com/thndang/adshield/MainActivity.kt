package com.thndang.adshield

import android.Manifest
import android.app.Activity
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var totalText: TextView
    private lateinit var actionButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 40)
        buildUi()

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

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    @Deprecated("Deprecated in Android SDK; retained to keep this MVP dependency-free.")
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
                ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT
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
            text = "Chặn quảng cáo bằng DNS cục bộ"
            textSize = 15f
            setTextColor(Color.rgb(102, 112, 133))
            gravity = Gravity.CENTER
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = rounded(Color.WHITE, dp(20).toFloat())
        }
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

        root.addView(
            infoRow(
                "Riêng tư",
                "AdShield không tạo VPN từ xa. Chỉ các gói DNS tới địa chỉ DNS ảo được đưa qua giao diện VPN cục bộ."
            )
        )
        root.addView(
            infoRow(
                "Giới hạn",
                "DNS-over-HTTPS/QUIC trong một số ứng dụng có thể bỏ qua bộ lọc. Quảng cáo YouTube trong ứng dụng thường không thể chặn ổn định bằng DNS."
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
    }

    private fun infoRow(title: String, body: String): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Color.WHITE, dp(16).toFloat())

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

    private fun rounded(color: Int, radius: Float): GradientDrawable =
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
            startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST)
        }
    }

    private fun startShield() {
        val intent = Intent(this, DnsVpnService::class.java)
            .setAction(DnsVpnService.ACTION_START)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        handler.postDelayed({ refreshUi() }, 400)
    }

    private fun stopShield() {
        startService(
            Intent(this, DnsVpnService::class.java)
                .setAction(DnsVpnService.ACTION_STOP)
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

        statusText.text = if (running) "Đang bảo vệ" else "Đang tắt"
        statusText.setTextColor(
            if (running) {
                Color.rgb(3, 152, 85)
            } else {
                Color.rgb(217, 45, 32)
            }
        )

        countText.text = "%,d".format(blocked)
        totalText.text = "%,d truy vấn DNS đã xử lý".format(total)
        actionButton.text = if (running) "Tắt bảo vệ" else "Bật bảo vệ"
        actionButton.background = rounded(
            if (running) {
                Color.rgb(217, 45, 32)
            } else {
                Color.rgb(105, 65, 198)
            },
            22f * resources.displayMetrics.density
        )
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 100
        private const val NOTIFICATION_PERMISSION_REQUEST = 200

        const val PREFS = "adshield_stats"
        const val KEY_RUNNING = "vpn_running"
        const val KEY_BLOCKED = "blocked_count"
        const val KEY_TOTAL = "total_count"
    }
}
