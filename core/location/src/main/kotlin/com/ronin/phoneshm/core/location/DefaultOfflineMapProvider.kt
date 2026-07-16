package com.ronin.phoneshm.core.location

/**
 * DefaultOfflineMapProvider is an offline-capable, zero-dependency MapProvider implementation.
 * It simulates or loads offline MapLibre/OpenStreetMap tiles, registers pin placement,
 * and handles manual adjustments for structural positioning.
 */
class DefaultOfflineMapProvider : MapProvider {

    private var currentMarker: MapPinMarker? = null
    private var dragListener: ((Double, Double) -> Unit)? = null
    private var isInitialized = false

    override suspend fun initializeProvider(offlineTilePath: String?): Boolean {
        // Load offline tile cache or initialize OSM provider
        isInitialized = true
        return true
    }

    override fun setCameraCenter(latitude: Double, longitude: Double, zoomLevel: Float) {
        // Simulates focusing camera on the physical building structure coordinates
    }

    override fun setPinMarker(marker: MapPinMarker) {
        currentMarker = marker
    }

    override fun setOnPinDragConfirmedListener(listener: (Double, Double) -> Unit) {
        dragListener = listener
    }

    /**
     * Simulation helper to trigger user drag confirmation event on the map.
     */
    fun simulateUserDrag(lat: Double, lon: Double) {
        currentMarker = currentMarker?.copy(latitude = lat, longitude = lon)
        dragListener?.invoke(lat, lon)
    }
}
