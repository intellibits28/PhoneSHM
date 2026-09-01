#include "rdt_ssi_core.h"
#include "fft_backend.h"
#include <Eigen/Dense>
#include <Eigen/Eigenvalues>
#include <cmath>
#include <algorithm>
#include <iostream>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace phoneshm {
namespace dsp {

static int nextPowerOf2(int n) {
    if (n <= 0) return 1;
    int p = 1;
    while (p < n) {
        p *= 2;
    }
    return p;
}

std::vector<RdtSsiPole> calculateRdtSsi(
    const float* x, const float* y, const float* z, int length,
    float fs, float minHz, float maxHz, int maxModelOrder, float rdsDurationSec)
{
    if (length <= 0) return {};

    double mx = 0, my = 0, mz = 0;
    for (int i = 0; i < length; ++i) {
        mx += x[i]; my += y[i]; mz += z[i];
    }
    mx /= length; my /= length; mz /= length;

    int nFft = nextPowerOf2(length);
    std::vector<double> rx(nFft, 0.0), ix(nFft, 0.0);
    std::vector<double> ry(nFft, 0.0), iy(nFft, 0.0);
    std::vector<double> rz(nFft, 0.0), iz(nFft, 0.0);

    for (int i = 0; i < length; ++i) {
        rx[i] = x[i] - mx;
        ry[i] = y[i] - my;
        rz[i] = z[i] - mz;
    }

    auto& fft = phoneshm::fft::getDefaultBackend();
    fft.fft(rx.data(), ix.data(), nFft);
    fft.fft(ry.data(), iy.data(), nFft);
    fft.fft(rz.data(), iz.data(), nFft);

    double df = (double)fs / nFft;
    for (int i = 0; i < nFft; ++i) {
        double freq = i * df;
        if (freq > fs / 2.0) {
            freq = fs - freq; // mirror frequency
        }
        if (freq < minHz || freq > maxHz) {
            rx[i] = 0.0; ix[i] = 0.0;
            ry[i] = 0.0; iy[i] = 0.0;
            rz[i] = 0.0; iz[i] = 0.0;
        }
    }

    // IFFT via conjugate
    for (int i = 0; i < nFft; ++i) {
        ix[i] = -ix[i];
        iy[i] = -iy[i];
        iz[i] = -iz[i];
    }
    fft.fft(rx.data(), ix.data(), nFft);
    fft.fft(ry.data(), iy.data(), nFft);
    fft.fft(rz.data(), iz.data(), nFft);

    for (int i = 0; i < nFft; ++i) {
        rx[i] /= nFft;
        ry[i] /= nFft;
        rz[i] /= nFft;
    }

    double varX = 0, varY = 0, varZ = 0;
    for (int i = 0; i < length; ++i) {
        varX += rx[i] * rx[i];
        varY += ry[i] * ry[i];
        varZ += rz[i] * rz[i];
    }
    varX /= length; varY /= length; varZ /= length;

    const std::vector<double>* refPtr = &rx;
    double maxVar = varX;
    int refAxis = 0;
    if (varY > maxVar) { maxVar = varY; refPtr = &ry; refAxis = 1; }
    if (varZ > maxVar) { maxVar = varZ; refPtr = &rz; refAxis = 2; }

    double sigma = std::sqrt(maxVar);
    
    int rdsLength = static_cast<int>(rdsDurationSec * fs);
    if (rdsLength <= 0) return {};
    if (rdsLength > length) rdsLength = length;

    std::vector<double> rdsX(rdsLength, 0.0);
    std::vector<double> rdsY(rdsLength, 0.0);
    std::vector<double> rdsZ(rdsLength, 0.0);
    int triggerCount = 0;

    for (int lvlIdx = 0; lvlIdx < 10; ++lvlIdx) {
        double level = sigma * (1.0 + 0.1 * lvlIdx);
        for (int i = 1; i <= length - rdsLength; ++i) {
            if ((*refPtr)[i - 1] < level && (*refPtr)[i] >= level) {
                for (int j = 0; j < rdsLength; ++j) {
                    rdsX[j] += rx[i + j];
                    rdsY[j] += ry[i + j];
                    rdsZ[j] += rz[i + j];
                }
                triggerCount++;
            }
        }
    }

    if (triggerCount > 0) {
        for (int j = 0; j < rdsLength; ++j) {
            rdsX[j] /= triggerCount;
            rdsY[j] /= triggerCount;
            rdsZ[j] /= triggerCount;
        }
    } else {
        return {};
    }

    double maxRef = 0.0;
    const std::vector<double>* outRefPtr = &rdsX;
    if (refAxis == 1) outRefPtr = &rdsY;
    if (refAxis == 2) outRefPtr = &rdsZ;

    for (int j = 0; j < rdsLength; ++j) {
        if (std::abs((*outRefPtr)[j]) > maxRef) {
            maxRef = std::abs((*outRefPtr)[j]);
        }
    }

    if (maxRef > 0.0) {
        for (int j = 0; j < rdsLength; ++j) {
            rdsX[j] /= maxRef;
            rdsY[j] /= maxRef;
            rdsZ[j] /= maxRef;
        }
    }

    int i_dim = maxModelOrder + 10;
    if (rdsLength < 2 * i_dim) {
        return {};
    }

    Eigen::MatrixXf H(3 * i_dim, i_dim);
    for (int r = 0; r < i_dim; ++r) {
        for (int c = 0; c < i_dim; ++c) {
            int idx = 1 + r + c;
            H(3 * r + 0, c) = static_cast<float>(rdsX[idx]);
            H(3 * r + 1, c) = static_cast<float>(rdsY[idx]);
            H(3 * r + 2, c) = static_cast<float>(rdsZ[idx]);
        }
    }

    Eigen::BDCSVD<Eigen::MatrixXf> svd(H, Eigen::ComputeThinU | Eigen::ComputeThinV);
    Eigen::MatrixXf U = svd.matrixU();
    Eigen::VectorXf S = svd.singularValues();

    std::vector<RdtSsiPole> results;

    for (int order = 2; order <= maxModelOrder; order += 2) {
        if (order > U.cols() || order > S.size()) break;
        
        Eigen::MatrixXf Un = U.leftCols(order);
        Eigen::VectorXf Sn = S.head(order);
        Eigen::MatrixXf Sn_sqrt = Sn.cwiseSqrt().asDiagonal();
        
        Eigen::MatrixXf O = Un * Sn_sqrt;
        
        int rows = O.rows();
        if (rows <= 3) continue;
        
        Eigen::MatrixXf O_up = O.topRows(rows - 3);
        Eigen::MatrixXf O_down = O.bottomRows(rows - 3);
        
        Eigen::MatrixXf A = O_up.bdcSvd(Eigen::ComputeThinU | Eigen::ComputeThinV).solve(O_down);
        
        Eigen::EigenSolver<Eigen::MatrixXf> solver(A);
        Eigen::VectorXcf evals = solver.eigenvalues();
        
        for (int k = 0; k < evals.size(); ++k) {
            std::complex<float> lambda = evals[k];
            std::complex<float> s = std::log(lambda) * fs;
            float sigma_c = s.real();
            float omega_c = s.imag();
            
            float f = std::abs(omega_c) / (2.0f * static_cast<float>(M_PI));
            float damp = -sigma_c / std::sqrt(sigma_c * sigma_c + omega_c * omega_c);
            
            if (f > 0.1f && f < fs / 2.0f && damp > 0.005f && damp < 0.15f) {
                if (omega_c > 0.0f) {
                    results.push_back({f, damp, order});
                }
            }
        }
    }
    
    return results;
}

} // namespace dsp
} // namespace phoneshm
