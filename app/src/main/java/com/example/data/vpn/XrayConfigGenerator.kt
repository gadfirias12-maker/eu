package com.example.data.vpn

import com.example.domain.model.ProxyProtocol
import com.example.domain.model.ServerConfig
import com.example.domain.model.VpnSettings
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigGenerator {

    /**
     * Creates a fully compliant string representation of the Xray-core JSON config 
     * based on the active server profile and global VPN preferences.
     */
    fun generate(server: ServerConfig, settings: VpnSettings): String {
        try {
            val config = JSONObject()

            // 1. Log Config
            val log = JSONObject().apply {
                put("loglevel", "warning")
                put("access", "")
                put("error", "")
            }
            config.put("log", log)

            // 2. Inbounds (Local HTTP / SOCKS socks proxy, HTTP proxy)
            val inbounds = JSONArray()
            inbounds.put(JSONObject().apply {
                put("port", 10808)
                put("protocol", "socks")
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                    })
                })
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
            })
            config.put("inbounds", inbounds)

            // 3. Outbounds (Main server + direct fallback)
            val outbounds = JSONArray()
            
            // Proxy outbound
            val mainOutbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", server.protocol.name.lowercase())
                
                // Stream Settings (Transport, TLS)
                val streamSettings = JSONObject()
                streamSettings.put("network", server.network)
                
                if (server.security == "tls" || server.security == "xtls") {
                    streamSettings.put("security", server.security)
                    val tlsSettings = JSONObject().apply {
                        put("serverName", if (server.sni.isNotEmpty()) server.sni else server.address)
                        put("allowInsecure", false)
                    }
                    streamSettings.put("${server.security}Settings", tlsSettings)
                }

                // Transport-specific blocks (ws, grpc)
                if (server.network == "ws") {
                    val wsSettings = JSONObject().apply {
                        put("path", if (server.path.isNotEmpty()) server.path else "/")
                    }
                    streamSettings.put("wsSettings", wsSettings)
                } else if (server.network == "grpc") {
                    val grpcSettings = JSONObject().apply {
                        put("serviceName", server.path)
                    }
                    streamSettings.put("grpcSettings", grpcSettings)
                }
                
                put("streamSettings", streamSettings)

                // Protocol settings (VMess, VLESS, Trojan, SS, SOCKS parameters)
                val protocolSettings = JSONObject()
                val serversArray = JSONArray()
                
                when (server.protocol) {
                    ProxyProtocol.VMESS -> {
                        val userObj = JSONObject().apply {
                            put("id", server.uuid)
                            put("alterId", server.alterId)
                            put("security", "auto")
                        }
                        serversArray.put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("users", JSONArray().apply { put(userObj) })
                        })
                        protocolSettings.put("vnext", serversArray)
                    }
                    ProxyProtocol.VLESS -> {
                        val userObj = JSONObject().apply {
                            put("id", server.uuid)
                            put("encryption", "none")
                            put("flow", "")
                        }
                        serversArray.put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("users", JSONArray().apply { put(userObj) })
                        })
                        protocolSettings.put("vnext", serversArray)
                    }
                    ProxyProtocol.TROJAN -> {
                        serversArray.put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("password", server.password)
                        })
                        protocolSettings.put("servers", serversArray)
                    }
                    ProxyProtocol.SHADOWSOCKS -> {
                        serversArray.put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("password", server.password)
                            put("method", "aes-256-gcm") // Default secure cypher
                        })
                        protocolSettings.put("servers", serversArray)
                    }
                    ProxyProtocol.SOCKS -> {
                        serversArray.put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                        })
                        protocolSettings.put("servers", serversArray)
                    }
                }
                put("settings", protocolSettings)
            }
            outbounds.put(mainOutbound)

            // Direct fallback outbound
            outbounds.put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })

            // Blocked/blackhole outbound
            outbounds.put(JSONObject().apply {
                put("tag", "blocked")
                put("protocol", "blackhole")
                put("settings", JSONObject())
            })

            config.put("outbounds", outbounds)

            // 4. DNS config
            val dns = JSONObject().apply {
                val serversArr = JSONArray()
                settings.dnsServer.split(",").map { it.trim() }.forEach {
                    serversArr.put(it)
                }
                put("servers", serversArr)
            }
            config.put("dns", dns)

            // 5. Routing Rules (Bypass LAN, Direct domain mapping, bypass China IP)
            val routing = JSONObject()
            routing.put("domainStrategy", "IPIfNonMatch")
            
            val rules = JSONArray()
            
            // Bypass DNS traffic
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("port", "53")
            })

            if (settings.bypassLan) {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:private")
                        put("10.0.0.0/8")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                    })
                })
            }
            
            if (settings.bypassMainland) {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply { put("geoip:cn") })
                    put("domain", JSONArray().apply { put("geosite:cn") })
                })
            }
            
            routing.put("rules", rules)
            config.put("routing", routing)

            return config.toString(4) // 4 space indentation
        } catch (e: Exception) {
            return """{ "error": "Failed to serialize Xray config: ${e.message}" }"""
        }
    }

    /**
     * Super basic configuration structural validation.
     */
    fun validate(server: ServerConfig): Pair<Boolean, String> {
        if (server.name.trim().isEmpty()) {
            return Pair(false, "Server Profile Name cannot be empty")
        }
        if (server.address.trim().isEmpty()) {
            return Pair(false, "Server Address/IP cannot be empty")
        }
        if (server.port <= 0 || server.port > 65535) {
            return Pair(false, "Port must be in the range 1-65535")
        }
        
        when (server.protocol) {
            ProxyProtocol.VMESS, ProxyProtocol.VLESS -> {
                if (server.uuid.trim().isEmpty()) {
                    return Pair(false, "UUID is required for VMess / VLESS protocols")
                }
            }
            ProxyProtocol.TROJAN, ProxyProtocol.SHADOWSOCKS -> {
                if (server.password.trim().isEmpty()) {
                    return Pair(false, "Password is required for Trojan / Shadowsocks")
                }
            }
            ProxyProtocol.SOCKS -> {} // Defaults valid
        }
        
        return Pair(true, "Configuration valid")
    }
}
