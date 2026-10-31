#include "wavelet_packet_core.h"
#include <cmath>
#include <numeric>
#include <algorithm>

namespace phoneshm {
namespace dsp {

namespace {

std::vector<float> convolveAndDownsampleDb4(
    const std::vector<float>& input,
    const std::array<float, 8>& filter
) {
    if (input.size() < 2) return input;
    size_t outLen = input.size() / 2;
    std::vector<float> output(outLen, 0.0f);
    constexpr int filterLen = 8;
    int inSize = static_cast<int>(input.size());

    for (size_t i = 0; i < outLen; ++i) {
        float sum = 0.0f;
        for (int k = 0; k < filterLen; ++k) {
            int idx = static_cast<int>(2 * i) - k;
            if (idx >= 0 && idx < inSize) {
                sum += input[idx] * filter[k];
            }
        }
        output[i] = sum;
    }
    return output;
}

} // anonymous namespace

float computeShannonEntropy(const std::array<float, 16>& normalizedEnergies) {
    float entropy = 0.0f;
    for (float p : normalizedEnergies) {
        if (p > 1e-12f) {
            entropy -= p * std::log(p);
        }
    }
    return entropy;
}

WptAnalysisResult analyzeWaveletPacketDb4(
    const std::vector<float>& signal,
    int depth
) {
    WptAnalysisResult result;
    if (signal.empty()) {
        return result;
    }

    int effectiveDepth = std::clamp(depth, 1, 4);
    const float sqrt2 = std::sqrt(2.0f);

    // Daubechies db4 low-pass scaling filter h[n] (ported from WptFeatureExtractor.cpp without audio downsampling)
    const std::array<float, 8> db4_h = {
        0.4829629131f / sqrt2,
        0.8365163037f / sqrt2,
        0.2241438680f / sqrt2,
        -0.1294095225f / sqrt2,
        -0.0992195431f / sqrt2,
        0.0358121650f / sqrt2,
        0.0244672620f / sqrt2,
        -0.0039282305f / sqrt2
    };

    //Quadrature mirror high-pass wavelet filter g[n] = (-1)^n * h[7 - n]
    std::array<float, 8> db4_g{};
    for (size_t i = 0; i < 8; ++i) {
        db4_g[i] = ((i % 2 == 0) ? 1.0f : -1.0f) * db4_h[7 - i];
    }

    std::vector<std::vector<float>> currentLevelNodes;
    currentLevelNodes.push_back(signal);

    for (int d = 0; d < effectiveDepth; ++d) {
        std::vector<std::vector<float>> nextLevelNodes;
        nextLevelNodes.reserve(currentLevelNodes.size() * 2);
        for (const auto& node : currentLevelNodes) {
            nextLevelNodes.push_back(convolveAndDownsampleDb4(node, db4_h)); // Approximation
            nextLevelNodes.push_back(convolveAndDownsampleDb4(node, db4_g)); // Detail
        }
        currentLevelNodes = std::move(nextLevelNodes);
    }

    size_t numBands = std::min<size_t>(16, currentLevelNodes.size());
    float totalEnergy = 0.0f;
    for (size_t j = 0; j < numBands; ++j) {
        double bandEnergy = 0.0;
        for (float val : currentLevelNodes[j]) {
            bandEnergy += static_cast<double>(val) * static_cast<double>(val);
        }
        result.normalizedEnergies[j] = static_cast<float>(bandEnergy);
        totalEnergy += result.normalizedEnergies[j];
    }

    if (totalEnergy > 1e-20f) {
        for (size_t j = 0; j < numBands; ++j) {
            result.normalizedEnergies[j] /= totalEnergy;
        }
    }

    result.shannonEntropy = computeShannonEntropy(result.normalizedEnergies);

    // Structural shock concentrates energy in lower structural subbands (first 8 subbands, 0-25 Hz)
    // and exhibits narrowband modal decay (low Shannon entropy < 1.8), whereas accidental drops/handling
    // spread broadband energy across all 16 subbands (high Shannon entropy > 2.0).
    float lowBandEnergy = 0.0f;
    for (size_t j = 0; j < 8; ++j) {
        lowBandEnergy += result.normalizedEnergies[j];
    }
    result.isStructuralShock = (totalEnergy > 1e-20f) &&
                               (result.shannonEntropy < 1.8f) &&
                               (lowBandEnergy >= 0.55f);

    return result;
}

} // namespace dsp
} // namespace phoneshm
