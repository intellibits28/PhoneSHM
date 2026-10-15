#include "fdd_core.h"
#include "fft_backend.h"
#include <Eigen/Dense>
#include <Eigen/SVD>
#include <cmath>
#include <numbers>
#include <iostream>

namespace phoneshm {
namespace dsp {

using Matrix2cdSafe = Eigen::Matrix<std::complex<double>, 2, 2, Eigen::DontAlign>;
using Vector2cdSafe = Eigen::Matrix<std::complex<double>, 2, 1, Eigen::DontAlign>;

FddResult calculateFdd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    int fftSize,
    float overlapPct
) {
    int n = samples.size();
    FddResult result;
    if (n < 256) return result;

    // Detrend and filter
    auto gravResult = removeGravityAndDetrend(samples);
    auto settlingResult = detectSettlingWindow(gravResult, sampleRateHz);
    int settlingN = settlingResult.settlingDurationSamples;
    int usableN = n - settlingN;
    if (usableN < 256 && n >= 256) {
        settlingN = std::max(0, n - 256);
        usableN = n - settlingN;
    }
    if (usableN < 256) return result; // Minimum required

    // Adjust fftSize to highest power of 2 <= usableN (minimum 256)
    if (usableN < fftSize) {
        int newFftSize = 256;
        while (newFftSize * 2 <= usableN) {
            newFftSize *= 2;
        }
        fftSize = newFftSize;
    }

    int freqBins = fftSize / 2 + 1;
    result.frequencies.resize(freqBins);
    result.firstSingularValues.resize(freqBins, 0.0f);
    for (int i = 0; i < freqBins; ++i) {
        result.frequencies[i] = i * sampleRateHz / fftSize;
    }

    std::vector<float> rawX(usableN), rawY(usableN);
    for (int i = 0; i < usableN; ++i) {
        const auto& s = gravResult.gravityFreeSamples[i + settlingN];
        rawX[i] = s.x; rawY[i] = s.y;
    }

    auto filtX = highPassFilterFiltfilt(rawX, sampleRateHz, 0.5f);
    auto filtY = highPassFilterFiltfilt(rawY, sampleRateHz, 0.5f);

    int stepSize = static_cast<int>(fftSize * (1.0f - overlapPct));
    if (stepSize < 1) stepSize = 1;

    // Hanning window
    std::vector<float> window(fftSize);
    float windowPowerSum = 0.0f;
    for (int i = 0; i < fftSize; ++i) {
        window[i] = 0.5f * (1.0f - std::cos(2.0f * std::numbers::pi * i / (fftSize - 1)));
        windowPowerSum += window[i] * window[i];
    }

    // Pass 1: Compute RMS energy for all candidate segments to establish baseline
    std::vector<double> segmentRms;
    int scanOffset = 0;
    while (scanOffset + fftSize <= usableN) {
        double sumSq = 0.0;
        for (int i = 0; i < fftSize; ++i) {
            double vx = filtX[scanOffset + i];
            double vy = filtY[scanOffset + i];
            sumSq += vx * vx + vy * vy;
        }
        segmentRms.push_back(std::sqrt(sumSq / (2.0 * fftSize)));
        scanOffset += stepSize;
    }

    if (segmentRms.empty()) return result;

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

    // CSD matrices for each frequency bin: 2x2 complex matrix for horizontal sway
    std::vector<Matrix2cdSafe> G(freqBins, Matrix2cdSafe::Zero());
    int segmentCount = 0;
    int offset = 0;
    size_t segIdx = 0;

    std::vector<double> realX(fftSize), imagX(fftSize);
    std::vector<double> realY(fftSize), imagY(fftSize);

    while (offset + fftSize <= usableN) {
        double currentRms = segmentRms[segIdx++];
        if (segmentRms.size() < 3 || currentRms <= rmsThreshold) {
            for (int i = 0; i < fftSize; ++i) {
                realX[i] = filtX[offset + i] * window[i]; imagX[i] = 0.0;
                realY[i] = filtY[offset + i] * window[i]; imagY[i] = 0.0;
            }

            detrendInPlace(realX);
            detrendInPlace(realY);

            phoneshm::fft::getDefaultBackend().fft(realX.data(), imagX.data(), fftSize);
            phoneshm::fft::getDefaultBackend().fft(realY.data(), imagY.data(), fftSize);

            for (int k = 0; k < freqBins; ++k) {
                std::complex<double> X(realX[k], imagX[k]);
                std::complex<double> Y(realY[k], imagY[k]);

                // Build 2x2 G(f) outer product: [X Y]^T * conj([X Y])
                G[k](0, 0) += X * std::conj(X);
                G[k](0, 1) += X * std::conj(Y);
                G[k](1, 0) += Y * std::conj(X);
                G[k](1, 1) += Y * std::conj(Y);
            }

            segmentCount++;
        }
        offset += stepSize;
    }

    // Safety fallback: if all segments were gated, accumulate without gating
    if (segmentCount == 0) {
        offset = 0;
        while (offset + fftSize <= usableN) {
            for (int i = 0; i < fftSize; ++i) {
                realX[i] = filtX[offset + i] * window[i]; imagX[i] = 0.0;
                realY[i] = filtY[offset + i] * window[i]; imagY[i] = 0.0;
            }
            detrendInPlace(realX);
            detrendInPlace(realY);

            phoneshm::fft::getDefaultBackend().fft(realX.data(), imagX.data(), fftSize);
            phoneshm::fft::getDefaultBackend().fft(realY.data(), imagY.data(), fftSize);

            for (int k = 0; k < freqBins; ++k) {
                std::complex<double> X(realX[k], imagX[k]);
                std::complex<double> Y(realY[k], imagY[k]);

                G[k](0, 0) += X * std::conj(X);
                G[k](0, 1) += X * std::conj(Y);
                G[k](1, 0) += Y * std::conj(X);
                G[k](1, 1) += Y * std::conj(Y);
            }

            segmentCount++;
            offset += stepSize;
        }
    }

    if (segmentCount == 0) return result;

    float normFactor = 1.0f / (sampleRateHz * windowPowerSum * segmentCount);

    std::vector<Vector2cdSafe> modeShapes(freqBins);

    // SVD on each 2x2 G(k)
    for (int k = 0; k < freqBins; ++k) {
        // Normalize
        G[k] *= normFactor;
        if (k > 0 && k < freqBins - 1) {
            G[k] *= 2.0; // One-sided spectrum compensation
        }

        Eigen::JacobiSVD<Matrix2cdSafe> svd(G[k], Eigen::ComputeFullU);
        auto singularValues = svd.singularValues(); // sorted in decreasing order
        float sv1 = static_cast<float>(singularValues(0));
        if (std::isnan(sv1) || std::isinf(sv1) || sv1 < 0.0f) {
            sv1 = 0.0f;
        }
        result.firstSingularValues[k] = sv1;
        modeShapes[k] = svd.matrixU().col(0);
    }

    // Peak picking on the first singular value curve
    auto peaks = findPeaks(result.frequencies, result.firstSingularValues);
    for (const auto& p : peaks) {
        EfddMode mode;
        mode.frequencyHz = p.frequencyHz;
        mode.powerMagnitude = p.powerMagnitude;
        mode.prominence = p.prominence;
        mode.dampingRatio = 0.0f;

        // 1. Find the peak bin index
        int k = -1;
        float minDiff = 1e9f;
        for (int i = 0; i < freqBins; ++i) {
            float diff = std::abs(result.frequencies[i] - p.frequencyHz);
            if (diff < minDiff) {
                minDiff = diff;
                k = i;
            }
        }
        
        if (k > 0) {
            Vector2cdSafe refShape = modeShapes[k];
            
            auto computeMAC = [](const Vector2cdSafe& u1, const Vector2cdSafe& u2) {
                return static_cast<float>(std::norm(u1.dot(u2)));
            };

            int L = k, R = k;
            float macThreshold = 0.85f;
            
            // Phase 3: Advanced Modal Validation - MAC-based SDOF Bell Selection
            while (L > 0) {
                float mac = computeMAC(refShape, modeShapes[L-1]);
                if (mac < macThreshold) break;
                // Still enforce monotonic descent to prevent grabbing adjacent modes with similar shapes
                if (result.firstSingularValues[L-1] > result.firstSingularValues[L]) break;
                L--;
            }
            while (R < freqBins - 1) {
                float mac = computeMAC(refShape, modeShapes[R+1]);
                if (mac < macThreshold) break;
                if (result.firstSingularValues[R+1] > result.firstSingularValues[R]) break;
                R++;
            }
            
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
            double firstPeakVal = -1.0;
            
            for (int i = 1; i < fftSize / 2 - 1; ++i) {
                if (realX[i] > realX[i-1] && realX[i] > realX[i+1] && realX[i] > 0) {
                    if (firstPeakVal < 0.0) {
                        firstPeakVal = realX[i];
                    }
                    peakTimes.push_back(static_cast<double>(i) / sampleRateHz);
                    peakVals.push_back(realX[i]);
                    // Compare against 0.15 * firstPeakVal instead of realX[0] to avoid premature cutoff
                    if (firstPeakVal > 0.0 && realX[i] < 0.15 * firstPeakVal) break;
                    if (peakVals.size() >= 15) break;
                }
            }
            if (peakVals.size() < 3 && firstPeakVal > 0.0) {
                peakTimes.insert(peakTimes.begin(), 0.0);
                peakVals.insert(peakVals.begin(), std::max(firstPeakVal, realX[0]));
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
                    if (mode.dampingRatio < 0.001f) mode.dampingRatio = 0.01f;
                    if (mode.dampingRatio > 0.20f) mode.dampingRatio = 0.20f;
                }
            }
        }
        result.modes.push_back(mode);
    }

    return result;
}

}
}
