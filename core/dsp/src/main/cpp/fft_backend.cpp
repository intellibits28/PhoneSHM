#include "fft_backend.h"
#include <cmath>
#include <stdexcept>
#include <numbers>

namespace phoneshm {
namespace fft {

void CooleyTukeyBackend::fft(double* real, double* imag, int n) {
    if (n <= 0 || (n & (n - 1)) != 0) {
        throw std::invalid_argument("FFT size must be a positive power of 2");
    }

    // Bit-reversal permutation
    int j = 0;
    for (int i = 1; i < n; ++i) {
        int bit = n >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            double tempRe = real[i];
            real[i] = real[j];
            real[j] = tempRe;
            
            double tempIm = imag[i];
            imag[i] = imag[j];
            imag[j] = tempIm;
        }
    }

    // Butterfly computation
    for (int len = 2; len <= n; len <<= 1) {
        int halfLen = len / 2;
        double angle = -2.0 * std::numbers::pi / len;
        double wRe = std::cos(angle);
        double wIm = std::sin(angle);

        for (int i = 0; i < n; i += len) {
            double curRe = 1.0;
            double curIm = 0.0;

            for (int k = 0; k < halfLen; ++k) {
                int even = i + k;
                int odd = i + k + halfLen;

                double tRe = curRe * real[odd] - curIm * imag[odd];
                double tIm = curRe * imag[odd] + curIm * real[odd];

                real[odd] = real[even] - tRe;
                imag[odd] = imag[even] - tIm;
                real[even] += tRe;
                imag[even] += tIm;

                double newRe = curRe * wRe - curIm * wIm;
                curIm = curRe * wIm + curIm * wRe;
                curRe = newRe;
            }
        }
    }
}

IFftBackend& getDefaultBackend() {
    static CooleyTukeyBackend backend;
    return backend;
}

} // namespace fft
} // namespace phoneshm
