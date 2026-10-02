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
    private lateinit var blocklist: DomainBlocklist

    private var blockedCount = 0L
    private var totalCount = 0L
    private var sincePersist = 0

    override fun onCreate() {
        super.onCreate()
        blocklist = DomainBlocklist.load(this)

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        blockedCount = prefs.getLong(MainActivity.KEY_BLOCKED, 0)
        totalCount = prefs.getLong(MainActivity.KEY_TOTAL, 0)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> startVpn()
        }
        return Service.START_NOT_STICKY
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

        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

        try {
            while (active.get()) {
                val length = input.read(packetBuffer)
                if (length <= 0) continue

                val request = DnsCodec.parseIpv4UdpDns(packetBuffer, length)
                    ?: continue

                totalCount++
                sincePersist++
                val blocked = blocklist.isBlocked(request.domain)

                val response = if (blocked) {
                    blockedCount++
                    DnsCodec.buildBlockedNxdomain(request.dnsPayload)
                } else {
                    forwardToUpstream(request.dnsPayload)
                }

                if (response != null) {
                    val responsePacket =
                        DnsCodec.buildIpv4UdpResponse(request, response)
                    output.write(responsePacket)
                }

                if (sincePersist >= 10 || blocked) {
                    persistStats()
                }
            }
        } catch (_: Exception) {
            // Closing the TUN descriptor is the normal way to stop the blocking read loop.
        } finally {
            persistStats()
            markRunning(false)
        }
    }

    private fun forwardToUpstream(query: ByteArray): ByteArray? {
        val upstream = InetAddress.getByName(UPSTREAM_DNS)
        val receiveBuffer = ByteArray(4096)

        return try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                socket.soTimeout = 2500

                socket.send(
                    DatagramPacket(query, query.size, upstream, 53)
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
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .edit()
            .putLong(MainActivity.KEY_BLOCKED, blockedCount)
            .putLong(MainActivity.KEY_TOTAL, totalCount)
            .apply()
    }

    private fun markRunning(running: Boolean) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_RUNNING, running)
            .apply()
    }

    private fun startInForeground() {
        val notification = buildNotification()
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

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AdShield đang bảo vệ")
            .setContentText("Đang lọc truy vấn DNS quảng cáo trên thiết bị")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bảo vệ AdShield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo khi bộ lọc DNS cục bộ đang hoạt động"
            }
        )
    }

    companion object {
        const val ACTION_START = "com.thndang.adshield.START"
        const val ACTION_STOP = "com.thndang.adshield.STOP"

        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val UPSTREAM_DNS = "1.1.1.1"

        private const val CHANNEL_ID = "adshield_vpn"
        private const val NOTIFICATION_ID = 1001
    }
}
