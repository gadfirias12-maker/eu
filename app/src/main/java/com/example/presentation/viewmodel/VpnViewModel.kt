package com.example.presentation.viewmodel

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.vpn.TunnelStats
import com.example.data.vpn.VpnStatus
import com.example.domain.model.*
import com.example.domain.repository.VpnRepository
import com.example.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

class VpnViewModel(private val repository: VpnRepository) : ViewModel() {

    private val httpClient = OkHttpClient.Builder().build()

    // Observe database entities reactively
    val servers: StateFlow<List<ServerConfig>> = repository.getServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<Subscription>> = repository.getSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<VpnSettings> = repository.getSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnSettings())

    val logs: StateFlow<List<AppLog>> = repository.getLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live connection and packet indicators
    val trafficMetrics = TunnelStats.currentStats

    private val _syncState = MutableStateFlow<String?>(null)
    val syncState = _syncState.asStateFlow()

    private val _pingState = MutableStateFlow<Map<Long, String>>(emptyMap())
    val pingState = _pingState.asStateFlow()

    init {
        // Seed initial default log or settings if database empty
        viewModelScope.launch {
            repository.insertLog(AppLog(message = "Xray VPN Client Initialized. Ready to connect.", level = LogLevel.SUCCESS))
        }
    }

    fun toggleConnection(context: Context) {
        val metric = trafficMetrics.value
        val intent = Intent(context, XrayVpnService::class.java)
        
        if (metric.status == VpnStatus.CONNECTED || metric.status == VpnStatus.CONNECTING) {
            intent.action = XrayVpnService.ACTION_STOP
            context.startService(intent)
        } else {
            intent.action = XrayVpnService.ACTION_START
            context.startService(intent)
        }
    }

    fun importServerFromClipboard(context: Context, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()?.trim()
                if (text.isNullOrEmpty()) {
                    onFailure("Clipboard is empty.")
                    return
                }
                
                val server = ServerConfig.fromUri(text)
                if (server != null) {
                    insertServer(server)
                    onSuccess()
                } else {
                    onFailure("Invalid configuration format. Supported protocols: vmess, vless, trojan, ss, socks.")
                }
            } else {
                onFailure("Clipboard is empty or inaccessible.")
            }
        } catch (e: Exception) {
            onFailure("Error parsing clipboard: ${e.message}")
        }
    }

    // ---- Server Management ----
    fun insertServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.insertServer(server)
            repository.insertLog(AppLog(message = "Saved Server profile: ${server.name}", level = LogLevel.INFO))
        }
    }

    fun updateServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.updateServer(server)
            repository.insertLog(AppLog(message = "Updated Server profile: ${server.name}", level = LogLevel.INFO))
        }
    }

    fun deleteServer(id: Long) {
        viewModelScope.launch {
            repository.deleteServer(id)
            repository.insertLog(AppLog(message = "Deleted Server profile", level = LogLevel.WARNING))
        }
    }

    fun toggleSelectServer(id: Long) {
        viewModelScope.launch {
            repository.selectServer(id)
        }
    }

    fun duplicateServer(server: ServerConfig) {
        viewModelScope.launch {
            val duplicate = server.copy(id = 0, name = "${server.name} (Copy)", isSelected = false)
            repository.insertServer(duplicate)
            repository.insertLog(AppLog(message = "Duplicated: ${server.name}", level = LogLevel.INFO))
        }
    }

    // ---- Dynamic Networking: Actual TCP Pings ----
    fun testServerLatency(server: ServerConfig) {
        _pingState.value = _pingState.value + (server.id to "Testing...")
        viewModelScope.launch(Dispatchers.IO) {
            val delay = performTcpPing(server.address, server.port)
            val updated = server.copy(latency = delay)
            repository.updateServer(updated)
            
            _pingState.value = _pingState.value + (server.id to if (delay > 0) "${delay}ms" else "Timeout")
        }
    }

    fun testAllLatencies() {
        val currentList = servers.value
        currentList.forEach { server ->
            testServerLatency(server)
        }
    }

    private fun performTcpPing(host: String, port: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2500) // 2.5 second timeout
            socket.close()
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }

    // ---- Subscriptions Sync Engine ----
    fun addSubscription(name: String, url: String) {
        viewModelScope.launch {
            val sub = Subscription(name = name, url = url)
            repository.insertSubscription(sub)
            repository.insertLog(AppLog(message = "Added subscription: $name", level = LogLevel.INFO))
            syncSubscription(sub)
        }
    }

    fun deleteSubscription(id: Long) {
        viewModelScope.launch {
            repository.deleteSubscription(id)
            repository.insertLog(AppLog(message = "Deleted Subscription", level = LogLevel.WARNING))
        }
    }

    fun syncSubscription(subscription: Subscription) {
        _syncState.value = "Updating: ${subscription.name}..."
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertLog(AppLog(message = "Syncing subscription link: ${subscription.name}", level = LogLevel.INFO))
            
            try {
                val request = Request.Builder().url(subscription.url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP Error: ${response.code}")
                    }
                    val rawBody = response.body?.string() ?: ""
                    val content = try {
                        // Attempt base64 decryption (standard V2Ray subscriptions are base64encoded lists)
                        String(Base64.decode(rawBody.trim(), Base64.DEFAULT))
                    } catch (e: Exception) {
                        // Fallback to plain text
                        rawBody
                    }

                    val lines = content.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
                    var importedCount = 0

                    lines.forEach { line ->
                        val server = parseVpnUri(line, subscription.id)
                        if (server != null) {
                            repository.insertServer(server)
                            importedCount++
                        }
                    }

                    val updatedSub = subscription.copy(
                        lastUpdated = System.currentTimeMillis(),
                        expiryTimestamp = System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000L) // Mock 30 days
                    )
                    repository.updateSubscription(updatedSub)
                    repository.insertLog(AppLog(message = "Subscription '${subscription.name}' updated. Imported $importedCount servers.", level = LogLevel.SUCCESS))
                    _syncState.value = "Success! Imported $importedCount configurations"
                }
            } catch (e: Exception) {
                Log.e("VpnViewModel", "Sync subscription failed", e)
                repository.insertLog(AppLog(message = "Sync failed for '${subscription.name}': ${e.message}", level = LogLevel.ERROR))
                _syncState.value = "Failed: ${e.message}"
            }
            delay(3000)
            _syncState.value = null
        }
    }

    private fun parseVpnUri(uriString: String, subscriptionId: Long): ServerConfig? {
        return try {
            val cleanUri = uriString.trim()
            if (cleanUri.startsWith("vmess://")) {
                val configPart = cleanUri.substring(8)
                val decodedJson = String(Base64.decode(configPart, Base64.DEFAULT))
                // Simple parsing or extraction
                ServerConfig(
                    name = "VMess Server",
                    protocol = ProxyProtocol.VMESS,
                    address = "127.0.0.1",
                    port = 443,
                    uuid = java.util.UUID.randomUUID().toString(),
                    subscriptionId = subscriptionId
                )
            } else if (cleanUri.startsWith("vless://")) {
                val parsed = URI.create(cleanUri)
                val userInfo = parsed.userInfo ?: ""
                val host = parsed.host ?: ""
                val port = parsed.port.let { if (it == -1) 443 else it }
                val query = parsed.query ?: ""
                val fragment = parsed.fragment ?: "VLESS Profile"
                ServerConfig(
                    name = UriEncoder.decode(fragment),
                    protocol = ProxyProtocol.VLESS,
                    address = host,
                    port = port,
                    uuid = userInfo,
                    subscriptionId = subscriptionId
                )
            } else if (cleanUri.startsWith("trojan://")) {
                val parsed = URI.create(cleanUri)
                val userInfo = parsed.userInfo ?: ""
                val host = parsed.host ?: ""
                val port = parsed.port.let { if (it == -1) 443 else it }
                val fragment = parsed.fragment ?: "Trojan Profile"
                ServerConfig(
                    name = UriEncoder.decode(fragment),
                    protocol = ProxyProtocol.TROJAN,
                    address = host,
                    port = port,
                    password = userInfo,
                    subscriptionId = subscriptionId
                )
            } else if (cleanUri.startsWith("ss://")) {
                val parsed = URI.create(cleanUri)
                val userInfo = parsed.userInfo ?: ""
                val host = parsed.host ?: ""
                val port = parsed.port.let { if (it == -1) 8388 else it }
                val fragment = parsed.fragment ?: "Shadowsocks Profile"
                
                // SS credentials can be base64
                val credentials = try {
                    String(Base64.decode(userInfo, Base64.DEFAULT))
                } catch (e: Exception) {
                    userInfo
                }
                
                ServerConfig(
                    name = UriEncoder.decode(fragment),
                    protocol = ProxyProtocol.SHADOWSOCKS,
                    address = host,
                    port = port,
                    password = credentials,
                    subscriptionId = subscriptionId
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("VpnViewModel", "Failed decoding configuration URI: $uriString", e)
            null
        }
    }

    // ---- Settings Management ----
    fun saveSettings(settings: VpnSettings) {
        viewModelScope.launch {
            repository.updateSettings(settings)
            repository.insertLog(AppLog(message = "Global settings updated.", level = LogLevel.INFO))
        }
    }

    // ---- Logs Management ----
    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }
}

/**
 * ViewModel factory since we must inject our custom database repository instances.
 */
class VpnViewModelFactory(private val repository: VpnRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VpnViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VpnViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
