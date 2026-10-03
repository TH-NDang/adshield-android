package com.thndang.adshield

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class BypassAppsActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        window.statusBarColor =
            Color.rgb(16, 24, 40)

        buildUi()
    }

    private fun buildUi() {
        val density =
            resources.displayMetrics.density

        fun dp(value: Int) =
            (value * density).toInt()

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(20),
                    dp(28),
                    dp(20),
                    dp(24)
                )

                setBackgroundColor(
                    Color.rgb(
                        247,
                        249,
                        252
                    )
                )
            }

        root.addView(
            TextView(this).apply {
                text =
                    "Ứng dụng bỏ qua AdShield"

                textSize = 23f

                setTextColor(
                    Color.rgb(
                        16,
                        24,
                        40
                    )
                )
            }
        )

        root.addView(
            TextView(this).apply {
                text =
                    "Bật cho app Remote TV, Chromecast, máy in, " +
                    "smart-home hoặc app cần giao tiếp trực tiếp trong Wi‑Fi. " +
                    "Các app được chọn sẽ không đi qua AdShield VPN."

                textSize = 13f

                setTextColor(
                    Color.rgb(
                        102,
                        112,
                        133
                    )
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(14)
                )
            }
        )

        val scroll =
            ScrollView(this)

        val appsContainer =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        scroll.addView(
            appsContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams
                    .MATCH_PARENT,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT
            )
        )

        val launcherIntent =
            Intent(
                Intent.ACTION_MAIN
            ).apply {
                addCategory(
                    Intent.CATEGORY_LAUNCHER
                )
            }

        val apps =
            packageManager
                .queryIntentActivities(
                    launcherIntent,
                    0
                )
                .map {
                    val appPackage =
                        it.activityInfo
                            .packageName

                    val label =
                        it.loadLabel(
                            packageManager
                        )
                            ?.toString()
                            ?.trim()
                            .orEmpty()

                    Triple(
                        label.ifEmpty {
                            appPackage
                        },
                        appPackage,
                        it
                    )
                }
                .filter {
                    it.second !=
                        packageName
                }
                .distinctBy {
                    it.second
                }
                .sortedBy {
                    it.first.lowercase()
                }

        apps.forEach {
            (label, appPackage, _) ->

            val row =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        0,
                        dp(8),
                        0,
                        dp(8)
                    )
                }

            row.addView(
                TextView(this).apply {
                    text =
                        label +
                            "\n" +
                            appPackage

                    textSize = 14f

                    setTextColor(
                        Color.rgb(
                            52,
                            64,
                            84
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams
                        .WRAP_CONTENT,
                    1f
                )
            )

            row.addView(
                Switch(this).apply {
                    isChecked =
                        VpnAppRules
                            .isBypassed(
                                this@BypassAppsActivity,
                                appPackage
                            )

                    setOnCheckedChangeListener {
                        _,
                        checked ->

                        VpnAppRules
                            .setBypassed(
                                this@BypassAppsActivity,
                                appPackage,
                                checked
                            )
                    }
                }
            )

            appsContainer.addView(
                row
            )
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            Button(this).apply {
                text =
                    "Áp dụng và khởi động lại VPN"

                isAllCaps = false

                setOnClickListener {
                    applyVpnRules()
                }
            }
        )

        root.addView(
            Button(this).apply {
                text = "Xong"
                isAllCaps = false

                setOnClickListener {
                    finish()
                }
            }
        )

        setContentView(root)
    }

    private fun applyVpnRules() {
        val prefs =
            getSharedPreferences(
                MainActivity.PREFS,
                MODE_PRIVATE
            )

        val running =
            prefs.getBoolean(
                MainActivity.KEY_RUNNING,
                false
            )

        if (!running) {
            Toast.makeText(
                this,
                "Đã lưu. Quy tắc sẽ áp dụng khi bật AdShield.",
                Toast.LENGTH_SHORT
            ).show()

            finish()
            return
        }

        if (
            VpnService.prepare(this) != null
        ) {
            Toast.makeText(
                this,
                "Quyền VPN cần được cấp lại. Hãy về màn hình chính và bật AdShield.",
                Toast.LENGTH_LONG
            ).show()

            finish()
            return
        }

        val intent =
            Intent(
                this,
                DnsVpnService::class.java
            ).setAction(
                DnsVpnService.ACTION_RESTART
            )

        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
        ) {
            startForegroundService(
                intent
            )
        } else {
            startService(intent)
        }

        Toast.makeText(
            this,
            "Đang áp dụng danh sách bypass…",
            Toast.LENGTH_SHORT
        ).show()

        finish()
    }
}
