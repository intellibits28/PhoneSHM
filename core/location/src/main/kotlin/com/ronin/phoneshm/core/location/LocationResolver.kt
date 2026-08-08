package com.ronin.phoneshm.core.location

enum class PrivacyLevel {
    EXACT_LOCATION,
    APPROXIMATE_LOCATION,
    LOCAL_ONLY
}

/**
 * LocationProfile encapsulates geographic position and unique crowdsourcing buildingHash ID.
 */
data class LocationProfile(
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float,
    val source: String,
    val buildingHash: String, // SHA-256 or geohash representing unique physical structural identity
    val privacyLevel: PrivacyLevel
)

/**
 * LocationResolver resolves coordinates across multi-source providers (GPS/WiFi/Network)
 * and generates deterministic building hashes for citizen-scale aggregation.
 */
interface LocationResolver {
    /**
     * Resolves current location constrained by user privacy level preferences.
     */
    suspend fun resolveLocation(privacyLevel: PrivacyLevel): LocationProfile?

    /**
     * Generates a deterministic structural hash from coordinates and building metadata.
     */
    fun generateBuildingHash(lat: Double, lon: Double, buildingName: String): String
}
