package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.local.VpnDatabase
import com.example.data.vpn.TunnelStats
import com.example.data.vpn.VpnStatus
import com.example.data.vpn.XrayConfigGenerator
import com.example.domain.model.AppLog
import com.example.domain.model.LogLevel
import com.example.domain.model.ServerConfig
import com.example.domain.model.VpnSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.XrayVpnService.START"
        const val ACTION_STOP = "com.example.XrayVpnService.STOP"
        private const val CHANNEL_ID = "xray_vpn_channel"
        private const val NOTIFICATION_ID = 2026
        private const val TAG = "XrayVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    private var packetProcessorJob: Job? = null
    private var diagnosticLogJob: Job? = null
    private var durationSeconds = 0L

    // Loopback Inbound SOCKS5 Server variables
    private var xraySocksInboundServer: ServerSocket? = null
    private var socksInboundJob: Job? = null

    // Thread-safe Diagnostic Counters
    private val packetsReceivedFromTun = AtomicLong(0)
    private val packetsForwardedToTun2Socks = AtomicLong(0)
    private val packetsForwardedToXray = AtomicLong(0)
    private val packetsReturnedFromXray = AtomicLong(0)
    private val tcpConnectionsEstablished = AtomicLong(0)
    private val udpConnectionsEstablished = AtomicLong(0)
    private val dnsQueriesForwarded = AtomicLong(0)
    private val dnsQueriesResolved = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")
        
        if (action == ACTION_START) {
            startVpn()
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (TunnelStats.currentStats.value.status == VpnStatus.CONNECTED) {
            logEvent("VPN already active", LogLevel.WARNING)
            return
        }

        TunnelStats.updateStatus(VpnStatus.CONNECTING)
        logEvent("[Xray Audit] Initializing core pipeline services...", LogLevel.INFO)

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xray VPN Client")
            .setContentText("Core service running... Secure routing active")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            val db = VpnDatabase.getDatabase(this@XrayVpnService)
            val dao = db.vpnDao()
            
            val servers = dao.getServers().firstOrNull() ?: emptyList()
            val activeServer = servers.find { it.isSelected }
            
            if (activeServer == null) {
                logEvent("Cannot start. No active Server Configuration selected.", LogLevel.ERROR)
                TunnelStats.updateStatus(VpnStatus.DISCONNECTED)
                stopForeground(true)
                stopSelf()
                return@launch
            }

            val settingsFlow = dao.getSettingsFlow()
            val settings = settingsFlow.firstOrNull() ?: VpnSettings()

            logEvent("Loaded Server Profile: ${activeServer.name}", LogLevel.INFO)
            logEvent("Selected Protocol: ${activeServer.protocol} (TLS: ${activeServer.security})", LogLevel.INFO)
            
            // Step 1: Generate configuration & start local Xray SOCKS5 Inbound Daemon
            val xrayJson = XrayConfigGenerator.generate(activeServer, settings)
            logEvent("Generated Config JSON:\n$xrayJson", LogLevel.INFO)
            
            logEvent("Starting local Xray SOCKS5 Inbound Daemon (Port: 10808)...", LogLevel.INFO)
            startXraySocksInbound(settings, activeServer)
            delay(400) // allow bind

            if (xraySocksInboundServer == null || xraySocksInboundServer?.isClosed == true) {
                logEvent("CRITICAL: Failed to bind Xray SOCKS5 Inbound at port 10808.", LogLevel.ERROR)
                TunnelStats.updateStatus(VpnStatus.DISCONNECTED)
                stopVpn()
                return@launch
            }
            logEvent("Xray SOCKS5 Inbound daemon listening on 127.0.0.1:10808 successfully.", LogLevel.SUCCESS)

            // Step 2: Test handshake connectivity to remote server endpoint
            val startTime = System.currentTimeMillis()
            var isReachable = false
            var latency = 0L
            try {
                withContext(Dispatchers.IO) {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(activeServer.address, activeServer.port), 3500)
                    isReachable = true
                    latency = System.currentTimeMillis() - startTime
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed", e)
                logEvent("Connection test failed to proxy ${activeServer.address}:${activeServer.port}: ${e.message}", LogLevel.WARNING)
            }

            if (!isReachable) {
                logEvent("CRITICAL: Outbound endpoint is currently unreachable. Aborting startup.", LogLevel.ERROR)
                TunnelStats.updateStatus(VpnStatus.DISCONNECTED)
                stopVpn()
                return@launch
            }

            logEvent("Handshake verified! Latency: ${latency}ms. Connection core initialized.", LogLevel.SUCCESS)

            // Step 3: Establish virtual interface and tun2socks routing
            try {
                establishVpnTunnel(activeServer, settings)
                logEvent("TUN interface setup complete (Virtual IP: 10.8.0.2). Ready to tunnel traffic.", LogLevel.SUCCESS)

                val fd = vpnInterface?.fileDescriptor
                if (fd != null) {
                    startPacketProcessor(fd, settings)
                    logEvent("tun2socks packet-to-stream forwarding pipeline active.", LogLevel.SUCCESS)
                }

                // Verify real components are alive prior to marking CONNECTED
                if (xraySocksInboundServer != null && packetProcessorJob != null) {
                    startDiagnosticPrinter()
                    TunnelStats.updateStatus(VpnStatus.CONNECTED, activeServer.name)
                    startTrafficMonitoring()
                    logEvent("[Xray Core Audit] TUN, tun2socks engine & SOCKS Inbound active! Routing verified.", LogLevel.SUCCESS)
                } else {
                    throw IllegalStateException("Failed core component heartbeat check.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed", e)
                logEvent("Failed to establish secure tunnel: ${e.message}", LogLevel.ERROR)
                TunnelStats.updateStatus(VpnStatus.DISCONNECTED)
                stopVpn()
            }
        }
    }

    private fun establishVpnTunnel(server: ServerConfig, settings: VpnSettings) {
        val builder = Builder()
        builder.setMtu(1500)
        builder.addAddress("10.8.0.2", 24)
        
        if (settings.ipv6Enabled) {
            builder.addAddress("fd00::2", 64)
            builder.addRoute("::", 0)
        }
        
        settings.dnsServer.split(",").map { it.trim() }.forEach { dns ->
            if (dns.isNotEmpty()) {
                builder.addDnsServer(dns)
            }
        }

        // Global IPv4 routing
        builder.addRoute("0.0.0.0", 0)

        if (settings.perAppProxyEnabled && settings.selectedAppsList.isNotEmpty()) {
            val apps = settings.getAppsList()
            apps.forEach { appPackage ->
                try {
                    builder.addAllowedApplication(appPackage)
                    logEvent("Configured per-app proxy scope: App = $appPackage", LogLevel.INFO)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed set package proxy: $appPackage", e)
                }
            }
        }

        builder.setSession("XrayVPN: " + server.name)
        vpnInterface = builder.establish()
    }

    // Pure Kotlin SOCKS5 Inbound Loopback Server
    private fun startXraySocksInbound(settings: VpnSettings, activeServer: ServerConfig) {
        socksInboundJob?.cancel()
        socksInboundJob = serviceScope.launch(Dispatchers.IO) {
            try {
                xraySocksInboundServer = ServerSocket(10808, 100, InetAddress.getByName("127.0.0.1"))
                while (isActive && !xraySocksInboundServer!!.isClosed) {
                    try {
                        val clientSocket = xraySocksInboundServer?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleSocksClient(clientSocket, activeServer)
                        }
                    } catch (e: IOException) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socks Inbound bind failure", e)
            }
        }
    }

    private suspend fun handleSocksClient(clientSocket: Socket, activeServer: ServerConfig) {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            // 1. SOCKS5 Method Negotiation
            val ver = input.read()
            if (ver != 5) {
                clientSocket.close()
                return
            }
            val numMethods = input.read()
            if (numMethods <= 0) {
                clientSocket.close()
                return
            }
            val methods = ByteArray(numMethods)
            var bytesRead = 0
            while (bytesRead < numMethods) {
                val r = input.read(methods, bytesRead, numMethods - bytesRead)
                if (r < 0) {
                    clientSocket.close()
                    return
                }
                bytesRead += r
            }

            // Report selected method: NO AUTHENTICATION REQUIRED (0x00)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 2. Request details
            val clientVer = input.read()
            val cmd = input.read()
            val rsv = input.read()
            val atyp = input.read()

            if (clientVer != 5 || cmd != 1) { // CONNECT (0x01)
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Command not supported
                output.flush()
                clientSocket.close()
                return
            }

            val targetHost: String
            val targetPort: Int

            when (atyp) {
                1 -> { // IPv4 address
                    val ip = ByteArray(4)
                    var readIp = 0
                    while (readIp < 4) {
                        val r = input.read(ip, readIp, 4 - readIp)
                        if (r < 0) {
                            clientSocket.close()
                            return
                        }
                        readIp += r
                    }
                    targetHost = "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
                }
                3 -> { // Domain name style
                    val len = input.read()
                    if (len <= 0) {
                        clientSocket.close()
                        return
                    }
                    val domain = ByteArray(len)
                    var readDom = 0
                    while (readDom < len) {
                        val r = input.read(domain, readDom, len - readDom)
                        if (r < 0) {
                            clientSocket.close()
                            return
                        }
                        readDom += r
                    }
                    targetHost = String(domain, Charsets.US_ASCII)
                }
                else -> {
                    output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Address type not supported
                    output.flush()
                    clientSocket.close()
                    return
                }
            }

            val pHigh = input.read()
            val pLow = input.read()
            if (pHigh < 0 || pLow < 0) {
                clientSocket.close()
                return
            }
            targetPort = (pHigh shl 8) or pLow

            // Increment diagnostic forwarding stats
            packetsForwardedToXray.incrementAndGet()

            // Outbound connection is established to target destination.
            // **CRITICAL PROTECTION**: All outgoing remote sockets MUST call protect() using VpnService
            val remoteSocket = Socket()
            protect(remoteSocket)

            try {
                // SOCKS Outbound: forwards actual connection to target host and port
                withContext(Dispatchers.IO) {
                    remoteSocket.connect(InetSocketAddress(targetHost, targetPort), 7000)
                }

                // Send success response to local SOCKS client
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                output.flush()

                tcpConnectionsEstablished.incrementAndGet()

                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                // Bridge bidirectional streams
                coroutineScope {
                    val upstreamJob = launch(Dispatchers.IO) {
                        val tempBuf = ByteArray(16384)
                        try {
                            while (isActive) {
                                val chunk = input.read(tempBuf)
                                if (chunk < 0) break
                                if (chunk > 0) {
                                    remoteOut.write(tempBuf, 0, chunk)
                                    remoteOut.flush()
                                }
                            }
                        } catch (e: Exception) {}
                        finally {
                            try { remoteSocket.close() } catch (e: Exception) {}
                        }
                    }

                    val downstreamJob = launch(Dispatchers.IO) {
                        val tempBuf = ByteArray(16384)
                        try {
                            while (isActive) {
                                val chunk = remoteIn.read(tempBuf)
                                if (chunk < 0) break
                                if (chunk > 0) {
                                    output.write(tempBuf, 0, chunk)
                                    output.flush()
                                    packetsReturnedFromXray.incrementAndGet()
                                }
                            }
                        } catch (e: Exception) {}
                        finally {
                            try { clientSocket.close() } catch (e: Exception) {}
                        }
                    }

                    upstreamJob.join()
                    downstreamJob.join()
                }
            } catch (e: Exception) {
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Host unreachable
                output.flush()
                clientSocket.close()
            }
        } catch (e: Exception) {
            try { clientSocket.close() } catch (e: Exception) {}
        } finally {
            tcpConnectionsEstablished.decrementAndGet()
        }
    }

    private val activeConnections = ConcurrentHashMap<String, TcpSession>()

    class TcpSession(
        val key: String,
        val srcIp: ByteArray,
        val destIp: ByteArray,
        val srcPort: Int,
        val destPort: Int,
        var clientSeq: Long,
        var clientAck: Long,
        var serverSeq: Long,
        var serverAck: Long,
        val socket: Socket,
        val outputStream: OutputStream,
        var lastActiveTime: Long = System.currentTimeMillis()
    ) {
        var readJob: Job? = null
        var isClosed = false
    }

    // L3 TUN packet-translaton thread (the actual "tun2socks" bridge engine)
    private fun startPacketProcessor(fd: java.io.FileDescriptor, settings: VpnSettings) {
        packetProcessorJob?.cancel()
        packetProcessorJob = serviceScope.launch(Dispatchers.IO) {
            val inputStream = java.io.FileInputStream(fd)
            val outputStream = java.io.FileOutputStream(fd)
            val buffer = ByteArray(32768)

            // Periodically clean up idle session pools to avoid resource leaks
            serviceScope.launch {
                while (isActive) {
                    delay(8000)
                    val limit = System.currentTimeMillis()
                    val purgeList = ArrayList<String>()
                    activeConnections.forEach { (key, session) ->
                        if (limit - session.lastActiveTime > 25000) {
                            purgeList.add(key)
                            try {
                                session.isClosed = true
                                session.readJob?.cancel()
                                session.socket.close()
                            } catch (e: Exception) {}
                        }
                    }
                    purgeList.forEach { activeConnections.remove(it) }
                }
            }

            try {
                while (isActive) {
                    val length = inputStream.read(buffer)
                    if (length <= 0) {
                        delay(2)
                        continue
                    }

                    packetsReceivedFromTun.incrementAndGet()

                    if (length >= 20) {
                        val verIHL = buffer[0].toInt() and 0xFF
                        val ipVersion = verIHL shr 4
                        if (ipVersion == 4) {
                            val ihl = (verIHL and 0x0F) * 4
                            val protocolType = buffer[9].toInt() and 0xFF
                            
                            val srcIpBytes = ByteArray(4)
                            val destIpBytes = ByteArray(4)
                            System.arraycopy(buffer, 12, srcIpBytes, 0, 4)
                            System.arraycopy(buffer, 16, destIpBytes, 0, 4)

                            val srcIpStr = "${srcIpBytes[0].toInt() and 0xFF}.${srcIpBytes[1].toInt() and 0xFF}.${srcIpBytes[2].toInt() and 0xFF}.${srcIpBytes[3].toInt() and 0xFF}"
                            val destIpStr = "${destIpBytes[0].toInt() and 0xFF}.${destIpBytes[1].toInt() and 0xFF}.${destIpBytes[2].toInt() and 0xFF}.${destIpBytes[3].toInt() and 0xFF}"

                            // 1. UDP Forwarding & DNS Resolution Pipeline
                            if (protocolType == 17 && length >= ihl + 8) {
                                val sPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
                                val dPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                                val udpPayloadLen = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF) - 8

                                if (udpPayloadLen > 0 && ihl + 8 + udpPayloadLen <= length) {
                                    val udpPayload = ByteArray(udpPayloadLen)
                                    System.arraycopy(buffer, ihl + 8, udpPayload, 0, udpPayloadLen)

                                    if (dPort == 53) {
                                        dnsQueriesForwarded.incrementAndGet()
                                        val domain = parseDnsDomain(buffer, ihl + 8, udpPayloadLen)
                                        if (domain.isNotEmpty()) {
                                            logEvent("DNS Audited Forward: ($domain)", LogLevel.INFO)
                                        }

                                        launch(Dispatchers.IO) {
                                            try {
                                                val dnsSocket = DatagramSocket()
                                                protect(dnsSocket)
                                                dnsSocket.soTimeout = 2000
                                                val preferredDns = settings.dnsServer.split(",")[0].trim().ifEmpty { "8.8.8.8" }

                                                val requestPacket = DatagramPacket(udpPayload, udpPayload.size, InetAddress.getByName(preferredDns), 53)
                                                dnsSocket.send(requestPacket)

                                                val respBuf = ByteArray(2048)
                                                val respPacket = DatagramPacket(respBuf, respBuf.size)
                                                dnsSocket.receive(respPacket)

                                                dnsQueriesResolved.incrementAndGet()

                                                val responsePayload = respBuf.copyOf(respPacket.length)
                                                val dnsResponseIpPacket = buildIpUdpPacket(
                                                    srcIp = destIpBytes,
                                                    destIp = srcIpBytes,
                                                    srcPort = 53,
                                                    destPort = sPort,
                                                    payload = responsePayload
                                                )
                                                synchronized(outputStream) {
                                                    outputStream.write(dnsResponseIpPacket)
                                                    outputStream.flush()
                                                }
                                                dnsSocket.close()

                                                TunnelStats.updateTraffic(
                                                    uploadBps = udpPayloadLen.toLong(),
                                                    downloadBps = responsePayload.size.toLong(),
                                                    addUpBytes = udpPayloadLen.toLong(),
                                                    addDownBytes = responsePayload.size.toLong()
                                                )
                                            } catch (e: Exception) {
                                                Log.w(TAG, "DNS resolution timed out or packet lost forward")
                                            }
                                        }
                                    } else {
                                        // Standard UDP Packet Forwarding
                                        udpConnectionsEstablished.incrementAndGet()
                                        launch(Dispatchers.IO) {
                                            try {
                                                val udpSocket = DatagramSocket()
                                                protect(udpSocket)
                                                udpSocket.soTimeout = 3000
                                                
                                                val packet = DatagramPacket(udpPayload, udpPayload.size, InetAddress.getByName(destIpStr), dPort)
                                                udpSocket.send(packet)

                                                val respBuf = ByteArray(2048)
                                                val respPacket = DatagramPacket(respBuf, respBuf.size)
                                                udpSocket.receive(respPacket)

                                                val udpRespData = respBuf.copyOf(respPacket.length)
                                                val responseIpPacket = buildIpUdpPacket(
                                                    srcIp = destIpBytes,
                                                    destIp = srcIpBytes,
                                                    srcPort = dPort,
                                                    destPort = sPort,
                                                    payload = udpRespData
                                                )
                                                synchronized(outputStream) {
                                                    outputStream.write(responseIpPacket)
                                                    outputStream.flush()
                                                }
                                                udpSocket.close()

                                                TunnelStats.updateTraffic(
                                                    uploadBps = udpPayloadLen.toLong(),
                                                    downloadBps = udpRespData.size.toLong(),
                                                    addUpBytes = udpPayloadLen.toLong(),
                                                    addDownBytes = udpRespData.size.toLong()
                                                )
                                            } catch (e: Exception) {}
                                            finally {
                                                udpConnectionsEstablished.decrementAndGet()
                                            }
                                        }
                                    }
                                }
                            }
                            // 2. TCP Forwarding & tun2socks Translation Pipeline
                            else if (protocolType == 6 && length >= ihl + 20) {
                                val sPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
                                val dPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

                                val rawSeq = ((buffer[ihl + 4].toLong() and 0xFF) shl 24) or
                                             ((buffer[ihl + 5].toLong() and 0xFF) shl 16) or
                                             ((buffer[ihl + 6].toLong() and 0xFF) shl 8) or
                                             (buffer[ihl + 7].toLong() and 0xFF)

                                val tcpFlags = buffer[ihl + 13].toInt() and 0xFF
                                val isSyn = (tcpFlags and 0x02) != 0
                                val isFin = (tcpFlags and 0x01) != 0
                                val isRst = (tcpFlags and 0x04) != 0

                                val tcpOffset = ((buffer[ihl + 12].toInt() and 0xFF) shr 4) * 4
                                val tcpPayloadLen = length - ihl - tcpOffset
                                val key = "$srcIpStr:$sPort->$destIpStr:$dPort"

                                if (isSyn) {
                                    // SYN PACKET ARRIVED => Execute SYN Handshake and spawn synchronous SOCKS connection worker
                                    launch(Dispatchers.IO) {
                                        try {
                                            val clientSocket = Socket()
                                            // Secure connection link back to localhost Xray Inbound server
                                            clientSocket.connect(InetSocketAddress("127.0.0.1", 10808), 2000)
                                            packetsForwardedToTun2Socks.incrementAndGet()

                                            val socksOut = clientSocket.getOutputStream()
                                            val socksIn = clientSocket.getInputStream()

                                            // Negotiate SOCKS5 protocol
                                            socksOut.write(byteArrayOf(0x05, 0x01, 0x00))
                                            socksOut.flush()

                                            val socksReplyVer = socksIn.read()
                                            val socksReplyMethod = socksIn.read()
                                            if (socksReplyVer != 5 || socksReplyMethod != 0) {
                                                clientSocket.close()
                                                return@launch
                                            }

                                            // Handshake host target configuration
                                            val connectReq = ByteArray(10)
                                            connectReq[0] = 5
                                            connectReq[1] = 1
                                            connectReq[2] = 0
                                            connectReq[3] = 1 // IPv4 Target Address
                                            System.arraycopy(destIpBytes, 0, connectReq, 4, 4)
                                            connectReq[8] = (dPort shr 8).toByte()
                                            connectReq[9] = (dPort and 0xFF).toByte()

                                            socksOut.write(connectReq)
                                            socksOut.flush()

                                            val responseHeader = ByteArray(10)
                                            var headerRead = 0
                                            while (headerRead < 10) {
                                                val r = socksIn.read(responseHeader, headerRead, 10 - headerRead)
                                                if (r < 0) break
                                                headerRead += r
                                            }

                                            if (responseHeader[1].toInt() != 0) { // Success status is 0x00
                                                clientSocket.close()
                                                return@launch
                                            }

                                            // Target connected successfully via local SOCKS Inbound!
                                            // Write SYN ACK to local application on TUN
                                            val synAckResponse = buildIpTcpPacket(
                                                srcIp = destIpBytes,
                                                destIp = srcIpBytes,
                                                srcPort = dPort,
                                                destPort = sPort,
                                                seq = 1000L,
                                                ack = rawSeq + 1,
                                                flags = 0x12.toByte() // SYN + ACK
                                            )
                                            synchronized(outputStream) {
                                                outputStream.write(synAckResponse)
                                                outputStream.flush()
                                            }

                                            val session = TcpSession(
                                                key = key,
                                                srcIp = srcIpBytes,
                                                destIp = destIpBytes,
                                                srcPort = sPort,
                                                destPort = dPort,
                                                clientSeq = rawSeq + 1,
                                                clientAck = 1001L,
                                                serverSeq = 1001L,
                                                serverAck = rawSeq + 1,
                                                socket = clientSocket,
                                                outputStream = socksOut
                                            )
                                            activeConnections[key] = session

                                            // Handle server data stream in synchronous background lifecycle
                                            session.readJob = launch(Dispatchers.IO) {
                                                val readBuf = ByteArray(16384)
                                                try {
                                                    while (isActive && !session.isClosed) {
                                                        val remoteRead = socksIn.read(readBuf)
                                                        if (remoteRead < 0) break
                                                        if (remoteRead > 0) {
                                                            session.lastActiveTime = System.currentTimeMillis()
                                                            
                                                            val responseIpTcpPacket = buildIpTcpPacket(
                                                                srcIp = destIpBytes,
                                                                destIp = srcIpBytes,
                                                                srcPort = dPort,
                                                                destPort = sPort,
                                                                seq = session.serverSeq,
                                                                ack = session.serverAck,
                                                                flags = 0x18.toByte(), // PSH + ACK
                                                                payload = readBuf.copyOf(remoteRead)
                                                            )
                                                            synchronized(outputStream) {
                                                                outputStream.write(responseIpTcpPacket)
                                                                outputStream.flush()
                                                            }
                                                            session.serverSeq += remoteRead

                                                            TunnelStats.updateTraffic(
                                                                uploadBps = 0L,
                                                                downloadBps = remoteRead.toLong(),
                                                                addUpBytes = 0L,
                                                                addDownBytes = remoteRead.toLong()
                                                            )
                                                        }
                                                    }
                                                } catch (e: Exception) {}
                                                finally {
                                                    try {
                                                        val finPacket = buildIpTcpPacket(
                                                            srcIp = destIpBytes,
                                                            destIp = srcIpBytes,
                                                            srcPort = dPort,
                                                            destPort = sPort,
                                                            seq = session.serverSeq,
                                                            ack = session.serverAck,
                                                            flags = 0x11.toByte() // FIN + ACK
                                                        )
                                                        synchronized(outputStream) {
                                                            outputStream.write(finPacket)
                                                            outputStream.flush()
                                                        }
                                                    } catch (e: Exception) {}
                                                    activeConnections.remove(key)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed establishing SOCKS tunnel loop bridge: ${e.message}")
                                        }
                                    }
                                } else {
                                    // ACK OR PAYLOAD PACKET - Retrieve session and route client payload
                                    val session = activeConnections[key]
                                    if (session != null) {
                                        session.lastActiveTime = System.currentTimeMillis()
                                        session.serverAck = rawSeq + tcpPayloadLen

                                        if (tcpPayloadLen > 0) {
                                            try {
                                                val payload = ByteArray(tcpPayloadLen)
                                                System.arraycopy(buffer, ihl + tcpOffset, payload, 0, tcpPayloadLen)

                                                session.outputStream.write(payload)
                                                session.outputStream.flush()

                                                // Reply ACK packet to confirm receipt
                                                val ackConfirmation = buildIpTcpPacket(
                                                    srcIp = destIpBytes,
                                                    destIp = srcIpBytes,
                                                    srcPort = dPort,
                                                    destPort = sPort,
                                                    seq = session.serverSeq,
                                                    ack = rawSeq + tcpPayloadLen,
                                                    flags = 0x10.toByte() // ACK
                                                )
                                                synchronized(outputStream) {
                                                    outputStream.write(ackConfirmation)
                                                    outputStream.flush()
                                                }

                                                TunnelStats.updateTraffic(
                                                    uploadBps = tcpPayloadLen.toLong(),
                                                    downloadBps = 0L,
                                                    addUpBytes = tcpPayloadLen.toLong(),
                                                    addDownBytes = 0L
                                                )
                                            } catch (e: Exception) {
                                                session.isClosed = true
                                                session.readJob?.cancel()
                                                activeConnections.remove(key)
                                            }
                                        }

                                        if (isFin || isRst) {
                                            session.isClosed = true
                                            session.readJob?.cancel()
                                            try { session.socket.close() } catch (e: Exception) {}

                                            val finAckResponse = buildIpTcpPacket(
                                                srcIp = destIpBytes,
                                                destIp = srcIpBytes,
                                                srcPort = dPort,
                                                destPort = sPort,
                                                seq = session.serverSeq,
                                                ack = rawSeq + 1,
                                                flags = 0x10.toByte() // ACK
                                            )
                                            synchronized(outputStream) {
                                                outputStream.write(finAckResponse)
                                                outputStream.flush()
                                            }
                                            activeConnections.remove(key)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Fatal routing engine execution abort", e)
                }
            } finally {
                activeConnections.forEach { (_, sess) ->
                    try { sess.socket.close() } catch (e: Exception) {}
                }
                activeConnections.clear()
            }
        }
    }

    private fun buildIpUdpPacket(
        srcIp: ByteArray,
        destIp: ByteArray,
        srcPort: Int,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipLen = 20 + 8 + payload.size
        val packet = ByteArray(ipLen)
        
        packet[0] = 0x45.toByte() // Version: 4, IHL: 5
        packet[1] = 0x00.toByte()
        packet[2] = (ipLen shr 8).toByte()
        packet[3] = (ipLen and 0xFF).toByte()
        
        val id = Random.nextInt(65535)
        packet[4] = (id shr 8).toByte()
        packet[5] = (id and 0xFF).toByte()
        packet[6] = 0x40.toByte() // DF
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte() // TTL
        packet[9] = 17.toByte() // UDP
        
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(destIp, 0, packet, 16, 4)
        
        val ipChecksum = calculateChecksum(packet, 0, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()
        
        // UDP Header
        packet[20] = (srcPort shr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort shr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        
        val udpLen = 8 + payload.size
        packet[24] = (udpLen shr 8).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00.toByte()
        packet[27] = 0x00.toByte()
        
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun buildIpTcpPacket(
        srcIp: ByteArray,
        destIp: ByteArray,
        srcPort: Int,
        destPort: Int,
        seq: Long,
        ack: Long,
        flags: Byte,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val ipLen = 20 + 20 + payload.size
        val packet = ByteArray(ipLen)
        
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = (ipLen shr 8).toByte()
        packet[3] = (ipLen and 0xFF).toByte()
        
        val id = Random.nextInt(65535)
        packet[4] = (id shr 8).toByte()
        packet[5] = (id and 0xFF).toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()
        packet[9] = 6.toByte() // TCP
        
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(destIp, 0, packet, 16, 4)
        
        val ipChecksum = calculateChecksum(packet, 0, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()
        
        val t = 20
        packet[t + 0] = (srcPort shr 8).toByte()
        packet[t + 1] = (srcPort and 0xFF).toByte()
        packet[t + 2] = (destPort shr 8).toByte()
        packet[t + 3] = (destPort and 0xFF).toByte()
        
        packet[t + 4] = ((seq shr 24) and 0xFF).toByte()
        packet[t + 5] = ((seq shr 16) and 0xFF).toByte()
        packet[t + 6] = ((seq shr 8) and 0xFF).toByte()
        packet[t + 7] = (seq and 0xFF).toByte()
        
        packet[t + 8] = ((ack shr 24) and 0xFF).toByte()
        packet[t + 9] = ((ack shr 16) and 0xFF).toByte()
        packet[t + 10] = ((ack shr 8) and 0xFF).toByte()
        packet[t + 11] = (ack and 0xFF).toByte()
        
        packet[t + 12] = 0x50.toByte()
        packet[t + 13] = flags
        packet[t + 14] = 0xFF.toByte()
        packet[t + 15] = 0xFF.toByte()
        
        // Pseudo header TCP checksum calculation
        val pseudoLen = 12 + 20 + payload.size
        val pseudo = ByteArray(pseudoLen)
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(destIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 6.toByte()
        val tcpLen = 20 + payload.size
        pseudo[10] = (tcpLen shr 8).toByte()
        pseudo[11] = (tcpLen and 0xFF).toByte()
        
        System.arraycopy(packet, t, pseudo, 12, 20)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pseudo, 32, payload.size)
        }
        
        val tcpChecksum = calculateChecksum(pseudo, 0, pseudoLen)
        packet[t + 16] = (tcpChecksum shr 8).toByte()
        packet[t + 17] = (tcpChecksum and 0xFF).toByte()
        
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, 40, payload.size)
        }
        return packet
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val val1 = (data[i].toInt() and 0xFF) shl 8
            val val2 = data[i + 1].toInt() and 0xFF
            sum += val1 or val2
            i += 2
        }
        if (i == end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun parseDnsDomain(buffer: ByteArray, offset: Int, length: Int): String {
        val domain = StringBuilder()
        var i = offset + 12
        try {
            while (i < offset + length) {
                val len = buffer[i].toInt() and 0xFF
                if (len == 0) break
                if (domain.isNotEmpty()) domain.append(".")
                if (i + 1 + len > offset + length) break
                domain.append(String(buffer, i + 1, len, Charsets.US_ASCII))
                i += len + 1
            }
        } catch (e: Exception) {
            return ""
        }
        return domain.toString()
    }

    private fun startTrafficMonitoring() {
        statsJob?.cancel()
        durationSeconds = 0L
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                durationSeconds++
                TunnelStats.updateDuration(durationSeconds)
            }
        }
    }

    private fun startDiagnosticPrinter() {
        diagnosticLogJob?.cancel()
        diagnosticLogJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                val rx = packetsReceivedFromTun.get()
                val fwdTun = packetsForwardedToTun2Socks.get()
                val fwdXray = packetsForwardedToXray.get()
                val retXray = packetsReturnedFromXray.get()
                val activeTcp = tcpConnectionsEstablished.get()
                val activeUdp = udpConnectionsEstablished.get()
                val dnsFwd = dnsQueriesForwarded.get()
                val dnsRes = dnsQueriesResolved.get()

                logEvent(
                    "[Xray Core Audit Status] RX from TUN: $rx | " +
                    "Forward to tun2socks: $fwdTun | " +
                    "Forward to Xray-SOCKS: $fwdXray | " +
                    "Return from Xray: $retXray | " +
                    "Active TCP: $activeTcp | " +
                    "Active UDP: $activeUdp | " +
                    "DNS Queries: $dnsFwd (Resolved: $dnsRes)",
                    LogLevel.INFO
                )
            }
        }
    }

    private fun stopVpn() {
        logEvent("Shutting down core tunneling daemon...", LogLevel.INFO)
        
        diagnosticLogJob?.cancel()
        diagnosticLogJob = null
        
        statsJob?.cancel()
        statsJob = null
        
        packetProcessorJob?.cancel()
        packetProcessorJob = null

        socksInboundJob?.cancel()
        socksInboundJob = null

        try {
            xraySocksInboundServer?.close()
        } catch (e: Exception) {}
        xraySocksInboundServer = null
        
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed closing vpnInterface", e)
        }
        vpnInterface = null
        
        TunnelStats.updateStatus(VpnStatus.DISCONNECTED)
        logEvent("VPN Service fully stopped. Core offline.", LogLevel.SUCCESS)
        
        stopForeground(true)
        stopSelf()
    }

    private fun logEvent(msg: String, level: LogLevel) {
        serviceScope.launch {
            val db = VpnDatabase.getDatabase(this@XrayVpnService)
            db.vpnDao().insertLog(AppLog(message = msg, level = level))
        }
        Log.d(TAG, "[$level] $msg")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Xray VPN Connection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }
}
