package com.ronin.phoneshm.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "baseline_profiles")
data class BaselineProfileEntity(
    @PrimaryKey val buildingHash: String,
    val meanF0Hz: Double, 
    val stdF0Hz: Double,
    val m2: Double, 
    val measurementCount: Int,
    val consecutiveAnomalyCount: Int = 0,
    val lastUpdatedAt: Long
)
