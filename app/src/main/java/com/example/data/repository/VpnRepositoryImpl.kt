package com.example.data.repository

import com.example.data.local.VpnDao
import com.example.domain.model.AppLog
import com.example.domain.model.ServerConfig
import com.example.domain.model.Subscription
import com.example.domain.model.VpnSettings
import com.example.domain.repository.VpnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class VpnRepositoryImpl(private val vpnDao: VpnDao) : VpnRepository {

    override fun getServers(): Flow<List<ServerConfig>> = vpnDao.getServers()

    override suspend fun getServerById(id: Long): ServerConfig? = vpnDao.getServerById(id)

    override suspend fun insertServer(server: ServerConfig): Long = vpnDao.insertServer(server)

    override suspend fun updateServer(server: ServerConfig) = vpnDao.updateServer(server)

    override suspend fun deleteServer(id: Long) = vpnDao.deleteServer(id)

    override suspend fun selectServer(id: Long) = vpnDao.selectSingleServer(id)

    override suspend fun clearLatency() = vpnDao.clearLatency()

    override fun getSubscriptions(): Flow<List<Subscription>> = vpnDao.getSubscriptions()

    override suspend fun getSubscriptionById(id: Long): Subscription? = vpnDao.getSubscriptionById(id)

    override suspend fun insertSubscription(sub: Subscription): Long = vpnDao.insertSubscription(sub)

    override suspend fun updateSubscription(sub: Subscription) = vpnDao.updateSubscription(sub)

    override suspend fun deleteSubscription(id: Long) = vpnDao.deleteSubscription(id)

    override fun getSettings(): Flow<VpnSettings> {
        return vpnDao.getSettingsFlow().map { it ?: VpnSettings() }
    }

    override suspend fun updateSettings(settings: VpnSettings) {
        vpnDao.insertSettings(settings)
    }

    override fun getLogs(): Flow<List<AppLog>> = vpnDao.getLogs()

    override suspend fun insertLog(log: AppLog) = vpnDao.insertLog(log)

    override suspend fun clearLogs() = vpnDao.clearLogs()
}
