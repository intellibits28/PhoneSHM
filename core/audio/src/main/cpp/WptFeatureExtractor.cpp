#include "WptFeatureExtractor.h"
#include <cmath>
#include <numeric>
#include <stdexcept>
#include <iostream>

namespace phoneshm {
namespace audio {

WptFeatureExtractor::WptFeatureExtractor(int depth) : m_depth(depth) {
    if (m_depth < 1 || m_depth > 10) {
        throw std::invalid_argument("Invalid depth for WPT");
    }
    initFilters();
}

void WptFeatureExtractor::initFilters() {
    // Daubechies db4 scaling (low-pass) and wavelet (high-pass) coefficients
    const float sqrt2 = std::sqrt(2.0f);
    m_db4_h = {
        0.4829629131f / sqrt2,
        0.8365163037f / sqrt2,
        0.2241438680f / sqrt2,
        -0.1294095225f / sqrt2,
        -0.0992195431f / sqrt2,
        0.0358121650f / sqrt2,
        0.0244672620f / sqrt2,
        -0.0039282305f / sqrt2
    };
    
    // High-pass filter g[n] = (-1)^n * h[N-1-n]
    m_db4_g.resize(m_db4_h.size());
    for (size_t i = 0; i < m_db4_h.size(); ++i) {
        m_db4_g[i] = ((i % 2 == 0) ? 1 : -1) * m_db4_h[m_db4_h.size() - 1 - i];
    }
}

std::vector<float> WptFeatureExtractor::downsample(const std::vector<float>& input, int nativeRate, int targetRate) {
    if (nativeRate <= targetRate) return input;
    // Basic decimation for skeleton (in production, apply anti-aliasing LPF first)
    int ratio = nativeRate / targetRate;
    std::vector<float> output;
    output.reserve(input.size() / ratio);
    for (size_t i = 0; i < input.size(); i += ratio) {
        output.push_back(input[i]);
    }
    return output;
}

std::vector<float> WptFeatureExtractor::convolveAndDownsample(const std::vector<float>& input, const std::vector<float>& filter) {
    std::vector<float> output(input.size() / 2, 0.0f);
    int filterLen = filter.size();
    for (size_t i = 0; i < output.size(); ++i) {
        float sum = 0.0f;
        for (int k = 0; k < filterLen; ++k) {
            int idx = 2 * i - k;
            if (idx >= 0 && idx < (int)input.size()) {
                sum += input[idx] * filter[k];
            }
        }
        output[i] = sum;
    }
    return output;
}

std::vector<float> WptFeatureExtractor::computeWptEnergies(const std::vector<float>& signal) {
    std::vector<std::vector<float>> currentLevelNodes;
    currentLevelNodes.push_back(signal);
    
    for (int d = 0; d < m_depth; ++d) {
        std::vector<std::vector<float>> nextLevelNodes;
        for (const auto& node : currentLevelNodes) {
            nextLevelNodes.push_back(convolveAndDownsample(node, m_db4_h)); // Approximation
            nextLevelNodes.push_back(convolveAndDownsample(node, m_db4_g)); // Detail
        }
        currentLevelNodes = std::move(nextLevelNodes);
    }
    
    std::vector<float> energies;
    energies.reserve(currentLevelNodes.size());
    for (const auto& node : currentLevelNodes) {
        float energy = 0.0f;
        for (float val : node) {
            energy += val * val;
        }
        energies.push_back(energy);
    }
    
    // Normalize energies
    float totalEnergy = std::accumulate(energies.begin(), energies.end(), 0.0f);
    if (totalEnergy > 0) {
        for (float& e : energies) {
            e /= totalEnergy;
        }
    }
    return energies;
}

float WptFeatureExtractor::computeShannonEntropy(const std::vector<float>& signal) {
    // Stub for backlog feature
    return 0.0f;
}

WptFeatures WptFeatureExtractor::extractNodeEnergies(const std::vector<float>& rawPcm, int nativeSampleRate) {
    std::vector<float> workingSignal = downsample(rawPcm, nativeSampleRate, 16000);
    std::vector<float> energies = computeWptEnergies(workingSignal);
    
    // Simple heuristic classification stub
    std::string label = "quiet";
    double conf = 0.5;
    if (!energies.empty() && energies[0] < 0.5f) {
        label = "vehicle_passing";
        conf = 0.85;
    }
    
    return {energies, label, conf};
}

} // namespace audio
} // namespace phoneshm
