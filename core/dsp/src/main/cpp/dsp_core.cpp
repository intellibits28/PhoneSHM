#include "dsp_core.h"
#include "fft_backend.h"
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
    float b0, float b1, float b2,
    float a1, float a2
) {
    std::vector<float> output(input.size());
    float w1 = 0.0f, w2 = 0.0f;

    for (size_t i = 0; i < input.size(); ++i) {
        float w0 = input[i] - a1 * w1 - a2 * w2;
        output[i] = b0 * w0 + b1 * w1 + b2 * w2;
        w2 = w1;
        w1 = w0;
    }
    return output;
}

std::vector<float> highPassFilterFiltfilt(
    const std::vector<float>& signal,
    float sampleRateHz,
    float cutoffHz
) {
    if (signal.size() < 3) return signal;

    double wc = std::tan(std::numbers::pi * cutoffHz / sampleRateHz);
    double wc2 = wc * wc;
    double sqrt2wc = std::sqrt(2.0) * wc;
    double k = 1.0 + sqrt2wc + wc2;

    float b0 = static_cast<float>(1.0 / k);
    float b1 = static_cast<float>(-2.0 / k);
    float b2 = static_cast<float>(1.0 / k);
    float a1 = static_cast<float>(2.0 * (wc2 - 1.0) / k);
    float a2 = static_cast<float>((1.0 - sqrt2wc + wc2) / k);

    std::vector<float> forward = applyIir(signal, b0, b1, b2, a1, a2);
    std::reverse(forward.begin(), forward.end());
    std::vector<float> backward = applyIir(forward, b0, b1, b2, a1, a2);
    std::reverse(backward.begin(), backward.end());

    return backward;
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

    std::vector<float> sorted(psd);
    std::sort(sorted.begin(), sorted.end());
    float median = sorted[n / 2];
    float threshold = median * 3.0f;

    std::vector<Peak> peaks;

    for (int k = 1; k < n - 1; ++k) {
        if (psd[k] > psd[k - 1] && psd[k] > psd[k + 1] && psd[k] > threshold) {
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

            peaks.push_back({interpFreq, interpMag, prominence});
        }
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
    float sampleRateHz
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

    int segmentCount = 0;
    int offset = 0;

    auto& backend = fft::getDefaultBackend();

    while (offset + fftSize <= n) {
        std::vector<double> segment(fftSize);
        for (int i = 0; i < fftSize; ++i) {
            segment[i] = signal[offset + i];
        }
        detrendInPlace(segment);

        std::vector<double> real(fftSize);
        std::vector<double> imag(fftSize, 0.0);
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
    const WelchPsdParams& params
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

    auto psdX = welchPsdSingleAxis(filtX, fftSize, params.overlapPercentage, sampleRateHz);
    auto psdY = welchPsdSingleAxis(filtY, fftSize, params.overlapPercentage, sampleRateHz);
    auto psdZ = welchPsdSingleAxis(filtZ, fftSize, params.overlapPercentage, sampleRateHz);
    auto psdMag = welchPsdSingleAxis(filtMag, fftSize, params.overlapPercentage, sampleRateHz);

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

} // namespace dsp
} // namespace phoneshm
