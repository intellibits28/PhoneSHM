#ifndef PHONESHM_WPT_FEATURE_EXTRACTOR_H
#define PHONESHM_WPT_FEATURE_EXTRACTOR_H

#include <vector>
#include <string>

namespace phoneshm {
namespace audio {

struct WptFeatures {
    std::vector<float> nodeEnergies;
    std::string eventLabel;
    double confidence;
};

class WptFeatureExtractor {
public:
    // Fixed decomposition depth 4-6 recommended for 16kHz
    explicit WptFeatureExtractor(int depth = 5);
    ~WptFeatureExtractor() = default;

    // Takes raw PCM float array (e.g. from -2s to +3s trigger window)
    // Downsamples internally to 16kHz before decomposition.
    // Returns extracted node energies and classification.
    WptFeatures extractNodeEnergies(const std::vector<float>& rawPcm, int nativeSampleRate);

private:
    int m_depth;
    
    // Daubechies db4 coefficients
    std::vector<float> m_db4_h; // Low-pass
    std::vector<float> m_db4_g; // High-pass

    void initFilters();
    std::vector<float> downsample(const std::vector<float>& input, int nativeRate, int targetRate);
    std::vector<float> convolveAndDownsample(const std::vector<float>& input, const std::vector<float>& filter);
    
    // Computes full WPT tree up to m_depth and returns leaf node energies
    std::vector<float> computeWptEnergies(const std::vector<float>& signal);
    
    // Optional best-basis selection (Shannon entropy)
    // Currently disabled / backlog feature to save compute cost
    bool m_useBestBasis = false;
    float computeShannonEntropy(const std::vector<float>& signal);
};

} // namespace audio
} // namespace phoneshm

#endif // PHONESHM_WPT_FEATURE_EXTRACTOR_H
