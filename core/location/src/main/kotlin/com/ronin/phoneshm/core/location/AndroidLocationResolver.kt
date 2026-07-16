package com.ronin.phoneshm.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import java.security.MessageDigest
import kotlin.math.round

/**
 * AndroidLocationResolver retrieves coordinates using android.location.LocationManager,
 * applying privacy filters and generating deterministic SHA-256 spatial clustering building hashes.
 */
class AndroidLocationResolver(
    private val context: Context
) : LocationResolver {

    override fun generateBuildingHash(lat: Double, lon: Double, buildingName: String): String {
        // Round coordinates to 4 decimal places (~11 meters grid resolution)
        val roundedLat = String.format("%.4f", lat)
        val roundedLon = String.format("%.4f", lon)
        val normalizedName = buildingName.trim().lowercase().replace("\\s+".toRegex(), "_")
        val rawKey = "$roundedLat,$roundedLon,$normalizedName"

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(rawKey.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }.substring(0, 16)
        } catch (e: Exception) {
            "hash_${rawKey.hashCode()}"
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun resolveLocation(privacyLevel: PrivacyLevel): LocationProfile? {
        if (privacyLevel == PrivacyLevel.LOCAL_ONLY) {
            return LocationProfile(
                latitude = 0.0,
                longitude = 0.0,
                accuracyMeters = 0.0f,
                source = "LOCAL_ONLY",
                buildingHash = "local_only_anonymized",
                privacyLevel = privacyLevel
            )
        }

        val locationManager = try {
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (e: Exception) {
            null
        }
        var bestLocation: Location? = null

        try {
            val providers = locationManager?.getProviders(true) ?: emptyList()
            for (provider in providers) {
                val loc = locationManager?.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
        } catch (e: SecurityException) {
            // Permission missing or denied, fallback to mock/default
        }

        // If no GPS/Network location resolved, use default Yangon coordinates as fallback
        val lat = bestLocation?.latitude ?: 16.8409
        val lon = bestLocation?.longitude ?: 96.1735
        val acc = bestLocation?.accuracy ?: 25.0f
        val src = bestLocation?.provider ?: "MOCK_FALLBACK"

        return when (privacyLevel) {
            PrivacyLevel.EXACT_LOCATION -> {
                LocationProfile(
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = acc,
                    source = src,
                    buildingHash = generateBuildingHash(lat, lon, "default_building"),
                    privacyLevel = privacyLevel
                )
            }
            PrivacyLevel.APPROXIMATE_LOCATION -> {
                // Truncate/round to 3 decimal places (~110 meters bounding box)
                val roundedLat = round(lat * 1000.0) / 1000.0
                val roundedLon = round(lon * 1000.0) / 1000.0
                LocationProfile(
                    latitude = roundedLat,
                    longitude = roundedLon,
                    accuracyMeters = 110.0f,
                    source = "${src}_APPROXIMATE",
                    buildingHash = generateBuildingHash(roundedLat, roundedLon, "default_building"),
                    privacyLevel = privacyLevel
                )
            }
            else -> null
        }
    }
}
