#pragma once
#include <vector>
#include <complex>
#include "dsp_core.h"

namespace phoneshm {
namespace dsp {

struct FddResult {
    std::vector<float> frequencies;
    std::vector<float> firstSingularValues;
    std::vector<Peak> modes;
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
