package com.ronin.phoneshm.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "baseline_history",
    foreignKeys = [ForeignKey(
        entity = BaselineProfileEntity::class,
        parentColumns = ["buildingHash"], 
        childColumns = ["buildingHash"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["buildingHash"])]
)
data class BaselineHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildingHash: String, 
    val timestampMs: Long,
    val f0Hz: Double, 
    val qualityScorePct: Int,
    val timeOfDay: String? = null,
    val temperatureCelsius: Float? = null
)
