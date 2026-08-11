#include "fdd_core.h"
#include "fft_backend.h"
#include <Eigen/Dense>
#include <Eigen/SVD>
#include <cmath>
#include <numbers>
#include <iostream>

namespace phoneshm {
namespace dsp {

FddResult calculateFdd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    int fftSize,
    float overlapPct
) {
    int n = samples.size();
    int freqBins = fftSize / 2 + 1;
    FddResult result;
    result.frequencies.resize(freqBins);
    result.firstSingularValues.resize(freqBins, 0.0f);

    if (n < fftSize) return result;

    for (int i = 0; i < freqBins; ++i) {
        result.frequencies[i] = i * sampleRateHz / fftSize;
    }

    // Detrend and filter
    auto gravResult = removeGravityAndDetrend(samples);
    auto settlingResult = detectSettlingWindow(gravResult, sampleRateHz);
    int settlingN = settlingResult.settlingDurationSamples;
    int usableN = n - settlingN;
    if (usableN < fftSize) return result;

    std::vector<float> rawX(usableN), rawY(usableN), rawZ(usableN);
    for (int i = 0; i < usableN; ++i) {
        const auto& s = gravResult.gravityFreeSamples[i + settlingN];
        rawX[i] = s.x; rawY[i] = s.y; rawZ[i] = s.z;
    }

    auto filtX = highPassFilterFiltfilt(rawX, sampleRateHz, 0.5f);
    auto filtY = highPassFilterFiltfilt(rawY, sampleRateHz, 0.5f);
    auto filtZ = highPassFilterFiltfilt(rawZ, sampleRateHz, 0.5f);

    int stepSize = static_cast<int>(fftSize * (1.0f - overlapPct));
    if (stepSize < 1) stepSize = 1;

    // Hanning window
    std::vector<float> window(fftSize);
    float windowPowerSum = 0.0f;
    for (int i = 0; i < fftSize; ++i) {
        window[i] = 0.5f * (1.0f - std::cos(2.0f * std::numbers::pi * i / (fftSize - 1)));
        windowPowerSum += window[i] * window[i];
    }

    // CSD matrices for each frequency bin: 3x3 complex matrix
    std::vector<Eigen::Matrix3cd> G(freqBins, Eigen::Matrix3cd::Zero());
    int segmentCount = 0;
    int offset = 0;

    std::vector<double> realX(fftSize), imagX(fftSize);
    std::vector<double> realY(fftSize), imagY(fftSize);
    std::vector<double> realZ(fftSize), imagZ(fftSize);

    while (offset + fftSize <= usableN) {
        for (int i = 0; i < fftSize; ++i) {
            realX[i] = filtX[offset + i] * window[i]; imagX[i] = 0.0;
            realY[i] = filtY[offset + i] * window[i]; imagY[i] = 0.0;
            realZ[i] = filtZ[offset + i] * window[i]; imagZ[i] = 0.0;
        }

        detrendInPlace(realX);
        detrendInPlace(realY);
        detrendInPlace(realZ);

        phoneshm::fft::getDefaultBackend().fft(realX.data(), imagX.data(), fftSize);
        phoneshm::fft::getDefaultBackend().fft(realY.data(), imagY.data(), fftSize);
        phoneshm::fft::getDefaultBackend().fft(realZ.data(), imagZ.data(), fftSize);

        for (int k = 0; k < freqBins; ++k) {
            std::complex<double> X(realX[k], imagX[k]);
            std::complex<double> Y(realY[k], imagY[k]);
            std::complex<double> Z(realZ[k], imagZ[k]);

            // Build G(f) outer product: [X Y Z]^T * conj([X Y Z])
            G[k](0, 0) += X * std::conj(X);
            G[k](0, 1) += X * std::conj(Y);
            G[k](0, 2) += X * std::conj(Z);
            
            G[k](1, 0) += Y * std::conj(X);
            G[k](1, 1) += Y * std::conj(Y);
            G[k](1, 2) += Y * std::conj(Z);

            G[k](2, 0) += Z * std::conj(X);
            G[k](2, 1) += Z * std::conj(Y);
            G[k](2, 2) += Z * std::conj(Z);
        }

        segmentCount++;
        offset += stepSize;
    }

    if (segmentCount == 0) return result;

    float normFactor = 1.0f / (sampleRateHz * windowPowerSum * segmentCount);

    // SVD on each G(k)
    for (int k = 0; k < freqBins; ++k) {
        // Normalize
        G[k] *= normFactor;
        if (k > 0 && k < freqBins - 1) {
            G[k] *= 2.0; // One-sided spectrum compensation
        }

        Eigen::JacobiSVD<Eigen::Matrix3cd> svd(G[k]);
        auto singularValues = svd.singularValues(); // sorted in decreasing order
        result.firstSingularValues[k] = static_cast<float>(singularValues(0));
    }

    // Peak picking on the first singular value curve
    result.modes = findPeaks(result.frequencies, result.firstSingularValues);

    return result;
}

}
}
