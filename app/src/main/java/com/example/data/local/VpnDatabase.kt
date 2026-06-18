package com.example.data.local

import android.content.Context
import androidx.room.*
import com.example.domain.model.*

class Converters {
    @TypeConverter
    fun fromProxyProtocol(protocol: ProxyProtocol): String = protocol.name
    
    @TypeConverter
    fun toProxyProtocol(value: String): ProxyProtocol = ProxyProtocol.valueOf(value)

    @TypeConverter
    fun fromLogLevel(level: LogLevel): String = level.name
    
    @TypeConverter
    fun toLogLevel(value: String): LogLevel = LogLevel.valueOf(value)
}

@Database(
    entities = [ServerConfig::class, Subscription::class, AppLog::class, VpnSettings::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class VpnDatabase : RoomDatabase() {
    abstract fun vpnDao(): VpnDao

    companion object {
        @Volatile
        private var INSTANCE: VpnDatabase? = null

        fun getDatabase(context: Context): VpnDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VpnDatabase::class.java,
                    "vpn_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
