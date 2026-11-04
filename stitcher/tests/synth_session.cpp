// Writes a synthetic session to disk, e.g. for memory/time measurement of the CLI.
#include "synth.hpp"

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: synth_session <dir> small|small-nonadir|large [noise_deg]\n");
        return 2;
    }
    std::string kind = argv[2];
    synth::Spec sp = kind == "large" ? synth::largeSpec() : synth::smallSpec(kind != "small-nonadir");
    if (argc > 3) {
        sp.noiseMaxDeg = std::atof(argv[3]);
        sp.noiseMinDeg = sp.noiseMaxDeg / 3;
    }
    synth::generate(argv[1], sp);
    std::printf("wrote %s (%zu frames)\n", argv[1], sp.shots.size());
}
