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
    if (usableN < 256) return result; // Minimum required
    if (usableN < fftSize) {
        // Adjust fftSize to highest power of 2 <= usableN
        int newFftSize = 256;
        while (newFftSize * 2 <= usableN) {
            newFftSize *= 2;
        }
        fftSize = newFftSize;
        freqBins = fftSize / 2 + 1;
        result.frequencies.resize(freqBins);
        result.firstSingularValues.resize(freqBins, 0.0f);
        for (int i = 0; i < freqBins; ++i) {
            result.frequencies[i] = i * sampleRateHz / fftSize;
        }
    }

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
    
    auto peaks = findPeaks(result.frequencies, result.firstSingularValues);
    for (const auto& p : peaks) {
        EfddMode mode;
        mode.frequencyHz = p.frequencyHz;
        mode.powerMagnitude = p.powerMagnitude;
        mode.prominence = p.prominence;
        mode.dampingRatio = 0.0f;

        // 1. Find SDOF bell limits
        int k = -1;
        for (int i = 0; i < freqBins; ++i) {
            if (std::abs(result.frequencies[i] - p.frequencyHz) < 1e-4) {
                k = i;
                break;
            }
        }
        
        if (k > 0) {
            int L = k, R = k;
            while (L > 0 && result.firstSingularValues[L-1] < result.firstSingularValues[L]) L--;
            while (R < freqBins - 1 && result.firstSingularValues[R+1] < result.firstSingularValues[R]) R++;
            
            // 2. Build two-sided spectrum for IDFT
            std::vector<double> realX(fftSize, 0.0), imagX(fftSize, 0.0);
            for (int i = L; i <= R; ++i) {
                realX[i] = result.firstSingularValues[i];
                if (i > 0 && i < fftSize / 2) {
                    realX[fftSize - i] = result.firstSingularValues[i];
                }
            }
            
            // 3. IDFT (using FFT)
            phoneshm::fft::getDefaultBackend().fft(realX.data(), imagX.data(), fftSize);
            for (int i = 0; i < fftSize; ++i) {
                realX[i] /= fftSize; // IFFT scaling
            }
            
            // 4. Extract Free Decay Envelope Peaks
            std::vector<double> peakTimes, peakVals;
            peakTimes.push_back(0.0);
            peakVals.push_back(realX[0]);
            
            for (int i = 1; i < fftSize / 2 - 1; ++i) {
                if (realX[i] > realX[i-1] && realX[i] > realX[i+1] && realX[i] > 0) {
                    peakTimes.push_back(i / sampleRateHz);
                    peakVals.push_back(realX[i]);
                    if (realX[i] < 0.1 * realX[0] || peakVals.size() > 15) break;
                }
            }
            
            // 5. Log-decrement linear regression
            if (peakVals.size() >= 3) {
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                int n_peaks = peakVals.size();
                for (int i = 0; i < n_peaks; ++i) {
                    double x = peakTimes[i];
                    double y = std::log(peakVals[i]);
                    sumX += x; sumY += y;
                    sumXY += x * y; sumX2 += x * x;
                }
                double denom = n_peaks * sumX2 - sumX * sumX;
                if (std::abs(denom) > 1e-12) {
                    double slope = (n_peaks * sumXY - sumX * sumY) / denom;
                    double omega_n = 2.0 * std::numbers::pi * p.frequencyHz;
                    mode.dampingRatio = static_cast<float>(-slope / omega_n);
                    if (mode.dampingRatio < 0) mode.dampingRatio = 0.01f;
                }
            }
        }
        result.modes.push_back(mode);
    }


    return result;
}

}
}
