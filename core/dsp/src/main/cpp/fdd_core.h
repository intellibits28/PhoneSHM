#pragma once
#include <vector>
#include <complex>
#include "dsp_core.h"

namespace phoneshm {
namespace dsp {

struct EfddMode {
    float frequencyHz;
    float powerMagnitude;
    float prominence;
    float dampingRatio;
};

struct FddResult {
    std::vector<float> frequencies;
    std::vector<float> firstSingularValues;
    std::vector<EfddMode> modes;
};

// Computes the Enhanced Frequency Domain Decomposition (EFDD) spectrum
FddResult calculateFdd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    int fftSize,
    float overlapPct
);

}
}
