package com.thndang.adshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DnsVpnService : VpnService() {

    private val active = AtomicBoolean(false)
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    @Volatile
    private var adBlocklist: DomainBlocklist = DomainBlocklist.empty()

    @Volatile
    private var popupRedirectBlocklist: DomainBlocklist =
        DomainBlocklist.empty()

    private var blockedCount = 0L
    private var redirectBlockedCount = 0L
    private var totalCount = 0L
    private var sincePersist = 0

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        blockedCount = prefs.getLong(MainActivity.KEY_BLOCKED, 0)
        redirectBlockedCount =
            prefs.getLong(MainActivity.KEY_REDIRECT_BLOCKED, 0)
        totalCount = prefs.getLong(MainActivity.KEY_TOTAL, 0)

        createNotificationChannels()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_RELOAD_FILTERS -> reloadFiltersAsync()

            ACTION_START -> startVpn()
        }
        return Service.START_NOT_STICKY
    }

    private fun loadFilters() {
        val newAd = DomainBlocklist.load(
            this,
            "blocklist.txt",
            FilterUpdater.MAIN_REMOTE_FILE
        )
        val newPopup = DomainBlocklist.load(
            this,
            "popup_redirect_blocklist.txt",
            FilterUpdater.POPUP_REMOTE_FILE
        )

        adBlocklist = newAd
        popupRedirectBlocklist = newPopup

        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putInt(
                MainActivity.KEY_BASE_FILTER_COUNT,
                newAd.size
            )
            .putInt(
                MainActivity.KEY_REDIRECT_FILTER_COUNT,
                newPopup.size
            )
            .apply()
    }

    private fun reloadFiltersAsync() {
        adBlocklist = DomainBlocklist.empty()
        popupRedirectBlocklist = DomainBlocklist.empty()
        System.gc()

        thread(
            start = true,
            isDaemon = true,
            name = "AdShield-filter-reload"
        ) {
            loadFilters()
        }
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (active.get()) return

        startInForeground()
        loadFilters()

        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val descriptor = Builder()
            .setSession("AdShield")
            .setConfigureIntent(configureIntent)
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .allowFamily(OsConstants.AF_INET6)
            .setBlocking(true)
            .establish()

        if (descriptor == null) {
            markRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        tunnel = descriptor
        active.set(true)
        markRunning(true)

        worker = thread(
            start = true,
            isDaemon = true,
            name = "AdShield-DNS"
        ) {
            runDnsLoop(descriptor)
        }
    }

    private fun stopVpn() {
        active.set(false)
        try {
            tunnel?.close()
        } catch (_: Exception) {
        }
        tunnel = null
        persistStats()
        markRunning(false)
    }

    private fun runDnsLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val packetBuffer = ByteArray(32767)
        val prefs = getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )

        try {
            while (active.get()) {
                val length = input.read(packetBuffer)
                if (length <= 0) continue

                val request =
                    DnsCodec.parseIpv4UdpDns(packetBuffer, length)
                        ?: continue

                totalCount++
                sincePersist++

                val domain = DomainRules.normalize(request.domain)
                val allowed = DomainRules.isAllowed(this, domain)

                val customBlocked =
                    !allowed && DomainRules.isBlocked(this, domain)

                val baseBlocked =
                    !allowed && adBlocklist.isBlocked(domain)

                val redirectProtectionEnabled =
                    prefs.getBoolean(
                        MainActivity.KEY_REDIRECT_PROTECTION,
                        true
                    )

                val popupRedirectMatched =
                    popupRedirectBlocklist.isBlocked(domain)

                val popupRedirectBlocked =
                    !allowed &&
                        redirectProtectionEnabled &&
                        popupRedirectMatched

                val blocked =
                    customBlocked ||
                        baseBlocked ||
                        popupRedirectBlocked

                val response = if (blocked) {
                    blockedCount++

                    if (popupRedirectBlocked) {
                        redirectBlockedCount++
                        registerRedirectDetection(domain)
                    }

                    DnsCodec.buildBlockedNxdomain(
                        request.dnsPayload
                    )
                } else {
                    forwardToUpstream(request.dnsPayload)
                }

                if (response != null) {
                    val responsePacket =
                        DnsCodec.buildIpv4UdpResponse(
                            request,
                            response
                        )
                    output.write(responsePacket)
                }

                if (sincePersist >= 10 || blocked) {
                    persistStats()
                }
            }
        } catch (_: Exception) {
            // Closing TUN is the normal way to stop this loop.
        } finally {
            persistStats()
            markRunning(false)
        }
    }

    private fun registerRedirectDetection(domain: String) {
        if (domain.isEmpty()) return
        if (DomainRules.wasWarned(this, domain)) return

        DomainRules.markWarned(this, domain)

        val prefs = getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )

        prefs.edit()
            .putString(
                MainActivity.KEY_PENDING_REDIRECT_DOMAIN,
                domain
            )
            .putLong(
                MainActivity.KEY_PENDING_REDIRECT_AT,
                System.currentTimeMillis()
            )
            .apply()

        showRedirectNotification(domain)
    }

    private fun showRedirectNotification(domain: String) {
        val openApp = PendingIntent.getActivity(
            this,
            domain.hashCode() and 0x7fffffff,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_REDIRECT_DOMAIN, domain)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            },
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(
            this,
            ALERT_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AdShield đã chặn popup / redirect")
            .setContentText(
                "$domain · Chạm để chọn cách xử lý"
            )
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Phát hiện tên miền popup/redirect: $domain. " +
                        "Mở AdShield để giữ chặn phần redirect, " +
                        "chặn toàn bộ domain hoặc cho phép domain."
                )
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(
                ALERT_NOTIFICATION_BASE +
                    (domain.hashCode() and 0x3ff),
                notification
            )
    }

    private fun forwardToUpstream(
        query: ByteArray
    ): ByteArray? {
        val upstream = InetAddress.getByName(UPSTREAM_DNS)
        val receiveBuffer = ByteArray(4096)

        return try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                socket.soTimeout = 2500

                socket.send(
                    DatagramPacket(
                        query,
                        query.size,
                        upstream,
                        53
                    )
                )

                val response = DatagramPacket(
                    receiveBuffer,
                    receiveBuffer.size
                )
                socket.receive(response)

                response.data.copyOfRange(
                    response.offset,
                    response.offset + response.length
                )
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun persistStats() {
        sincePersist = 0
        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putLong(
                MainActivity.KEY_BLOCKED,
                blockedCount
            )
            .putLong(
                MainActivity.KEY_REDIRECT_BLOCKED,
                redirectBlockedCount
            )
            .putLong(
                MainActivity.KEY_TOTAL,
                totalCount
            )
            .apply()
    }

    private fun markRunning(running: Boolean) {
        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putBoolean(MainActivity.KEY_RUNNING, running)
            .apply()
    }

    private fun startInForeground() {
        val notification = buildServiceNotification()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AdShield đang bảo vệ")
            .setContentText(
                "Đang lọc DNS quảng cáo và redirect trên thiết bị"
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannels() {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bảo vệ AdShield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Thông báo khi bộ lọc DNS cục bộ đang hoạt động"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Cảnh báo popup / redirect",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "Cảnh báo khi AdShield phát hiện tên miền popup hoặc redirect"
            }
        )
    }

    companion object {
        const val ACTION_START = "com.thndang.adshield.START"
        const val ACTION_STOP = "com.thndang.adshield.STOP"
        const val ACTION_RELOAD_FILTERS =
            "com.thndang.adshield.RELOAD_FILTERS"

        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val UPSTREAM_DNS = "1.1.1.1"

        private const val CHANNEL_ID = "adshield_vpn"
        private const val ALERT_CHANNEL_ID =
            "adshield_popup_redirect_alerts"

        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_BASE = 4000
    }
}
