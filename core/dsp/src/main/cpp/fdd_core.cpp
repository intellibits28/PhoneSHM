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
using Matrix3cdSafe = Eigen::Matrix<std::complex<double>, 3, 3, Eigen::DontAlign>;

FddResult calculateFdd(
    const std::vector<AccelerationSample>& samples,
    float sampleRateHz,
    int fftSize,
    float overlapPct,
    const std::vector<uint8_t>& gapMask
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

    std::vector<uint8_t> slicedGapMask;
    if (!gapMask.empty() && static_cast<int>(gapMask.size()) >= n) {
        slicedGapMask.assign(gapMask.begin() + settlingN, gapMask.begin() + settlingN + usableN);
    }

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
    result.coherenceXY.resize(freqBins, 0.0f);
    result.coherenceXZ.resize(freqBins, 0.0f);
    result.coherenceYZ.resize(freqBins, 0.0f);
    result.hvRatio.resize(freqBins, 0.0f);
    for (int i = 0; i < freqBins; ++i) {
        result.frequencies[i] = i * sampleRateHz / fftSize;
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

    // Pass 1: Compute RMS energy for all candidate segments to establish baseline
    std::vector<double> segmentRms;
    std::vector<bool> segmentHasGap;
    int scanOffset = 0;
    while (scanOffset + fftSize <= usableN) {
        bool hasGap = false;
        if (!slicedGapMask.empty()) {
            for (int i = 0; i < fftSize; ++i) {
                if (slicedGapMask[scanOffset + i] != 0) {
                    hasGap = true;
                    break;
                }
            }
        }
        segmentHasGap.push_back(hasGap);

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

    // Phase 4: 3x3 CSD matrices for each frequency bin across (X, Y, Z)
    std::vector<Matrix3cdSafe> G3(freqBins, Matrix3cdSafe::Zero());
    int segmentCount = 0;
    int offset = 0;
    size_t segIdx = 0;

    std::vector<double> realX(fftSize), imagX(fftSize);
    std::vector<double> realY(fftSize), imagY(fftSize);
    std::vector<double> realZ(fftSize), imagZ(fftSize);

    auto accumulateSegment3x3 = [&](int segStart) {
        for (int i = 0; i < fftSize; ++i) {
            realX[i] = filtX[segStart + i] * window[i]; imagX[i] = 0.0;
            realY[i] = filtY[segStart + i] * window[i]; imagY[i] = 0.0;
            realZ[i] = filtZ[segStart + i] * window[i]; imagZ[i] = 0.0;
        }

        detrendInPlace(realX);
        detrendInPlace(realY);
        detrendInPlace(realZ);

        auto& backend = phoneshm::fft::getDefaultBackend();
        backend.fft(realX.data(), imagX.data(), fftSize);
        backend.fft(realY.data(), imagY.data(), fftSize);
        backend.fft(realZ.data(), imagZ.data(), fftSize);

        for (int k = 0; k < freqBins; ++k) {
            std::complex<double> X(realX[k], imagX[k]);
            std::complex<double> Y(realY[k], imagY[k]);
            std::complex<double> Z(realZ[k], imagZ[k]);

            G3[k](0, 0) += X * std::conj(X);
            G3[k](0, 1) += X * std::conj(Y);
            G3[k](0, 2) += X * std::conj(Z);
            G3[k](1, 0) += Y * std::conj(X);
            G3[k](1, 1) += Y * std::conj(Y);
            G3[k](1, 2) += Y * std::conj(Z);
            G3[k](2, 0) += Z * std::conj(X);
            G3[k](2, 1) += Z * std::conj(Y);
            G3[k](2, 2) += Z * std::conj(Z);
        }
        segmentCount++;
    };

    while (offset + fftSize <= usableN) {
        bool hasGap = segmentHasGap[segIdx];
        double currentRms = segmentRms[segIdx++];
        if (!hasGap && (segmentRms.size() < 3 || currentRms <= rmsThreshold)) {
            accumulateSegment3x3(offset);
        }
        offset += stepSize;
    }

    // Safety fallback: if all segments were gated
    if (segmentCount == 0) {
        // First try to accumulate segments without gaps
        offset = 0;
        segIdx = 0;
        while (offset + fftSize <= usableN) {
            bool hasGap = segmentHasGap[segIdx++];
            if (!hasGap) {
                accumulateSegment3x3(offset);
            }
            offset += stepSize;
        }

        // If still 0 (meaning all segments contained gaps), accumulate all segments as last resort
        if (segmentCount == 0) {
            offset = 0;
            while (offset + fftSize <= usableN) {
                accumulateSegment3x3(offset);
                offset += stepSize;
            }
        }
    }

    if (segmentCount == 0) return result;

    double normFactor = 1.0 / (static_cast<double>(sampleRateHz) * windowPowerSum * segmentCount);

    std::vector<Vector2cdSafe> modeShapes(freqBins);

    // SVD on 2x2 horizontal sub-block and tri-axial coherence on 3x3 G3(k)
    for (int k = 0; k < freqBins; ++k) {
        G3[k] *= normFactor;
        if (k > 0 && k < freqBins - 1) {
            G3[k] *= 2.0; // One-sided spectrum compensation
        }

        Matrix2cdSafe Gxy = G3[k].block<2, 2>(0, 0);
        Eigen::JacobiSVD<Matrix2cdSafe> svd(Gxy, Eigen::ComputeFullU);
        auto singularValues = svd.singularValues();
        double sv1D = singularValues(0);
        if (std::isnan(sv1D) || std::isinf(sv1D) || sv1D < 0.0) {
            sv1D = 0.0;
        }
        result.firstSingularValues[k] = static_cast<float>(sv1D);
        modeShapes[k] = svd.matrixU().col(0);

        double sxx = std::max(0.0, G3[k](0, 0).real());
        double syy = std::max(0.0, G3[k](1, 1).real());
        double szz = std::max(0.0, G3[k](2, 2).real());

        double cXY = std::clamp(std::norm(G3[k](0, 1)) / std::max(sxx * syy, 1e-30), 0.0, 1.0);
        double cXZ = std::clamp(std::norm(G3[k](0, 2)) / std::max(sxx * szz, 1e-30), 0.0, 1.0);
        double cYZ = std::clamp(std::norm(G3[k](1, 2)) / std::max(syy * szz, 1e-30), 0.0, 1.0);
        double hv = sv1D / std::max(szz, 1e-30);

        result.coherenceXY[k] = static_cast<float>(cXY);
        result.coherenceXZ[k] = static_cast<float>(cXZ);
        result.coherenceYZ[k] = static_cast<float>(cYZ);
        result.hvRatio[k] = static_cast<float>(std::min(hv, 1e9));
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
            mode.coherenceXY = result.coherenceXY[k];
            mode.coherenceXZ = result.coherenceXZ[k];
            mode.coherenceYZ = result.coherenceYZ[k];
            mode.hvRatio = result.hvRatio[k];

            double azRad = std::atan2(refShape(1).real(), refShape(0).real());
            mode.azimuthDegrees = static_cast<float>(azRad * 180.0 / std::numbers::pi);
            mode.isValidHorizontalSway = (mode.hvRatio > 3.0f) &&
                                         (mode.coherenceXZ <= 0.35f) &&
                                         (mode.coherenceYZ <= 0.35f);
            
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
