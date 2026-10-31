package com.ronin.phoneshm.core.modal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class FloorModalObservation(
    val floorLevel: Int, // e.g., 1, 2, 3, ..., N
    val floorName: String, // e.g., "Floor 1", "Roof"
    val f0Hz: Double,
    val f1Hz: Double? = null,
    val peakSingularValue: Double, // sigma_1(f0)
    val stationaryBroadbandRmsVariance: Double, // Broadband ambient RMS^2 for excitation normalization
    val dominantAzimuthDegrees: Double, // Principal horizontal sway azimuth theta in [-180, 180]
    val qualityScore: Double = 1.0
)

data class BuildingModeShapeResult(
    val referenceFrequencyHz: Double,
    val floorLevels: List<Int>,
    val normalizedAmplitudes: List<Double>, // phi(floor), normalized so phi(Roof) = 1.0
    val phaseSigns: List<Double>, // +1.0 or -1.0 derived from cos(theta_floor - theta_ref)
    val interStoryDriftRatios: List<Double>,
    val isMonotonicShearProfile: Boolean
)

interface RovingModeShapeEngine {
    fun synthesizeModeShape(
        observations: List<FloorModalObservation>,
        frequencyTolerancePct: Double = 5.0
    ): BuildingModeShapeResult?
}

/**
 * Synthesizes multi-floor normalized mode shapes from sequential single-phone roving
 * OMA sessions using ambient broadband RMS variance as an excitation proxy.
 */
class DefaultRovingModeShapeEngine : RovingModeShapeEngine {

    override fun synthesizeModeShape(
        observations: List<FloorModalObservation>,
        frequencyTolerancePct: Double
    ): BuildingModeShapeResult? {
        val validPositive = observations.filter {
            it.f0Hz > 0.0 && !it.f0Hz.isNaN() && !it.f0Hz.isInfinite() &&
                it.peakSingularValue >= 0.0 && !it.peakSingularValue.isNaN()
        }
        if (validPositive.isEmpty()) return null

        // Determine reference modal frequency (median f0 across valid observations)
        val sortedFreqs = validPositive.map { it.f0Hz }.sorted()
        val medianF0 = if (sortedFreqs.size % 2 == 1) {
            sortedFreqs[sortedFreqs.size / 2]
        } else {
            0.5 * (sortedFreqs[sortedFreqs.size / 2 - 1] + sortedFreqs[sortedFreqs.size / 2])
        }
        if (medianF0 <= 0.0) return null

        // Filter observations within frequencyTolerancePct of reference f0
        val filtered = validPositive.filter { obs ->
            val relDiffPct = (abs(obs.f0Hz - medianF0) / medianF0) * 100.0
            relDiffPct <= frequencyTolerancePct
        }
        if (filtered.isEmpty()) return null

        data class AggregatedFloor(
            val floorLevel: Int,
            val meanF0Hz: Double,
            val unscaledAmplitude: Double,
            val meanAzimuthDeg: Double
        )

        val groupedByFloor = filtered.groupBy { it.floorLevel }.toSortedMap()
        val aggregatedFloors = ArrayList<AggregatedFloor>(groupedByFloor.size)

        for ((floorLevel, floorObsList) in groupedByFloor) {
            var weightSum = 0.0
            var weightedAmpSum = 0.0
            var weightedF0Sum = 0.0
            var cosSum = 0.0
            var sinSum = 0.0

            for (obs in floorObsList) {
                val w = max(obs.qualityScore, 1e-6)
                val safeVar = max(obs.stationaryBroadbandRmsVariance, 1e-12)
                val safeSv = max(obs.peakSingularValue, 0.0)
                // |phi(L)| = sqrt( sigma_1^{(L)}(f0) / max(stationaryBroadbandRmsVariance, 1e-12) )
                val unscaledAmp = sqrt(safeSv / safeVar)

                val azRad = obs.dominantAzimuthDegrees * PI / 180.0
                weightedAmpSum += w * unscaledAmp
                weightedF0Sum += w * obs.f0Hz
                cosSum += w * cos(azRad)
                sinSum += w * sin(azRad)
                weightSum += w
            }

            if (weightSum > 0.0) {
                val meanAmp = weightedAmpSum / weightSum
                val meanF0 = weightedF0Sum / weightSum
                val meanAzDeg = atan2(sinSum / weightSum, cosSum / weightSum) * 180.0 / PI
                aggregatedFloors.add(
                    AggregatedFloor(
                        floorLevel = floorLevel,
                        meanF0Hz = meanF0,
                        unscaledAmplitude = meanAmp,
                        meanAzimuthDeg = meanAzDeg
                    )
                )
            }
        }

        if (aggregatedFloors.isEmpty()) return null

        // Highest floor (Roof) serves as spatial normalization & phase reference
        val roofFloor = aggregatedFloors.last()
        val roofAmplitude = roofFloor.unscaledAmplitude
        if (roofAmplitude <= 1e-15) return null

        val refAzimuthDeg = roofFloor.meanAzimuthDeg
        val refFrequencyHz = roofFloor.meanF0Hz

        val floorLevels = ArrayList<Int>(aggregatedFloors.size)
        val normalizedAmplitudes = ArrayList<Double>(aggregatedFloors.size)
        val phaseSigns = ArrayList<Double>(aggregatedFloors.size)
        val interStoryDriftRatios = ArrayList<Double>(aggregatedFloors.size)

        var isMonotonic = true
        var prevAmplitude = 0.0
        var prevFloorLevel = 0

        for (i in aggregatedFloors.indices) {
            val floor = aggregatedFloors[i]
            val deltaAzRad = (floor.meanAzimuthDeg - refAzimuthDeg) * PI / 180.0
            val phaseSign = if (cos(deltaAzRad) < 0.0) -1.0 else 1.0
            val normAmp = if (i == aggregatedFloors.lastIndex) {
                1.0 * phaseSign
            } else {
                phaseSign * (floor.unscaledAmplitude / roofAmplitude)
            }

            val dFloor = max(1, floor.floorLevel - prevFloorLevel)
            val driftRatio = abs(normAmp - prevAmplitude) / dFloor.toDouble()

            if (phaseSign < 0.0 || normAmp + 1e-6 < prevAmplitude) {
                isMonotonic = false
            }

            floorLevels.add(floor.floorLevel)
            normalizedAmplitudes.add(normAmp)
            phaseSigns.add(phaseSign)
            interStoryDriftRatios.add(driftRatio)

            prevAmplitude = normAmp
            prevFloorLevel = floor.floorLevel
        }

        return BuildingModeShapeResult(
            referenceFrequencyHz = refFrequencyHz,
            floorLevels = floorLevels,
            normalizedAmplitudes = normalizedAmplitudes,
            phaseSigns = phaseSigns,
            interStoryDriftRatios = interStoryDriftRatios,
            isMonotonicShearProfile = isMonotonic
        )
    }
}
