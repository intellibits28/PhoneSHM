#pragma once

#include <vector>

namespace phoneshm {
namespace dsp {

struct RdtSsiPole {
    float freq;
    float damp;
    int order;
};

std::vector<RdtSsiPole> calculateRdtSsi(
    const float* x, const float* y, const float* z, int length,
    float fs, float minHz, float maxHz, int maxModelOrder, float rdsDurationSec
);

} // namespace dsp
} // namespace phoneshm
