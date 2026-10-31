#pragma once
#include <vector>
#include <complex>
#include "dsp_core.h"

namespace phoneshm {
namespace dsp {

struct EfddMode {
    float frequencyHz = 0.0f;
    float powerMagnitude = 0.0f;
    float prominence = 0.0f;
    float dampingRatio = 0.0f;
    float coherenceXY = 0.0f;
    float coherenceXZ = 0.0f;
    float coherenceYZ = 0.0f;
    float hvRatio = 0.0f;
    float azimuthDegrees = 0.0f;
    bool isValidHorizontalSway = false;
};

struct FddResult {
    std::vector<float> frequencies;
    std::vector<float> firstSingularValues;
    std::vector<float> coherenceXY;
    std::vector<float> coherenceXZ;
    std::vector<float> coherenceYZ;
    std::vector<float> hvRatio;
    std::vector<EfddMode> modes;
};

// Computes the Enhanced Frequency Domain Decomposition (EFDD) spectrum
FddResult calculateFdd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    int fftSize,
    float overlapPct,
    const std::vector<uint8_t>& gapMask = {}
);

}
}
