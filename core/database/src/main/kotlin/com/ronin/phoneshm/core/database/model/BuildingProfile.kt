package com.ronin.phoneshm.core.database.model

/**
 * BuildingProfile represents the domain model for structural typology and material properties.
 */
data class BuildingProfile(
    val id: String,
    val name: String,
    val type: String,               // RESIDENTIAL_CONCRETE, COMMERCIAL_STEEL, MASONRY, HERITAGE_WOOD, INDUSTRIAL, OTHER
    val floors: Int? = null,
    val constructionYear: Int? = null,
    val material: String? = null,
    val buildingHash: String? = null // Crowdsourced anonymized spatial cluster ID
)
