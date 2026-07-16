package com.ronin.phoneshm.core.database.model

/**
 * MeasurementProfile represents session placement configuration and surface coupling setup.
 */
data class MeasurementProfile(
    val id: String,
    val buildingId: String,
    val floorLevel: Int? = null,
    val surfaceType: String = "CONCRETE",      // CONCRETE, CERAMIC_TILE, TIMBER, CARPET, UNKNOWN
    val locationType: String = "CENTER_SPAN",  // CENTER_SPAN, NEAR_COLUMN, BALCONY, FOUNDATION, ROOF
    val placement: String = "FLAT_ON_FLOOR",   // FLAT_ON_FLOOR, FLAT_WITH_WEIGHT, MOUNTED_WALL, HANDHELD_INVALID
    val createdAt: Long = System.currentTimeMillis()
)
