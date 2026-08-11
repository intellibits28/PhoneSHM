#pragma once
#include <vector>
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <string>
#include <array>

namespace phoneshm {
namespace dsp {

struct AccelerationSample {
    int64_t timestampNs;
    float x, y, z;
};

struct GravityRemovalResult {
    std::vector<AccelerationSample> gravityFreeSamples;
    std::vector<std::array<float, 3>> estimatedGravityVectors; // [gx, gy, gz] per sample
    float gravityVectorVariance; // For QualityEngine coupling score
};

struct SettlingWindowResult {
    int settlingDurationSamples;
    int gravityStabilizedAtSample;
    bool wasExtended;
};

struct Peak {
    float frequencyHz;
    float powerMagnitude;
    float prominence;
};

struct AxisPsdResult {
    std::vector<float> frequencies;
    std::vector<float> powerSpectralDensity;
    std::vector<Peak> peaks;
};

std::vector<Peak> findPeaks(const std::vector<float>& frequencies, const std::vector<float>& psd);

struct WelchPsdParams {
    int fftSize = 1024;
    float overlapPercentage = 0.50f;
    // windowType is always Hanning
};

struct WelchPsdOutput {
    int actualSegmentCount;
    float effectiveDeltaFHz;
    SettlingWindowResult settlingWindow;
    bool hasSettlingWindow = false;
};

struct MultiAxisSpectrumResult {
    AxisPsdResult psdX, psdY, psdZ, psdMagnitude;
    WelchPsdOutput output;
};

// B1: Gravity removal via EMA + detrend, returns gravity vector time-series
GravityRemovalResult removeGravityAndDetrend(
    const std::vector<AccelerationSample>& samples,
    float emaAlpha = 0.1f
);

// B1: Compound settling window detection
SettlingWindowResult detectSettlingWindow(
    const GravityRemovalResult& gravResult,
    float sampleRateHz,
    float hpfCutoffHz = 0.5f,
    float gravityVarianceThreshold = 0.01f
);

// B2: Live streaming RMS level (causal, real-time) — stateless per-call
// Internal state managed by caller via prevRms parameter
float streamingRmsLevel(const AccelerationSample& sample, float prevRms, float smoothingAlpha = 0.05f);

// High-pass Butterworth filter (2nd order, forward-backward = filtfilt)
std::vector<float> highPassFilterFiltfilt(
    const std::vector<float>& signal,
    float sampleRateHz,
    float cutoffHz = 0.5f
);

// Per-segment linear detrend
void detrendInPlace(std::vector<double>& data);

// Single axis PSD calculation
std::vector<float> welchPsdSingleAxis(
    const std::vector<float>& signal,
    int fftSize,
    float overlapFraction,
    float sampleRateHz
);

// Full multi-axis Welch PSD pipeline (B2: post-session accurate path)
// C1: Uses double accumulation internally
MultiAxisSpectrumResult calculateMultiAxisWelchPsd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    const WelchPsdParams& params = WelchPsdParams{}
);

// C4: NaN/Inf detection
bool hasNanOrInf(const std::vector<AccelerationSample>& samples);

} // namespace dsp
} // namespace phoneshm
