package com.example.data.local

import androidx.room.*
import com.example.domain.model.AppLog
import com.example.domain.model.ServerConfig
import com.example.domain.model.Subscription
import com.example.domain.model.VpnSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnDao {
    // ---- Servers ----
    @Query("SELECT * FROM servers ORDER BY id DESC")
    fun getServers(): Flow<List<ServerConfig>>
    
    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: Long): ServerConfig?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerConfig): Long
    
    @Update
    suspend fun updateServer(server: ServerConfig)
    
    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteServer(id: Long)
    
    @Query("UPDATE servers SET isSelected = 0")
    suspend fun unselectAllServers()
    
    @Query("UPDATE servers SET isSelected = 1 WHERE id = :id")
    suspend fun selectServer(id: Long)
    
    @Query("UPDATE servers SET latency = -1")
    suspend fun clearLatency()
    
    @Transaction
    suspend fun selectSingleServer(id: Long) {
        unselectAllServers()
        selectServer(id)
    }

    // ---- Subscriptions ----
    @Query("SELECT * FROM subscriptions ORDER BY id DESC")
    fun getSubscriptions(): Flow<List<Subscription>>
    
    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: Long): Subscription?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(sub: Subscription): Long
    
    @Update
    suspend fun updateSubscription(sub: Subscription)
    
    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: Long)

    // ---- Settings ----
    @Query("SELECT * FROM vpn_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<VpnSettings?>
    
    @Query("SELECT * FROM vpn_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): VpnSettings?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: VpnSettings)

    // ---- Logs ----
    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT 300")
    fun getLogs(): Flow<List<AppLog>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AppLog)
    
    @Query("DELETE FROM app_logs")
    suspend fun clearLogs()
}
