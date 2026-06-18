package com.example.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "vpn_settings")
data class VpnSettings(
    @PrimaryKey val id: Int = 1, // Store as a single row for configuration
    val dnsServer: String = "8.8.8.8, 1.1.1.1",
    val bypassLan: Boolean = true,
    val bypassMainland: Boolean = false,
    val fakeDnsEnabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val connectionMode: String = "Global", // Global, Bypass LAN, Bypass Mainland
    val perAppProxyEnabled: Boolean = false,
    val selectedAppsList: String = "" // Comma separated package names
) : Serializable {
    fun getAppsList(): List<String> {
        if (selectedAppsList.isEmpty()) return emptyList()
        return selectedAppsList.split(",").map { it.trim() }
    }
}
