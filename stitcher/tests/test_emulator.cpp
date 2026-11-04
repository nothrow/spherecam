// Real emulator sessions: BA must register most frames. Outputs are kept in the build dir for visual review.
#include <filesystem>

#include "spherestitch/stitch.hpp"
#include "synth.hpp"

int main() {
    for (const char* name : {"nadir-1", "nadir-2"}) {
        std::string dir = std::string(TESTDATA) + "/emulator/" + name, out = std::string("emulator_") + name;
        spherestitch::Options o;
        o.width = 2048;
        o.debugDir = out + "_debug";
        std::filesystem::create_directories(o.debugDir);
        auto r = spherestitch::stitch(dir, out + ".jpg", o);
        CHECK(r.ok, "%s: %s", name, r.error.c_str());
        spherestitch::writeReport(r, o.debugDir + "/report.json");
        int nba = 0;
        double dev = 0;
        for (auto& c : r.cameras) {
            nba += c.source == "bundle-adjusted";
            dev = std::max(dev, c.deviationDeg);
        }
        std::printf("%s: %s, %d/%zu bundle-adjusted, residual %.3f deg, max deviation %.2f deg, focal %.1f\n", name,
                    r.rotationSource.c_str(), nba, r.cameras.size(), r.baRayResidualDeg, dev, r.focal);
        // nadir-2 has a ~40 deg sensor yaw jump between frames 7 and 8 (capture-side issue, phase 1); its pairs
        // across the jump must be rejected, so only nadir-1 is held to the registration rate.
        if (std::string(name) == "nadir-1")
            CHECK(nba >= (int)r.cameras.size() * 8 / 10, "%s: too few frames registered", name);
    }
    std::printf("OK\n");
}
