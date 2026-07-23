#pragma once

namespace phoneshm {
namespace fft {

class IFftBackend {
public:
    virtual ~IFftBackend() = default;
    virtual void fft(double* real, double* imag, int n) = 0;
};

// Default radix-2 Cooley-Tukey implementation
class CooleyTukeyBackend : public IFftBackend {
public:
    void fft(double* real, double* imag, int n) override;
};

// Global backend accessor
IFftBackend& getDefaultBackend();

} // namespace fft
} // namespace phoneshm
