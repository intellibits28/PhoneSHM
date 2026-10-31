#pragma once
#include <vector>
#include <array>

namespace phoneshm {
namespace dsp {

struct WptAnalysisResult {
    std::array<float, 16> normalizedEnergies{};
    float shannonEntropy = 0.0f;
    bool isStructuralShock = false;
};

// Computes Daubechies db4 Wavelet Packet Transform (WPT) at depth J = 4 (16 subbands, 0-50 Hz for fs=100 Hz)
// Directly processes the 100 Hz vibration signal without audio downsampling.
WptAnalysisResult analyzeWaveletPacketDb4(
    const std::vector<float>& signal,
    int depth = 4
);

// Computes normalized Shannon Wavelet Entropy: S_wpt = - sum_{j} p_j * ln(p_j)
// Includes strict p_j > 1e-12 guard to prevent ln(0) -> NaN.
float computeShannonEntropy(const std::array<float, 16>& normalizedEnergies);

} // namespace dsp
} // namespace phoneshm
