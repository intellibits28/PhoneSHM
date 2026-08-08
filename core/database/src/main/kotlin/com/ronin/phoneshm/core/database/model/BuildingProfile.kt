package com.ronin.phoneshm.core.database.model

/**
 * BuildingProfile represents the domain model for structural typology and material properties.
 */
data class BuildingProfile(
    val buildingHash: String,
    val displayName: String,
    val buildingType: String,
    val floors: Int? = null,
    val constructionYear: Int? = null,
    val material: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)
