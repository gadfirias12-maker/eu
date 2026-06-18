package com.example.data.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

data class TrafficMetric(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val uploadSpeedBps: Long = 0,     // Upload Speed in Bytes/sec
    val downloadSpeedBps: Long = 0,   // Download Speed in Bytes/sec
    val totalUploadedBytes: Long = 0,  // Cumulative upload bytes
    val totalDownloadedBytes: Long = 0, // Cumulative download bytes
    val connectedDurationSeconds: Long = 0, // Seconds connected
    val activeProfileName: String = "None"
)

object TunnelStats {
    private val _currentStats = MutableStateFlow(TrafficMetric())
    val currentStats = _currentStats.asStateFlow()

    fun updateStatus(status: VpnStatus, profileName: String = "None") {
        _currentStats.value = _currentStats.value.copy(
            status = status,
            activeProfileName = profileName,
            // Clear speed metrics if disconnected
            uploadSpeedBps = if (status == VpnStatus.DISCONNECTED) 0 else _currentStats.value.uploadSpeedBps,
            downloadSpeedBps = if (status == VpnStatus.DISCONNECTED) 0 else _currentStats.value.downloadSpeedBps,
            connectedDurationSeconds = if (status == VpnStatus.DISCONNECTED) 0 else _currentStats.value.connectedDurationSeconds,
            totalUploadedBytes = if (status == VpnStatus.DISCONNECTED) 0 else _currentStats.value.totalUploadedBytes,
            totalDownloadedBytes = if (status == VpnStatus.DISCONNECTED) 0 else _currentStats.value.totalDownloadedBytes
        )
    }

    fun updateTraffic(uploadBps: Long, downloadBps: Long, addUpBytes: Long, addDownBytes: Long) {
        val current = _currentStats.value
        _currentStats.value = current.copy(
            uploadSpeedBps = uploadBps,
            downloadSpeedBps = downloadBps,
            totalUploadedBytes = current.totalUploadedBytes + addUpBytes,
            totalDownloadedBytes = current.totalDownloadedBytes + addDownBytes
        )
    }

    fun updateDuration(durationSeconds: Long) {
        _currentStats.value = _currentStats.value.copy(
            connectedDurationSeconds = durationSeconds
        )
    }

    fun reset() {
        _currentStats.value = TrafficMetric()
    }
}
