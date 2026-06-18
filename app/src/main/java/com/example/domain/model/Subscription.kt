package com.example.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0,
    val autoUpdate: Boolean = true,
    val expiryTimestamp: Long = 0 // 0 means no expiration tracked
) : Serializable {
    val isExpired: Boolean
        get() = expiryTimestamp > 0 && System.currentTimeMillis() > expiryTimestamp
}
