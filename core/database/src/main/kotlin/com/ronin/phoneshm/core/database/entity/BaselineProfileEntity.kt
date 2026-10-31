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
    val lastUpdatedAt: Long,
    val meanF0XHz: Double? = null,
    val stdF0XHz: Double? = null,
    val m2F0X: Double? = null,
    val meanF0YHz: Double? = null,
    val stdF0YHz: Double? = null,
    val m2F0Y: Double? = null,
    val meanDampingEfdd: Double? = null,
    val meanDampingSsi: Double? = null,
    val thermalSensitivityHzPerC: Double? = null,
    val referenceTemperatureC: Double = 25.0,
    val meanTempC: Double? = null,
    val m2Temp: Double? = null,
    val coMomentTempF0: Double? = null,
    val tempSampleCount: Int = 0
)
