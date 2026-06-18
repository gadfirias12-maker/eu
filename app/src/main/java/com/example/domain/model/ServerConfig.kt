package com.example.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

enum class ProxyProtocol {
    VMESS, VLESS, TROJAN, SHADOWSOCKS, SOCKS
}

@Entity(tableName = "servers")
data class ServerConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: ProxyProtocol,
    val address: String,
    val port: Int,
    
    // Auth
    val uuid: String = "",       // VMess & VLESS
    val password: String = "",   // Trojan & Shadowsocks
    val alterId: Int = 0,        // VMess
    
    // Transport & Security
    val security: String = "none",  // none, tls, xtls
    val network: String = "tcp",   // tcp, ws, grpc
    val sni: String = "",
    val path: String = "",         // websocket path or grpc service name
    
    // Metadata
    val subscriptionId: Long? = null,
    val latency: Long = -1,        // -1 means unchecked
    val isSelected: Boolean = false
) : Serializable {
    
    fun getSummary(): String {
        return "$protocol | $address:$port"
    }

    /**
     * Converts configuration into standard v2ray/xray URI format for export / sharing.
     */
    fun toUri(): String {
        return when (protocol) {
            ProxyProtocol.VMESS -> {
                // Simplified VMess scheme
                "vmess://${address}:$port?uuid=$uuid&network=$network"
            }
            ProxyProtocol.VLESS -> {
                "vless://$uuid@$address:$port?security=$security&sni=$sni&type=$network#${UriEncoder.encode(name)}"
            }
            ProxyProtocol.TROJAN -> {
                "trojan://$password@$address:$port?sni=$sni#${UriEncoder.encode(name)}"
            }
            ProxyProtocol.SHADOWSOCKS -> {
                "ss://$password@$address:$port#${UriEncoder.encode(name)}"
            }
            ProxyProtocol.SOCKS -> {
                "socks://$address:$port#${UriEncoder.encode(name)}"
            }
        }
    }
    companion object {
        fun fromUri(rawUri: String): ServerConfig? {
            try {
                val uri = rawUri.trim()
                if (uri.startsWith("vmess://")) {
                    val content = uri.substring(8)
                    // VMess can be base64-encoded JSON or a plain query string. Let's support both!
                    if (content.contains("@") || content.contains(":")) {
                        val urlStr = "http://" + content // convert to URL-like structure for parsing
                        val url = java.net.URL(urlStr)
                        val name = url.ref?.let { UriEncoder.decode(it) } ?: "VMess Server"
                        val userInfo = url.userInfo ?: ""
                        val address = url.host ?: ""
                        val port = if (url.port != -1) url.port else 1080
                        
                        val queries = url.query?.split("&")?.associate {
                            val parts = it.split("=")
                            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                        } ?: emptyMap()
                        
                        return ServerConfig(
                            name = name,
                            protocol = ProxyProtocol.VMESS,
                            address = address,
                            port = port,
                            uuid = userInfo,
                            network = queries["network"] ?: queries["type"] ?: "tcp",
                            security = queries["security"] ?: "none"
                        )
                    } else {
                        // Base64 encoded JSON
                        val decodedBytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                        val decodedStr = String(decodedBytes, Charsets.UTF_8)
                        
                        val json = org.json.JSONObject(decodedStr)
                        val address = json.optString("add", "")
                        val port = json.optInt("port", 1080)
                        val uuid = json.optString("id", "")
                        val ps = json.optString("ps", "VMess Import")
                        val net = json.optString("net", "tcp")
                        val tls = json.optString("tls", "none")
                        
                        return ServerConfig(
                            name = ps,
                            protocol = ProxyProtocol.VMESS,
                            address = address,
                            port = port,
                            uuid = uuid,
                            network = net,
                            security = tls
                        )
                    }
                }
                
                val protocol = when {
                    uri.startsWith("vless://") -> ProxyProtocol.VLESS
                    uri.startsWith("trojan://") -> ProxyProtocol.TROJAN
                    uri.startsWith("ss://") -> ProxyProtocol.SHADOWSOCKS
                    uri.startsWith("socks://") -> ProxyProtocol.SOCKS
                    else -> return null
                }
                
                val schemeLength = when (protocol) {
                    ProxyProtocol.VLESS -> 8
                    ProxyProtocol.TROJAN -> 9
                    ProxyProtocol.SHADOWSOCKS -> 5
                    ProxyProtocol.SOCKS -> 8
                    else -> 0
                }
                
                val withoutScheme = uri.substring(schemeLength)
                val hashParts = withoutScheme.split("#")
                val mainPart = hashParts[0]
                val rawName = if (hashParts.size > 1) hashParts[1] else "${protocol.name} Config"
                val name = UriEncoder.decode(rawName)
                
                val queryParts = mainPart.split("?")
                val connectionPart = queryParts[0]
                val queryString = if (queryParts.size > 1) queryParts[1] else ""
                
                val queries = if (queryString.isNotEmpty()) {
                    queryString.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                } else emptyMap()
                
                val credParts = connectionPart.split("@")
                val cred = if (credParts.size > 1) credParts[0] else ""
                val addrPort = if (credParts.size > 1) credParts[1] else credParts[0]
                
                val addrParts = addrPort.split(":")
                val address = addrParts[0]
                val port = if (addrParts.size > 1) addrParts[1].toIntOrNull() ?: 1080 else 1080
                
                return ServerConfig(
                    name = name,
                    protocol = protocol,
                    address = address,
                    port = port,
                    uuid = if (protocol == ProxyProtocol.VLESS) cred else "",
                    password = if (protocol == ProxyProtocol.TROJAN || protocol == ProxyProtocol.SHADOWSOCKS) cred else "",
                    security = queries["security"] ?: "none",
                    network = queries["type"] ?: queries["net"] ?: "tcp",
                    sni = queries["sni"] ?: "",
                    path = queries["path"] ?: ""
                )
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
    }
}

/**
 * Super simple URI encoder since java.net.URLEncoder is standard but can require try/catches.
 */
object UriEncoder {
    fun encode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8")
    }
    fun decode(s: String): String {
        return java.net.URLDecoder.decode(s, "UTF-8")
    }
}
