package com.ronin.phoneshm.core.location

/**
 * MapPinMarker represents a structural location confirmed or placed on the map abstraction.
 */
data class MapPinMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val isDraggable: Boolean = true
)

/**
 * MapProvider defines an abstraction over map rendering and pin interactions without coupling
 * to proprietary SDKs like Google Maps.
 *
 * Phase 0 / Phase 2 default target: MapLibre / OpenStreetMap (offline-capable, zero API key dependency).
 * Allows seamless drop-in addition of GoogleMapsProvider later if requested.
 */
interface MapProvider {
    /**
     * Initializes offline tile caches or online tile providers.
     */
    suspend fun initializeProvider(offlineTilePath: String? = null): Boolean

    /**
     * Updates center camera coordinates on the map view.
     */
    fun setCameraCenter(latitude: Double, longitude: Double, zoomLevel: Float = 16.0f)

    /**
     * Places or updates a pin marker on the map for user confirmation or dragging.
     */
    fun setPinMarker(marker: MapPinMarker)

    /**
     * Registers listener for when user drags and confirms pin location.
     */
    fun setOnPinDragConfirmedListener(listener: (latitude: Double, longitude: Double) -> Unit)
}
