package com.ronin.phoneshm.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SurfaceType {
    CONCRETE, CERAMIC_TILE, TIMBER, CARPET, UNKNOWN
}

enum class LocationType {
    CENTER_SPAN, NEAR_COLUMN, BALCONY, FOUNDATION, ROOF
}

enum class PhonePlacement {
    FLAT_ON_FLOOR, FLAT_WITH_WEIGHT, MOUNTED_WALL, HANDHELD_INVALID
}

/**
 * MeasurementProfileEntity persists session placement setup parameters.
 */
@Entity(tableName = "measurement_profiles")
data class MeasurementProfileEntity(
    @PrimaryKey
    val id: String,
    val buildingId: String,
    val floorLevel: Int?,
    val surfaceType: String,
    val locationType: String,
    val placement: String,
    val createdAt: Long = System.currentTimeMillis()
)
