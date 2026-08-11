#include "fdd_core.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <string>

using namespace phoneshm::dsp;

int main(int argc, char* argv[]) {
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <csv_file>" << std::endl;
        return 1;
    }

    std::string filename = argv[1];
    std::ifstream file(filename);
    if (!file.is_open()) {
        std::cerr << "Failed to open " << filename << std::endl;
        return 1;
    }

    std::vector<AccelerationSample> samples;
    std::string line;
    // Skip header if exists
    std::getline(file, line);

    while (std::getline(file, line)) {
        std::stringstream ss(line);
        std::string token;
        AccelerationSample s;
        
        // CSV format: timestamp, x, y, z
        if (std::getline(ss, token, ',')) s.timestampNs = std::stoll(token);
        if (std::getline(ss, token, ',')) s.x = std::stof(token);
        if (std::getline(ss, token, ',')) s.y = std::stof(token);
        if (std::getline(ss, token, ',')) s.z = std::stof(token);
        
        samples.push_back(s);
    }

    std::cout << "Loaded " << samples.size() << " samples." << std::endl;

    float sampleRateHz = 100.0f; // Assuming 100Hz
    int fftSize = 1024;
    float overlapPct = 0.5f;

    FddResult result = calculateFdd(samples, sampleRateHz, fftSize, overlapPct);

    std::cout << "FDD Analysis Complete!" << std::endl;
    std::cout << "Found " << result.modes.size() << " modes:" << std::endl;
    for (const auto& mode : result.modes) {
        std::cout << "  - Frequency: " << mode.frequencyHz << " Hz (Mag: " << mode.powerMagnitude << ", Prominence: " << mode.prominence << ")" << std::endl;
    }

    std::ofstream out("efdd_spectrum.csv");
    out << "FrequencyHz,SV1\n";
    for (size_t i = 0; i < result.frequencies.size(); ++i) {
        out << result.frequencies[i] << "," << result.firstSingularValues[i] << "\n";
    }
    out.close();
    std::cout << "Saved spectrum to efdd_spectrum.csv" << std::endl;

    return 0;
}
