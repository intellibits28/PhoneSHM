package com.ronin.phoneshm.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class BuildingType {
    RESIDENTIAL_CONCRETE,
    COMMERCIAL_STEEL,
    MASONRY,
    HERITAGE_WOOD,
    INDUSTRIAL,
    OTHER
}

/**
 * BuildingProfileEntity persists structural typology and material characteristics inside Room DB.
 */
@Entity(tableName = "building_profiles")
data class BuildingProfileEntity(
    @PrimaryKey
    val buildingHash: String,
    val displayName: String,
    val buildingType: String,
    val floors: Int?,
    val material: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)
