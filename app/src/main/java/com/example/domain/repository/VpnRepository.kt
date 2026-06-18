package com.example.domain.repository

import com.example.domain.model.AppLog
import com.example.domain.model.ServerConfig
import com.example.domain.model.Subscription
import com.example.domain.model.VpnSettings
import kotlinx.coroutines.flow.Flow

interface VpnRepository {
    // Servers
    fun getServers(): Flow<List<ServerConfig>>
    suspend fun getServerById(id: Long): ServerConfig?
    suspend fun insertServer(server: ServerConfig): Long
    suspend fun updateServer(server: ServerConfig)
    suspend fun deleteServer(id: Long)
    suspend fun selectServer(id: Long)
    suspend fun clearLatency()
    
    // Subscriptions
    fun getSubscriptions(): Flow<List<Subscription>>
    suspend fun getSubscriptionById(id: Long): Subscription?
    suspend fun insertSubscription(sub: Subscription): Long
    suspend fun updateSubscription(sub: Subscription)
    suspend fun deleteSubscription(id: Long)
    
    // Settings
    fun getSettings(): Flow<VpnSettings>
    suspend fun updateSettings(settings: VpnSettings)
    
    // Logs
    fun getLogs(): Flow<List<AppLog>>
    suspend fun insertLog(log: AppLog)
    suspend fun clearLogs()
}
