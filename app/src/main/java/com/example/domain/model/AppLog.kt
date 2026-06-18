package com.example.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_logs")
data class AppLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String = "XRAY_CORE",
    val level: LogLevel = LogLevel.INFO,
    val message: String
)

enum class LogLevel {
    INFO, WARNING, ERROR, SUCCESS
}
