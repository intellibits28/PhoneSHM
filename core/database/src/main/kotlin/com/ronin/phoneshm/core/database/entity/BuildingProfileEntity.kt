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
    val id: String,
    val name: String,
    val type: String,
    val floors: Int?,
    val constructionYear: Int?,
    val material: String?,
    val buildingHash: String?
)
