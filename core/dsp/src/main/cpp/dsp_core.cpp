#include "dsp_core.h"
#include "fft_backend.h"
#include "wavelet_packet_core.h"
#include <cmath>
#include <algorithm>
#include <numbers>

namespace phoneshm {
namespace dsp {

bool hasNanOrInf(const std::vector<AccelerationSample>& samples) {
    for (const auto& s : samples) {
        if (std::isnan(s.x) || std::isinf(s.x) ||
            std::isnan(s.y) || std::isinf(s.y) ||
            std::isnan(s.z) || std::isinf(s.z)) {
            return true;
        }
    }
    return false;
}

GravityRemovalResult removeGravityAndDetrend(
    const std::vector<AccelerationSample>& samples,
    float emaAlpha
) {
    GravityRemovalResult result;
    if (samples.empty()) {
        result.gravityVectorVariance = 0.0f;
        return result;
    }

    int n = samples.size();
    result.gravityFreeSamples.reserve(n);
    result.estimatedGravityVectors.reserve(n);

    float gx = samples[0].x;
    float gy = samples[0].y;
    float gz = samples[0].z;

    double gravMagSum = 0.0;
    double gravMagSumSq = 0.0;

    std::vector<double> xRes(n), yRes(n), zRes(n);

    for (int i = 0; i < n; ++i) {
        const auto& s = samples[i];
        gx = emaAlpha * s.x + (1.0f - emaAlpha) * gx;
        gy = emaAlpha * s.y + (1.0f - emaAlpha) * gy;
        gz = emaAlpha * s.z + (1.0f - emaAlpha) * gz;
        
        xRes[i] = s.x - gx;
        yRes[i] = s.y - gy;
        zRes[i] = s.z - gz;

        result.estimatedGravityVectors.push_back({gx, gy, gz});

        double gMag = std::sqrt(gx * gx + gy * gy + gz * gz);
        gravMagSum += gMag;
        gravMagSumSq += gMag * gMag;
    }

    double gravMagMean = gravMagSum / n;
    result.gravityVectorVariance = static_cast<float>((gravMagSumSq / n) - gravMagMean * gravMagMean);

    detrendInPlace(xRes);
    detrendInPlace(yRes);
    detrendInPlace(zRes);

    for (int i = 0; i < n; ++i) {
        AccelerationSample gfSample;
        gfSample.timestampNs = samples[i].timestampNs;
        gfSample.x = static_cast<float>(xRes[i]);
        gfSample.y = static_cast<float>(yRes[i]);
        gfSample.z = static_cast<float>(zRes[i]);
        result.gravityFreeSamples.push_back(gfSample);
    }

    return result;
}

SettlingWindowResult detectSettlingWindow(
    const GravityRemovalResult& gravResult,
    float sampleRateHz,
    float hpfCutoffHz,
    float gravityVarianceThreshold
) {
    int baseSettling = static_cast<int>(std::ceil(3.0f * sampleRateHz / hpfCutoffHz));
    int maxSettling = static_cast<int>(10.0f * sampleRateHz);
    int windowSamples = static_cast<int>(1.0f * sampleRateHz);

    const auto& gravVecs = gravResult.estimatedGravityVectors;
    int n = gravVecs.size();

    if (n <= baseSettling) {
        return {std::min(baseSettling, n), 0, false};
    }

    int stabilizedAt = baseSettling;
    int settling = baseSettling;

    for (int start = baseSettling; start < std::min(maxSettling, n); ++start) {
        if (start < windowSamples) continue;
        int windowStart = start - windowSamples;
        double sum = 0.0;
        double sumSq = 0.0;
        for (int j = windowStart; j < start; ++j) {
            const auto& gv = gravVecs[j];
            double mag = std::sqrt(gv[0] * gv[0] + gv[1] * gv[1] + gv[2] * gv[2]);
            sum += mag;
            sumSq += mag * mag;
        }
        double mean = sum / windowSamples;
        double variance = (sumSq / windowSamples) - mean * mean;

        if (variance < gravityVarianceThreshold) {
            stabilizedAt = start;
            settling = start;
            break;
        }
        settling = start;
    }

    return {settling, stabilizedAt, settling > baseSettling};
}

float streamingRmsLevel(const AccelerationSample& sample, float prevRms, float smoothingAlpha) {
    float mag = std::sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z);
    return smoothingAlpha * mag + (1.0f - smoothingAlpha) * prevRms;
}

std::vector<float> applyIir(
    const std::vector<float>& input,
    double b0, double b1, double b2,
    double a1, double a2,
    double zi1 = 0.0, double zi2 = 0.0
) {
    std::vector<float> output(input.size());
    double z1 = zi1;
    double z2 = zi2;

    for (size_t i = 0; i < input.size(); ++i) {
        double x = static_cast<double>(input[i]);
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        output[i] = static_cast<float>(y);
    }
    return output;
}

std::vector<float> highPassFilterFiltfilt(
    const std::vector<float>& signal,
    float sampleRateHz,
    float cutoffHz
) {
    if (signal.size() < 9) return signal;

    double wc = std::tan(std::numbers::pi * static_cast<double>(cutoffHz) / static_cast<double>(sampleRateHz));
    double wc2 = wc * wc;
    double sqrt2wc = std::sqrt(2.0) * wc;
    double k = 1.0 + sqrt2wc + wc2;

    double b0 = 1.0 / k;
    double b1 = -2.0 / k;
    double b2 = 1.0 / k;
    double a1 = 2.0 * (wc2 - 1.0) / k;
    double a2 = (1.0 - sqrt2wc + wc2) / k;

    // Initial conditions (lfilter_zi) for step response
    double sumA = 1.0 + a1 + a2;
    double zi1 = (b1 + b2 - (a1 + a2) * b0) / sumA;
    double zi2 = b2 - a2 * b0 - a2 * zi1;

    // Reflection padding (odd extension)
    int padlen = std::min(15, static_cast<int>(signal.size()) - 1);
    std::vector<float> padded(signal.size() + 2 * padlen);
    float x0 = signal[0];
    for (int i = 0; i < padlen; ++i) {
        padded[i] = 2.0f * x0 - signal[padlen - i];
    }
    std::copy(signal.begin(), signal.end(), padded.begin() + padlen);
    float xN = signal.back();
    for (int i = 0; i < padlen; ++i) {
        padded[padlen + signal.size() + i] = 2.0f * xN - signal[signal.size() - 2 - i];
    }

    // Forward pass
    double z1_fwd = zi1 * static_cast<double>(padded[0]);
    double z2_fwd = zi2 * static_cast<double>(padded[0]);
    std::vector<float> forward = applyIir(padded, b0, b1, b2, a1, a2, z1_fwd, z2_fwd);

    // Reverse
    std::reverse(forward.begin(), forward.end());

    // Backward pass
    double z1_bwd = zi1 * static_cast<double>(forward[0]);
    double z2_bwd = zi2 * static_cast<double>(forward[0]);
    std::vector<float> backward = applyIir(forward, b0, b1, b2, a1, a2, z1_bwd, z2_bwd);

    // Reverse back
    std::reverse(backward.begin(), backward.end());

    // Slice center
    return std::vector<float>(backward.begin() + padlen, backward.begin() + padlen + signal.size());
}

void detrendInPlace(std::vector<double>& data) {
    int n = data.size();
    if (n < 2) return;

    double sumI = 0.0, sumX = 0.0, sumIX = 0.0, sumI2 = 0.0;
    for (int i = 0; i < n; ++i) {
        double iD = i;
        double xD = data[i];
        sumI += iD;
        sumX += xD;
        sumIX += iD * xD;
        sumI2 += iD * iD;
    }

    double denom = n * sumI2 - sumI * sumI;
    if (std::abs(denom) < 1e-12) return;

    double slope = (n * sumIX - sumI * sumX) / denom;
    double intercept = (sumX - slope * sumI) / n;

    for (int i = 0; i < n; ++i) {
        data[i] -= (slope * i + intercept);
    }
}

std::vector<Peak> findPeaks(const std::vector<float>& frequencies, const std::vector<float>& psd) {
    int n = psd.size();
    if (n < 3) return {};

    int localWindowHalf = 20;
    int guardBand = 2;

    auto extractPeaks = [&](float minSnrDb) {
        std::vector<Peak> resultPeaks;
        for (int k = 1; k < n - 1; ++k) {
            if (psd[k] > psd[k - 1] && psd[k] > psd[k + 1]) {
                std::vector<float> localBins;
                for (int j = std::max(0, k - localWindowHalf); j <= std::min(n - 1, k + localWindowHalf); ++j) {
                    if (std::abs(j - k) > guardBand) localBins.push_back(psd[j]);
                }
                if (localBins.empty()) continue;
                std::sort(localBins.begin(), localBins.end());
                float localMedian = localBins[localBins.size() / 2];
                if (std::isnan(psd[k]) || std::isinf(psd[k]) || psd[k] <= 0.0f) continue;
                
                float safeNoise = std::max(localMedian, 1e-30f);
                float peakDb = 10.0f * std::log10(psd[k]);
                float noiseDb = 10.0f * std::log10(safeNoise);
                float snrDb = peakDb - noiseDb;
                
                if (snrDb > minSnrDb) {
                    float leftMin = psd[k];
                    for (int j = k - 1; j >= 0; --j) {
                        if (psd[j] < leftMin) leftMin = psd[j];
                        if (j < k - 1 && psd[j] > psd[j + 1]) break;
                    }

                    float rightMin = psd[k];
                    for (int j = k + 1; j < n; ++j) {
                        if (psd[j] < rightMin) rightMin = psd[j];
                        if (j > k + 1 && psd[j] > psd[j - 1]) break;
                    }

                    float referenceLevel = std::max((leftMin + rightMin) / 2.0f, 1e-30f);
                    float prominence = psd[k] / referenceLevel;

                    // Parabolic Interpolation (Log-magnitude for Hanning window)
                    float alpha = std::log(std::max(psd[k - 1], 1e-30f));
                    float beta = std::log(std::max(psd[k], 1e-30f));
                    float gamma = std::log(std::max(psd[k + 1], 1e-30f));

                    float denom = alpha - 2.0f * beta + gamma;
                    float interpFreq = frequencies[k];
                    float interpMag = psd[k];

                    if (std::abs(denom) > 1e-12f) {
                        float p = 0.5f * (alpha - gamma) / denom;
                        if (p >= -1.0f && p <= 1.0f) {
                            float deltaF = frequencies[k + 1] - frequencies[k];
                            interpFreq = frequencies[k] + p * deltaF;
                            float interpLogMag = beta - 0.25f * (alpha - gamma) * p;
                            interpMag = std::exp(interpLogMag);
                        }
                    }

                    resultPeaks.push_back({interpFreq, interpMag, prominence});
                }
            }
        }
        return resultPeaks;
    };

    std::vector<Peak> peaks = extractPeaks(1.0f);
    if (peaks.empty()) {
        peaks = extractPeaks(0.1f);
    }
    if (peaks.empty()) {
        peaks = extractPeaks(0.0f);
    }

    std::sort(peaks.begin(), peaks.end(), [](const Peak& a, const Peak& b) {
        return a.prominence > b.prominence;
    });

    if (peaks.size() > 20) {
        peaks.resize(20);
    }
    return peaks;
}

std::vector<float> welchPsdSingleAxis(
    const std::vector<float>& signal,
    int fftSize,
    float overlapFraction,
    float sampleRateHz,
    const std::vector<uint8_t>& gapMask
) {
    int n = signal.size();
    int stepSize = std::max(1, static_cast<int>(fftSize * (1.0f - overlapFraction)));
    int freqBins = fftSize / 2 + 1;

    std::vector<double> psdAccumulator(freqBins, 0.0);

    std::vector<double> window(fftSize);
    double windowPowerSum = 0.0;
    double twoPiOverNm1 = 2.0 * std::numbers::pi / (fftSize - 1);
    for (int i = 0; i < fftSize; ++i) {
        double w = 0.5 * (1.0 - std::cos(twoPiOverNm1 * i));
        window[i] = w;
        windowPowerSum += w * w;
    }

    // Pass 1: Compute RMS energy for all candidate segments to establish baseline
    std::vector<double> segmentRms;
    std::vector<bool> segmentHasGap;
    int scanOffset = 0;
    while (scanOffset + fftSize <= n) {
        bool hasGap = false;
        if (!gapMask.empty()) {
            for (int i = 0; i < fftSize; ++i) {
                if (gapMask[scanOffset + i] != 0) {
                    hasGap = true;
                    break;
                }
            }
        }
        segmentHasGap.push_back(hasGap);

        double sumSq = 0.0;
        for (int i = 0; i < fftSize; ++i) {
            double v = signal[scanOffset + i];
            sumSq += v * v;
        }
        segmentRms.push_back(std::sqrt(sumSq / fftSize));
        scanOffset += stepSize;
    }

    if (segmentRms.empty()) return std::vector<float>(freqBins, 0.0f);

    // Robust Median and MAD of Segment RMS
    std::vector<double> sortedRms = segmentRms;
    std::sort(sortedRms.begin(), sortedRms.end());
    double medianRms = sortedRms[sortedRms.size() / 2];

    std::vector<double> deviations(sortedRms.size());
    for (size_t i = 0; i < sortedRms.size(); ++i) {
        deviations[i] = std::abs(sortedRms[i] - medianRms);
    }
    std::sort(deviations.begin(), deviations.end());
    double madRms = deviations[deviations.size() / 2];

    // Threshold: Reject segments with RMS > Median + 3.0 * (1.4826 * MAD)
    double rmsThreshold = std::max(medianRms + 3.0 * (1.4826 * madRms), medianRms * 2.5);

    int segmentCount = 0;
    int offset = 0;
    size_t segIdx = 0;

    auto& backend = fft::getDefaultBackend();

    std::vector<double> segment(fftSize);
    std::vector<double> real(fftSize);
    std::vector<double> imag(fftSize);

    while (offset + fftSize <= n) {
        bool hasGap = segmentHasGap[segIdx];
        double currentRms = segmentRms[segIdx++];
        if (!hasGap && (segmentRms.size() < 3 || currentRms <= rmsThreshold)) {
            for (int i = 0; i < fftSize; ++i) {
                segment[i] = signal[offset + i];
            }
            detrendInPlace(segment);

            std::fill(imag.begin(), imag.end(), 0.0);
            for (int i = 0; i < fftSize; ++i) {
                real[i] = segment[i] * window[i];
            }

            backend.fft(real.data(), imag.data(), fftSize);

            for (int k = 0; k < freqBins; ++k) {
                psdAccumulator[k] += real[k] * real[k] + imag[k] * imag[k];
            }

            segmentCount++;
        }
        offset += stepSize;
    }

    // Safety fallback: if all segments were gated
    if (segmentCount == 0) {
        // First try to accumulate segments without gaps (if only RMS was high)
        offset = 0;
        segIdx = 0;
        while (offset + fftSize <= n) {
            bool hasGap = segmentHasGap[segIdx++];
            if (!hasGap) {
                for (int i = 0; i < fftSize; ++i) {
                    segment[i] = signal[offset + i];
                }
                detrendInPlace(segment);
                std::fill(imag.begin(), imag.end(), 0.0);
                for (int i = 0; i < fftSize; ++i) {
                    real[i] = segment[i] * window[i];
                }
                backend.fft(real.data(), imag.data(), fftSize);
                for (int k = 0; k < freqBins; ++k) {
                    psdAccumulator[k] += real[k] * real[k] + imag[k] * imag[k];
                }
                segmentCount++;
            }
            offset += stepSize;
        }

        // If still 0 (meaning all segments contained gaps), accumulate all segments as last resort
        if (segmentCount == 0) {
            offset = 0;
            while (offset + fftSize <= n) {
                for (int i = 0; i < fftSize; ++i) {
                    segment[i] = signal[offset + i];
                }
                detrendInPlace(segment);
                std::fill(imag.begin(), imag.end(), 0.0);
                for (int i = 0; i < fftSize; ++i) {
                    real[i] = segment[i] * window[i];
                }
                backend.fft(real.data(), imag.data(), fftSize);
                for (int k = 0; k < freqBins; ++k) {
                    psdAccumulator[k] += real[k] * real[k] + imag[k] * imag[k];
                }
                segmentCount++;
                offset += stepSize;
            }
        }
    }

    std::vector<float> psd(freqBins, 0.0f);
    if (segmentCount == 0) return psd;

    double normFactor = 1.0 / (sampleRateHz * windowPowerSum * segmentCount);

    psd[0] = static_cast<float>(psdAccumulator[0] * normFactor);
    for (int k = 1; k < freqBins - 1; ++k) {
        psd[k] = static_cast<float>(2.0 * psdAccumulator[k] * normFactor);
    }
    psd[freqBins - 1] = static_cast<float>(psdAccumulator[freqBins - 1] * normFactor);

    return psd;
}

MultiAxisSpectrumResult createEmptyResult(
    int freqBins, float sampleRateHz, int fftSize, const WelchPsdParams& params,
    const SettlingWindowResult* settling = nullptr
) {
    MultiAxisSpectrumResult result;
    result.output.actualSegmentCount = 0;
    result.output.effectiveDeltaFHz = sampleRateHz / fftSize;
    if (settling) {
        result.output.settlingWindow = *settling;
        result.output.hasSettlingWindow = true;
    } else {
        result.output.hasSettlingWindow = false;
    }

    std::vector<float> freqs(freqBins);
    for (int i = 0; i < freqBins; ++i) {
        freqs[i] = i * sampleRateHz / fftSize;
    }
    std::vector<float> emptyPsd(freqBins, 0.0f);

    AxisPsdResult emptyAxis{freqs, emptyPsd, {}};
    result.psdX = emptyAxis;
    result.psdY = emptyAxis;
    result.psdZ = emptyAxis;
    result.psdMagnitude = emptyAxis;

    return result;
}

MultiAxisSpectrumResult calculateMultiAxisWelchPsd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    const WelchPsdParams& params,
    const std::vector<uint8_t>& gapMask
) {
    int n = samples.size();
    int fftSize = params.fftSize;
    int freqBins = fftSize / 2 + 1;

    if (hasNanOrInf(samples)) {
        return createEmptyResult(freqBins, sampleRateHz, fftSize, params);
    }

    if (n < fftSize) {
        return createEmptyResult(freqBins, sampleRateHz, fftSize, params);
    }

    auto gravResult = removeGravityAndDetrend(samples);
    const auto& gfSamples = gravResult.gravityFreeSamples;

    auto settlingResult = detectSettlingWindow(gravResult, sampleRateHz);
    int settlingN = settlingResult.settlingDurationSamples;

    int usableN = n - settlingN;
    if (usableN < fftSize) {
        return createEmptyResult(freqBins, sampleRateHz, fftSize, params, &settlingResult);
    }

    std::vector<uint8_t> slicedGapMask;
    if (!gapMask.empty() && static_cast<int>(gapMask.size()) >= n) {
        slicedGapMask.assign(gapMask.begin() + settlingN, gapMask.begin() + settlingN + usableN);
    }

    std::vector<float> rawX(usableN), rawY(usableN), rawZ(usableN), rawMag(usableN);
    for (int i = 0; i < usableN; ++i) {
        const auto& s = gfSamples[i + settlingN];
        rawX[i] = s.x;
        rawY[i] = s.y;
        rawZ[i] = s.z;
        rawMag[i] = std::sqrt(s.x * s.x + s.y * s.y + s.z * s.z);
    }

    auto filtX = highPassFilterFiltfilt(rawX, sampleRateHz, 0.5f);
    auto filtY = highPassFilterFiltfilt(rawY, sampleRateHz, 0.5f);
    auto filtZ = highPassFilterFiltfilt(rawZ, sampleRateHz, 0.5f);
    auto filtMag = highPassFilterFiltfilt(rawMag, sampleRateHz, 0.5f);

    std::vector<float> frequencies(freqBins);
    for (int i = 0; i < freqBins; ++i) {
        frequencies[i] = i * sampleRateHz / fftSize;
    }

    auto psdX = welchPsdSingleAxis(filtX, fftSize, params.overlapPercentage, sampleRateHz, slicedGapMask);
    auto psdY = welchPsdSingleAxis(filtY, fftSize, params.overlapPercentage, sampleRateHz, slicedGapMask);
    auto psdZ = welchPsdSingleAxis(filtZ, fftSize, params.overlapPercentage, sampleRateHz, slicedGapMask);
    auto psdMag = welchPsdSingleAxis(filtMag, fftSize, params.overlapPercentage, sampleRateHz, slicedGapMask);

    auto peaksX = findPeaks(frequencies, psdX);
    auto peaksY = findPeaks(frequencies, psdY);
    auto peaksZ = findPeaks(frequencies, psdZ);
    auto peaksMag = findPeaks(frequencies, psdMag);

    int stepSize = static_cast<int>(fftSize * (1.0f - params.overlapPercentage));
    int segmentCount = (stepSize > 0) ? (usableN - fftSize) / stepSize + 1 : 1;

    MultiAxisSpectrumResult result;
    result.psdX = {frequencies, psdX, peaksX};
    result.psdY = {frequencies, psdY, peaksY};
    result.psdZ = {frequencies, psdZ, peaksZ};
    result.psdMagnitude = {frequencies, psdMag, peaksMag};

    result.output.actualSegmentCount = segmentCount;
    result.output.effectiveDeltaFHz = sampleRateHz / fftSize;
    result.output.settlingWindow = settlingResult;
    result.output.hasSettlingWindow = true;

    return result;
}

ResamplingResult resampleToUniformGrid(
    const std::vector<AccelerationSample>& samples,
    float targetSampleRateHz,
    InterpolationMode mode,
    double gapThresholdMs,
    double maxAllowedMissingRatio
) {
    ResamplingResult res;
    if (samples.empty()) {
        return res;
    }
    if (samples.size() == 1) {
        res.samples = samples;
        res.gapMask = {0};
        res.originalCount = 1;
        res.resampledCount = 1;
        res.isContinuityPassed = true;
        return res;
    }

    // Sort by timestamp if necessary
    std::vector<AccelerationSample> sorted = samples;
    if (!std::is_sorted(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) {
        return a.timestampNs < b.timestampNs;
    })) {
        std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) {
            return a.timestampNs < b.timestampNs;
        });
    }

    // Deduplicate consecutive identical timestamps
    std::vector<AccelerationSample> clean;
    clean.reserve(sorted.size());
    for (const auto& s : sorted) {
        if (clean.empty() || s.timestampNs > clean.back().timestampNs) {
            clean.push_back(s);
        }
    }

    int n = static_cast<int>(clean.size());
    res.originalCount = n;
    if (n < 2) {
        res.samples = clean;
        res.gapMask = std::vector<uint8_t>(n, 0);
        res.resampledCount = n;
        res.isContinuityPassed = true;
        return res;
    }

    int64_t startNs = clean.front().timestampNs;
    int64_t endNs = clean.back().timestampNs;
    double durationSec = (endNs - startNs) / 1e9;
    if (durationSec <= 0.0) {
        res.samples = clean;
        res.gapMask = std::vector<uint8_t>(n, 0);
        res.resampledCount = n;
        res.isContinuityPassed = true;
        return res;
    }

    // Measure gaps and continuity
    double maxGapMs = 0.0;
    double totalMissingTimeSec = 0.0;
    for (int i = 0; i + 1 < n; ++i) {
        double dtMs = (clean[i + 1].timestampNs - clean[i].timestampNs) / 1e6;
        if (dtMs > maxGapMs) {
            maxGapMs = dtMs;
        }
        if (dtMs > gapThresholdMs) {
            totalMissingTimeSec += (dtMs / 1000.0);
        }
    }

    res.maxGapMs = maxGapMs;
    res.totalMissingTimeSec = totalMissingTimeSec;
    res.missingTimeRatio = totalMissingTimeSec / durationSec;
    res.isContinuityPassed = (res.missingTimeRatio <= maxAllowedMissingRatio);

    int targetCount = static_cast<int>(durationSec * targetSampleRateHz) + 1;
    if (targetCount < 2) {
        res.samples = clean;
        res.gapMask = std::vector<uint8_t>(n, 0);
        res.resampledCount = n;
        return res;
    }

    res.resampledCount = targetCount;
    res.samples.resize(targetCount);
    res.gapMask.assign(targetCount, 0);

    double dtNsDouble = 1e9 / static_cast<double>(targetSampleRateHz);

    // Compute PCHIP derivatives if PCHIP_CUBIC
    std::vector<double> h(n - 1);
    std::vector<double> deltaX(n - 1), deltaY(n - 1), deltaZ(n - 1);
    for (int i = 0; i < n - 1; ++i) {
        h[i] = (clean[i + 1].timestampNs - clean[i].timestampNs) * 1e-9;
        deltaX[i] = (clean[i + 1].x - clean[i].x) / h[i];
        deltaY[i] = (clean[i + 1].y - clean[i].y) / h[i];
        deltaZ[i] = (clean[i + 1].z - clean[i].z) / h[i];
    }

    auto computeSlopes = [&](const std::vector<double>& delta) {
        std::vector<double> d(n, 0.0);
        if (n == 2) {
            d[0] = delta[0];
            d[1] = delta[0];
            return d;
        }
        for (int i = 1; i < n - 1; ++i) {
            if ((delta[i - 1] > 0.0 && delta[i] > 0.0) || (delta[i - 1] < 0.0 && delta[i] < 0.0)) {
                double w1 = 2.0 * h[i] + h[i - 1];
                double w2 = h[i] + 2.0 * h[i - 1];
                d[i] = (w1 + w2) / (w1 / delta[i - 1] + w2 / delta[i]);
            } else {
                d[i] = 0.0;
            }
        }
        // Endpoint 0
        d[0] = ((2.0 * h[0] + h[1]) * delta[0] - h[0] * delta[1]) / (h[0] + h[1]);
        if ((d[0] > 0.0) != (delta[0] > 0.0)) {
            d[0] = 0.0;
        } else if (((delta[0] > 0.0) != (delta[1] > 0.0)) && std::abs(d[0]) > 3.0 * std::abs(delta[0])) {
            d[0] = 3.0 * delta[0];
        }
        // Endpoint n - 1
        int last = n - 1;
        d[last] = ((2.0 * h[last - 1] + h[last - 2]) * delta[last - 1] - h[last - 1] * delta[last - 2]) / (h[last - 1] + h[last - 2]);
        if ((d[last] > 0.0) != (delta[last - 1] > 0.0)) {
            d[last] = 0.0;
        } else if (((delta[last - 1] > 0.0) != (delta[last - 2] > 0.0)) && std::abs(d[last]) > 3.0 * std::abs(delta[last - 1])) {
            d[last] = 3.0 * delta[last - 1];
        }
        return d;
    };

    std::vector<double> dX, dY, dZ;
    if (mode == InterpolationMode::PCHIP_CUBIC) {
        dX = computeSlopes(deltaX);
        dY = computeSlopes(deltaY);
        dZ = computeSlopes(deltaZ);
    }

    int srcIdx = 0;
    for (int k = 0; k < targetCount; ++k) {
        int64_t tTarget = startNs + static_cast<int64_t>(std::round(k * dtNsDouble));

        if (tTarget <= startNs) {
            res.samples[k] = {startNs, clean.front().x, clean.front().y, clean.front().z};
            res.gapMask[k] = 0;
            continue;
        }
        if (tTarget >= endNs) {
            res.samples[k] = {endNs, clean.back().x, clean.back().y, clean.back().z};
            res.gapMask[k] = 0;
            continue;
        }

        while (srcIdx < n - 2 && clean[srcIdx + 1].timestampNs < tTarget) {
            srcIdx++;
        }

        const auto& p1 = clean[srcIdx];
        const auto& p2 = clean[srcIdx + 1];
        int64_t gapNs = p2.timestampNs - p1.timestampNs;
        double dtMs = gapNs / 1e6;

        if (dtMs > gapThresholdMs) {
            // Gap > 50ms: mark gapMask = 1 if strictly between p1 and p2, and interpolate with linear bridge
            if (tTarget > p1.timestampNs && tTarget < p2.timestampNs) {
                res.gapMask[k] = 1;
            } else {
                res.gapMask[k] = 0;
            }
            double u = (gapNs > 0) ? static_cast<double>(tTarget - p1.timestampNs) / gapNs : 0.0;
            float ix = static_cast<float>(p1.x + u * (p2.x - p1.x));
            float iy = static_cast<float>(p1.y + u * (p2.y - p1.y));
            float iz = static_cast<float>(p1.z + u * (p2.z - p1.z));
            res.samples[k] = {tTarget, ix, iy, iz};
        } else {
            res.gapMask[k] = 0;
            if (mode == InterpolationMode::LINEAR) {
                double u = (gapNs > 0) ? static_cast<double>(tTarget - p1.timestampNs) / gapNs : 0.0;
                float ix = static_cast<float>(p1.x + u * (p2.x - p1.x));
                float iy = static_cast<float>(p1.y + u * (p2.y - p1.y));
                float iz = static_cast<float>(p1.z + u * (p2.z - p1.z));
                res.samples[k] = {tTarget, ix, iy, iz};
            } else {
                double hSec = gapNs * 1e-9;
                double u = (gapNs > 0) ? static_cast<double>(tTarget - p1.timestampNs) / gapNs : 0.0;
                double h00 = (1.0 + 2.0 * u) * (1.0 - u) * (1.0 - u);
                double h10 = u * (1.0 - u) * (1.0 - u) * hSec;
                double h01 = u * u * (3.0 - 2.0 * u);
                double h11 = u * u * (u - 1.0) * hSec;
                float ix = static_cast<float>(p1.x * h00 + dX[srcIdx] * h10 + p2.x * h01 + dX[srcIdx + 1] * h11);
                float iy = static_cast<float>(p1.y * h00 + dY[srcIdx] * h10 + p2.y * h01 + dY[srcIdx + 1] * h11);
                float iz = static_cast<float>(p1.z * h00 + dZ[srcIdx] * h10 + p2.z * h01 + dZ[srcIdx + 1] * h11);
                res.samples[k] = {tTarget, ix, iy, iz};
            }
        }
    }

    return res;
}

std::vector<TransientEventAnalysis> analyzeGatedTransients(
    const std::vector<float>& signal,
    float sampleRateHz,
    int segmentSize,
    float segmentOverlap,
    const std::vector<uint8_t>& gapMask
) {
    std::vector<TransientEventAnalysis> events;
    int n = static_cast<int>(signal.size());
    int segLen = std::max(256, segmentSize);
    if (n < segLen || sampleRateHz <= 0.0f) {
        return events;
    }

    int stepSize = std::max(1, static_cast<int>(segLen * (1.0f - segmentOverlap)));

    struct CandidateSegment {
        int startIdx;
        double rms;
        bool hasGap;
    };

    std::vector<CandidateSegment> candidates;
    int scanOffset = 0;
    while (scanOffset + segLen <= n) {
        bool hasGap = false;
        if (!gapMask.empty() && scanOffset + segLen <= static_cast<int>(gapMask.size())) {
            for (int i = 0; i < segLen; ++i) {
                if (gapMask[scanOffset + i] != 0) {
                    hasGap = true;
                    break;
                }
            }
        }
        double sumSq = 0.0;
        for (int i = 0; i < segLen; ++i) {
            double v = signal[scanOffset + i];
            sumSq += v * v;
        }
        candidates.push_back({scanOffset, std::sqrt(sumSq / segLen), hasGap});
        scanOffset += stepSize;
    }

    if (candidates.size() < 3) {
        return events;
    }

    std::vector<double> validRms;
    validRms.reserve(candidates.size());
    for (const auto& c : candidates) {
        if (!c.hasGap) {
            validRms.push_back(c.rms);
        }
    }
    if (validRms.size() < 3) {
        return events;
    }

    std::vector<double> sortedRms = validRms;
    std::sort(sortedRms.begin(), sortedRms.end());
    double medianRms = sortedRms[sortedRms.size() / 2];

    std::vector<double> deviations(sortedRms.size());
    for (size_t i = 0; i < sortedRms.size(); ++i) {
        deviations[i] = std::abs(sortedRms[i] - medianRms);
    }
    std::sort(deviations.begin(), deviations.end());
    double madRms = deviations[deviations.size() / 2];
    double rmsThreshold = std::max(medianRms + 3.0 * (1.4826 * madRms), medianRms * 2.5);

    std::vector<CandidateSegment> gated;
    for (const auto& c : candidates) {
        if (!c.hasGap && c.rms > rmsThreshold) {
            gated.push_back(c);
        }
    }
    if (gated.empty()) {
        return events;
    }

    // Sort by RMS descending and pick up to 5 non-overlapping transient windows
    std::sort(gated.begin(), gated.end(), [](const CandidateSegment& a, const CandidateSegment& b) {
        return a.rms > b.rms;
    });

    std::vector<CandidateSegment> selected;
    for (const auto& cand : gated) {
        bool overlaps = false;
        for (const auto& sel : selected) {
            if (std::abs(cand.startIdx - sel.startIdx) < segLen / 2) {
                overlaps = true;
                break;
            }
        }
        if (!overlaps) {
            selected.push_back(cand);
            if (selected.size() >= 5) break;
        }
    }

    // Order selected transient events chronologically
    std::sort(selected.begin(), selected.end(), [](const CandidateSegment& a, const CandidateSegment& b) {
        return a.startIdx < b.startIdx;
    });

    constexpr int stftWin = 256;
    constexpr int stftHop = 64; // 75% overlap
    constexpr int stftBins = stftWin / 2 + 1;

    std::vector<double> hanning(stftWin);
    double winPowerSum = 0.0;
    double twoPiOverNm1 = 2.0 * std::numbers::pi / (stftWin - 1);
    for (int i = 0; i < stftWin; ++i) {
        double w = 0.5 * (1.0 - std::cos(twoPiOverNm1 * i));
        hanning[i] = w;
        winPowerSum += w * w;
    }
    double scale = 1.0 / (static_cast<double>(sampleRateHz) * winPowerSum);

    auto& backend = fft::getDefaultBackend();
    std::vector<double> real(stftWin);
    std::vector<double> imag(stftWin);

    double onsetAmpThreshold = std::max(medianRms * 3.0, 1e-5);

    for (const auto& sel : selected) {
        TransientEventAnalysis ev;
        std::vector<float> segData(signal.begin() + sel.startIdx, signal.begin() + sel.startIdx + segLen);

        int onsetOffset = 0;
        bool foundOnset = false;
        float peakAbs = 0.0f;
        for (int i = 0; i < segLen; ++i) {
            float absVal = std::abs(segData[i]);
            if (absVal > peakAbs) {
                peakAbs = absVal;
            }
            if (!foundOnset && absVal > static_cast<float>(onsetAmpThreshold)) {
                onsetOffset = i;
                foundOnset = true;
            }
        }

        ev.onsetSampleIndex = sel.startIdx + onsetOffset;
        ev.peakAccelerationG = peakAbs;
        ev.rmsG = static_cast<float>(sel.rms);
        ev.durationMs = (static_cast<float>(segLen) * 1000.0f) / sampleRateHz;

        // 1. Daubechies db4 WPT (Depth J = 4, 16 subbands)
        WptAnalysisResult wpt = analyzeWaveletPacketDb4(segData, 4);
        ev.wptSubbandEnergies = wpt.normalizedEnergies;
        ev.waveletEntropy = wpt.shannonEntropy;
        ev.isStructuralShock = wpt.isStructuralShock;

        // 2. 256-point Hanning STFT with 75% overlap (64-sample hop)
        int timeSteps = (segLen - stftWin) / stftHop + 1;
        ev.stftTimeSteps = timeSteps;
        ev.stftFreqBins = stftBins;
        ev.stftSpectrogram.resize(static_cast<size_t>(timeSteps) * stftBins, 0.0f);

        for (int t = 0; t < timeSteps; ++t) {
            int frameStart = t * stftHop;
            std::fill(imag.begin(), imag.end(), 0.0);
            for (int i = 0; i < stftWin; ++i) {
                real[i] = static_cast<double>(segData[frameStart + i]) * hanning[i];
            }
            backend.fft(real.data(), imag.data(), stftWin);

            size_t rowOffset = static_cast<size_t>(t) * stftBins;
            for (int k = 0; k < stftBins; ++k) {
                double p = (real[k] * real[k] + imag[k] * imag[k]) * scale;
                if (k > 0 && k < stftBins - 1) {
                    p *= 2.0;
                }
                ev.stftSpectrogram[rowOffset + k] = static_cast<float>(p);
            }
        }

        events.push_back(std::move(ev));
    }

    return events;
}

} // namespace dsp
} // namespace phoneshm
