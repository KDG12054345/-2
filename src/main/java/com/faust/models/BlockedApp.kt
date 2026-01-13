package com.faust.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val blockedAt: Long = System.currentTimeMillis()
)
