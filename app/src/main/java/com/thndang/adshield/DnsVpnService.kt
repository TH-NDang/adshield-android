package com.thndang.adshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.File
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
    private val filterLoading = AtomicBoolean(false)

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    @Volatile
    private var localDnsServers: List<InetAddress> =
        emptyList()

    @Volatile
    private var adBlocklist: DomainBlocklist =
        DomainBlocklist.empty()

    @Volatile
    private var popupRedirectBlocklist: DomainBlocklist =
        DomainBlocklist.empty()

    private var blockedCount = 0L
    private var redirectBlockedCount = 0L
    private var totalCount = 0L
    private var sincePersist = 0

    override fun onCreate() {
        super.onCreate()

        val prefs =
            getSharedPreferences(
                MainActivity.PREFS,
                MODE_PRIVATE
            )

        blockedCount =
            prefs.getLong(
                MainActivity.KEY_BLOCKED,
                0
            )

        redirectBlockedCount =
            prefs.getLong(
                MainActivity.KEY_REDIRECT_BLOCKED,
                0
            )

        totalCount =
            prefs.getLong(
                MainActivity.KEY_TOTAL,
                0
            )

        // A new service instance means there is no active TUN yet.
        // Clear stale state left behind by a killed app process.
        markRunning(false)
        clearStartupError()

        createNotificationChannels()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopVpn()
                    stopForeground(
                        STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                }

                ACTION_RELOAD_FILTERS -> {
                    if (active.get()) {
                        loadFullFiltersAsync()
                    }
                }

                ACTION_RESTART -> {
                    restartVpn()
                }

                ACTION_START -> {
                    safeStartVpn()
                }
            }
        } catch (error: Throwable) {
            handleStartupFailure(error)
        }

        return Service.START_NOT_STICKY
    }

    private fun safeStartVpn() {
        if (active.get()) return

        try {
            // Foreground must happen immediately on a cold start.
            startInForeground()

            // Bundled lists are tiny and safe to load synchronously.
            // The large downloaded lists are loaded only after the VPN
            // is already established, on a background thread.
            loadBundledFilters()

            captureLocalDnsServers()

            val descriptor =
                establishTunnel()
                    ?: throw IllegalStateException(
                        "Android không tạo được giao diện VPN"
                    )

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

            // Hot-swap the aggressive lists after startup.
            loadFullFiltersAsync()
        } catch (error: Throwable) {
            handleStartupFailure(error)
        }
    }

    private fun establishTunnel():
        ParcelFileDescriptor? {
        val configureIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        val builder =
            Builder()
                .setSession("AdShield")
                .setConfigureIntent(
                    configureIntent
                )
                .setMtu(1500)
                .addAddress(
                    VPN_ADDRESS,
                    32
                )
                .addDnsServer(
                    VIRTUAL_DNS
                )
                .addRoute(
                    VIRTUAL_DNS,
                    32
                )
                .allowFamily(
                    OsConstants.AF_INET6
                )
                .setBlocking(true)

        VpnAppRules
            .bypassPackages(this)
            .forEach { appPackage ->
                if (
                    appPackage != packageName
                ) {
                    try {
                        builder
                            .addDisallowedApplication(
                                appPackage
                            )
                    } catch (_: Throwable) {
                        // App may have been uninstalled since
                        // the rule was saved. Ignore stale entries.
                    }
                }
            }

        return builder.establish()
    }

    private fun restartVpn() {
        stopVpn()

        try {
            worker?.join(600)
        } catch (_: Throwable) {
        }

        worker = null
        safeStartVpn()
    }

    private fun captureLocalDnsServers() {
        val manager =
            getSystemService(
                ConnectivityManager::class.java
            )

        val activeNetwork =
            manager.activeNetwork

        val networks =
            buildList {
                if (activeNetwork != null) {
                    add(activeNetwork)
                }

                manager.allNetworks
                    .forEach { network ->
                        if (
                            network !=
                                activeNetwork
                        ) {
                            add(network)
                        }
                    }
            }

        var fallback:
            List<InetAddress> =
            emptyList()

        for (network in networks) {
            val capabilities =
                manager.getNetworkCapabilities(
                    network
                )
                    ?: continue

            if (
                capabilities.hasTransport(
                    NetworkCapabilities
                        .TRANSPORT_VPN
                )
            ) {
                continue
            }

            val dns =
                manager
                    .getLinkProperties(
                        network
                    )
                    ?.dnsServers
                    ?.filter {
                        !it.isAnyLocalAddress
                    }
                    .orEmpty()

            if (dns.isEmpty()) {
                continue
            }

            if (fallback.isEmpty()) {
                fallback = dns
            }

            if (
                capabilities.hasTransport(
                    NetworkCapabilities
                        .TRANSPORT_WIFI
                ) ||
                capabilities.hasTransport(
                    NetworkCapabilities
                        .TRANSPORT_ETHERNET
                )
            ) {
                localDnsServers = dns
                return
            }
        }

        localDnsServers = fallback
    }

    private fun isLocalDomain(
        domain: String
    ): Boolean {
        val normalized =
            domain.trimEnd('.')
                .lowercase()

        return (
            normalized == "local" ||
                normalized.endsWith(
                    ".local"
                ) ||
                normalized == "lan" ||
                normalized.endsWith(
                    ".lan"
                ) ||
                normalized ==
                    "home.arpa" ||
                normalized.endsWith(
                    ".home.arpa"
                ) ||
                normalized.endsWith(
                    ".localdomain"
                )
            )
    }

    private fun loadBundledFilters() {
        try {
            adBlocklist =
                DomainBlocklist.load(
                    this,
                    "blocklist.txt"
                )

            popupRedirectBlocklist =
                DomainBlocklist.load(
                    this,
                    "popup_redirect_blocklist.txt"
                )

            persistFilterCounts(
                adBlocklist.size,
                popupRedirectBlocklist.size
            )
        } catch (error: Throwable) {
            // Even bundled-filter failure should not prevent the VPN
            // from starting. Start with empty filters and preserve
            // connectivity instead.
            recordServiceError(
                "loadBundledFilters",
                error
            )

            adBlocklist =
                DomainBlocklist.empty()

            popupRedirectBlocklist =
                DomainBlocklist.empty()
        }
    }

    private fun loadFullFiltersAsync() {
        if (
            !filterLoading.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        thread(
            start = true,
            isDaemon = true,
            name = "AdShield-filter-loader"
        ) {
            try {
                val newAd =
                    DomainBlocklist.load(
                        this,
                        "blocklist.txt",
                        FilterUpdater
                            .MAIN_REMOTE_FILE
                    )

                val newPopup =
                    DomainBlocklist.load(
                        this,
                        "popup_redirect_blocklist.txt",
                        FilterUpdater
                            .POPUP_REMOTE_FILE
                    )

                // One atomic-ish handoff per list. The DNS thread
                // keeps using the old immutable arrays until this point.
                adBlocklist = newAd
                popupRedirectBlocklist =
                    newPopup

                persistFilterCounts(
                    newAd.size,
                    newPopup.size
                )

                clearFilterLoadError()
            } catch (error: Throwable) {
                // Keep the already-working bundled/previous lists.
                recordServiceError(
                    "loadFullFiltersAsync",
                    error
                )

                getSharedPreferences(
                    MainActivity.PREFS,
                    MODE_PRIVATE
                )
                    .edit()
                    .putString(
                        MainActivity
                            .KEY_VPN_FILTER_LOAD_ERROR,
                        error.message
                            ?: error.javaClass.simpleName
                    )
                    .apply()
            } finally {
                filterLoading.set(false)
            }
        }
    }

    private fun persistFilterCounts(
        adCount: Int,
        popupCount: Int
    ) {
        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putInt(
                MainActivity
                    .KEY_BASE_FILTER_COUNT,
                adCount
            )
            .putInt(
                MainActivity
                    .KEY_REDIRECT_FILTER_COUNT,
                popupCount
            )
            .apply()
    }

    private fun clearFilterLoadError() {
        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .remove(
                MainActivity
                    .KEY_VPN_FILTER_LOAD_ERROR
            )
            .apply()
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

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        // If protection is off, make sure a stale running flag
        // cannot survive task removal.
        if (!active.get()) {
            markRunning(false)
        }

        super.onTaskRemoved(rootIntent)
    }

    private fun stopVpn() {
        active.set(false)

        try {
            tunnel?.close()
        } catch (_: Throwable) {
        }

        tunnel = null

        persistStats()
        markRunning(false)
    }

    private fun runDnsLoop(
        descriptor: ParcelFileDescriptor
    ) {
        val input =
            FileInputStream(
                descriptor.fileDescriptor
            )

        val output =
            FileOutputStream(
                descriptor.fileDescriptor
            )

        val packetBuffer =
            ByteArray(32767)

        val prefs =
            getSharedPreferences(
                MainActivity.PREFS,
                MODE_PRIVATE
            )

        try {
            while (active.get()) {
                val length =
                    input.read(
                        packetBuffer
                    )

                if (length <= 0) {
                    continue
                }

                val request =
                    DnsCodec
                        .parseIpv4UdpDns(
                            packetBuffer,
                            length
                        )
                        ?: continue

                totalCount++
                sincePersist++

                val domain =
                    DomainRules.normalize(
                        request.domain
                    )

                val allowed =
                    DomainRules.isAllowed(
                        this,
                        domain
                    )

                val customBlocked =
                    !allowed &&
                        DomainRules.isBlocked(
                            this,
                            domain
                        )

                val baseBlocked =
                    !allowed &&
                        adBlocklist
                            .isBlocked(
                                domain
                            )

                val redirectProtectionEnabled =
                    prefs.getBoolean(
                        MainActivity
                            .KEY_REDIRECT_PROTECTION,
                        true
                    )

                val popupRedirectMatched =
                    popupRedirectBlocklist
                        .isBlocked(
                            domain
                        )

                val localNetworkCompatibility =
                    prefs.getBoolean(
                        MainActivity
                            .KEY_LOCAL_NETWORK_COMPATIBILITY,
                        true
                    )

                val localDomain =
                    localNetworkCompatibility &&
                        isLocalDomain(
                            domain
                        )

                val popupRedirectBlocked =
                    !localDomain &&
                        !allowed &&
                        redirectProtectionEnabled &&
                        popupRedirectMatched

                val blocked =
                    !localDomain &&
                        (
                            customBlocked ||
                                baseBlocked ||
                                popupRedirectBlocked
                            )

                val response =
                    if (blocked) {
                        blockedCount++

                        if (
                            popupRedirectBlocked
                        ) {
                            redirectBlockedCount++

                            registerRedirectDetection(
                                domain
                            )
                        }

                        DnsCodec
                            .buildBlockedNxdomain(
                                request.dnsPayload
                            )
                    } else if (
                        localDomain
                    ) {
                        forwardToLocalDns(
                            request.dnsPayload
                        )
                            ?: forwardToUpstream(
                                request.dnsPayload
                            )
                    } else {
                        forwardToUpstream(
                            request.dnsPayload
                        )
                    }

                if (response != null) {
                    val responsePacket =
                        DnsCodec
                            .buildIpv4UdpResponse(
                                request,
                                response
                            )

                    output.write(
                        responsePacket
                    )
                }

                if (
                    sincePersist >= 10 ||
                    blocked
                ) {
                    persistStats()
                }
            }
        } catch (error: Throwable) {
            if (active.get()) {
                recordServiceError(
                    "runDnsLoop",
                    error
                )
            }
        } finally {
            try {
                input.close()
            } catch (_: Throwable) {
            }

            try {
                output.close()
            } catch (_: Throwable) {
            }

            persistStats()
            markRunning(false)
        }
    }

    private fun handleStartupFailure(
        error: Throwable
    ) {
        recordServiceError(
            "startup",
            error
        )

        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putString(
                MainActivity
                    .KEY_VPN_START_ERROR,
                error.javaClass.simpleName +
                    ": " +
                    (
                        error.message
                            ?: "không có thông báo"
                        )
            )
            .apply()

        active.set(false)

        try {
            tunnel?.close()
        } catch (_: Throwable) {
        }

        tunnel = null
        markRunning(false)

        try {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        } catch (_: Throwable) {
        }

        stopSelf()
    }

    private fun recordServiceError(
        stage: String,
        error: Throwable
    ) {
        try {
            val text = buildString {
                appendLine(
                    "AdShield VPN service error"
                )
                appendLine(
                    "Stage: " + stage
                )
                appendLine(
                    "Time: " +
                        System.currentTimeMillis()
                )
                appendLine(
                    "Device: " +
                        Build.MANUFACTURER +
                        " " +
                        Build.MODEL
                )
                appendLine(
                    "Android: " +
                        Build.VERSION.RELEASE
                )
                appendLine()
                appendLine(
                    Log.getStackTraceString(
                        error
                    )
                )
            }

            File(
                filesDir,
                SERVICE_ERROR_FILE
            ).writeText(text)
        } catch (_: Throwable) {
        }
    }

    private fun clearStartupError() {
        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .remove(
                MainActivity
                    .KEY_VPN_START_ERROR
            )
            .apply()
    }

    private fun registerRedirectDetection(
        domain: String
    ) {
        if (domain.isEmpty()) return

        if (
            DomainRules.wasWarned(
                this,
                domain
            )
        ) {
            return
        }

        DomainRules.markWarned(
            this,
            domain
        )

        val prefs =
            getSharedPreferences(
                MainActivity.PREFS,
                MODE_PRIVATE
            )

        prefs.edit()
            .putString(
                MainActivity
                    .KEY_PENDING_REDIRECT_DOMAIN,
                domain
            )
            .putLong(
                MainActivity
                    .KEY_PENDING_REDIRECT_AT,
                System.currentTimeMillis()
            )
            .apply()

        showRedirectNotification(
            domain
        )
    }

    private fun showRedirectNotification(
        domain: String
    ) {
        val openApp =
            PendingIntent.getActivity(
                this,
                domain.hashCode() and
                    0x7fffffff,
                Intent(
                    this,
                    MainActivity::class.java
                ).apply {
                    putExtra(
                        MainActivity
                            .EXTRA_REDIRECT_DOMAIN,
                        domain
                    )

                    addFlags(
                        Intent
                            .FLAG_ACTIVITY_NEW_TASK or
                            Intent
                                .FLAG_ACTIVITY_CLEAR_TOP
                    )
                },
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent
                        .FLAG_UPDATE_CURRENT
            )

        val notification =
            Notification.Builder(
                this,
                ALERT_CHANNEL_ID
            )
                .setSmallIcon(
                    R.drawable.ic_shield
                )
                .setContentTitle(
                    "AdShield đã chặn popup / redirect"
                )
                .setContentText(
                    domain +
                        " · Chạm để chọn cách xử lý"
                )
                .setContentIntent(
                    openApp
                )
                .setAutoCancel(true)
                .setCategory(
                    Notification.CATEGORY_STATUS
                )
                .build()

        getSystemService(
            NotificationManager::class.java
        )
            .notify(
                ALERT_NOTIFICATION_BASE +
                    (
                        domain.hashCode() and
                            0x3ff
                        ),
                notification
            )
    }

    private fun forwardToLocalDns(
        query: ByteArray
    ): ByteArray? {
        for (
            server in
            localDnsServers
        ) {
            val response =
                forwardToDns(
                    query,
                    server
                )

            if (response != null) {
                return response
            }
        }

        return null
    }

    private fun forwardToDns(
        query: ByteArray,
        upstream: InetAddress
    ): ByteArray? {
        val receiveBuffer =
            ByteArray(4096)

        return try {
            DatagramSocket().use {
                socket ->

                if (!protect(socket)) {
                    return null
                }

                socket.soTimeout = 1400

                socket.send(
                    DatagramPacket(
                        query,
                        query.size,
                        upstream,
                        53
                    )
                )

                val response =
                    DatagramPacket(
                        receiveBuffer,
                        receiveBuffer.size
                    )

                socket.receive(
                    response
                )

                response.data
                    .copyOfRange(
                        response.offset,
                        response.offset +
                            response.length
                    )
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun forwardToUpstream(
        query: ByteArray
    ): ByteArray? {
        val upstream =
            InetAddress.getByName(
                UPSTREAM_DNS
            )

        return forwardToDns(
            query,
            upstream
        )
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
                MainActivity
                    .KEY_REDIRECT_BLOCKED,
                redirectBlockedCount
            )
            .putLong(
                MainActivity.KEY_TOTAL,
                totalCount
            )
            .apply()
    }

    private fun markRunning(
        running: Boolean
    ) {
        getSharedPreferences(
            MainActivity.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                MainActivity.KEY_RUNNING,
                running
            )
            .apply()
    }

    private fun startInForeground() {
        val notification =
            buildServiceNotification()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun buildServiceNotification():
        Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                1,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent
                        .FLAG_UPDATE_CURRENT
            )

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                R.drawable.ic_shield
            )
            .setContentTitle(
                "AdShield đang bảo vệ"
            )
            .setContentText(
                "Đang lọc DNS quảng cáo trên thiết bị"
            )
            .setContentIntent(
                openApp
            )
            .setOngoing(true)
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .build()
    }

    private fun createNotificationChannels() {
        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Bảo vệ AdShield",
                    NotificationManager
                        .IMPORTANCE_LOW
                ).apply {
                    description =
                        "Thông báo khi bộ lọc DNS đang hoạt động"
                }
            )

        manager
            .createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Cảnh báo popup / redirect",
                    NotificationManager
                        .IMPORTANCE_DEFAULT
                ).apply {
                    description =
                        "Cảnh báo domain popup/redirect"
                }
            )
    }

    companion object {
        const val ACTION_START =
            "com.thndang.adshield.START"

        const val ACTION_STOP =
            "com.thndang.adshield.STOP"

        const val ACTION_RELOAD_FILTERS =
            "com.thndang.adshield.RELOAD_FILTERS"

        const val ACTION_RESTART =
            "com.thndang.adshield.RESTART"

        const val SERVICE_ERROR_FILE =
            "vpn_service_error.log"

        private const val VPN_ADDRESS =
            "10.111.222.1"

        private const val VIRTUAL_DNS =
            "10.111.222.2"

        private const val UPSTREAM_DNS =
            "1.1.1.1"

        private const val CHANNEL_ID =
            "adshield_vpn"

        private const val ALERT_CHANNEL_ID =
            "adshield_popup_redirect_alerts"

        private const val NOTIFICATION_ID =
            1001

        private const val ALERT_NOTIFICATION_BASE =
            4000
    }
}
