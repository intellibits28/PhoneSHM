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
    float sampleRateHz,
    const std::vector<uint8_t>& gapMask = {}
);

// Full multi-axis Welch PSD pipeline (B2: post-session accurate path)
// C1: Uses double accumulation internally
MultiAxisSpectrumResult calculateMultiAxisWelchPsd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    const WelchPsdParams& params = WelchPsdParams{},
    const std::vector<uint8_t>& gapMask = {}
);

// C4: NaN/Inf detection
bool hasNanOrInf(const std::vector<AccelerationSample>& samples);

enum class InterpolationMode {
    LINEAR = 0,
    PCHIP_CUBIC = 1
};

struct ResamplingResult {
    std::vector<AccelerationSample> samples;
    std::vector<uint8_t> gapMask; // 1 = in gap > 50ms (linear bridge), 0 = valid
    int originalCount = 0;
    int resampledCount = 0;
    double maxGapMs = 0.0;
    double totalMissingTimeSec = 0.0;
    double missingTimeRatio = 0.0;
    bool isContinuityPassed = true;
};

// Resamples irregular acceleration samples to a uniform grid (PCHIP or Linear)
ResamplingResult resampleToUniformGrid(
    const std::vector<AccelerationSample>& samples,
    float targetSampleRateHz,
    InterpolationMode mode = InterpolationMode::PCHIP_CUBIC,
    double gapThresholdMs = 50.0,
    double maxAllowedMissingRatio = 0.05
);

struct TransientEventAnalysis {
    int onsetSampleIndex = 0;
    float peakAccelerationG = 0.0f;
    float rmsG = 0.0f;
    float durationMs = 0.0f;
    float waveletEntropy = 0.0f;
    bool isStructuralShock = false;
    std::array<float, 16> wptSubbandEnergies{};
    int stftTimeSteps = 0;
    int stftFreqBins = 0;
    std::vector<float> stftSpectrogram; // Flattened [stftTimeSteps x stftFreqBins]
};

// Phase 3: Analyzes high-energy transient segments rejected by the Welch Median + 3*MAD gate
// using 256-point Hanning STFT (75% overlap, 64-sample hop) and Daubechies db4 WPT (Depth J=4, 16 subbands).
std::vector<TransientEventAnalysis> analyzeGatedTransients(
    const std::vector<float>& signal,
    float sampleRateHz = 100.0f,
    int segmentSize = 512,
    float segmentOverlap = 0.50f,
    const std::vector<uint8_t>& gapMask = {}
);

} // namespace dsp
} // namespace phoneshm
