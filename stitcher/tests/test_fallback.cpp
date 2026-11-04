// Frames that cannot be registered keep their sensor rotation; the stitch still completes and says so.
#include <fstream>
#include <sstream>

#include "spherestitch/stitch.hpp"
#include "synth.hpp"

int main() {
    spherestitch::Options o;
    o.width = 1024;
    {
        auto sp = synth::smallSpec();
        sp.textureless = 5;
        auto s = synth::generate(synth::tmpDir("fallback_flat"), sp);
        auto r = spherestitch::stitch(s.dir, s.dir + "/out.jpg", o);
        CHECK(r.ok, "%s", r.error.c_str());
        CHECK(r.rotationSource == "mixed", "%s", r.rotationSource.c_str());
        for (auto& c : r.cameras)
            CHECK((c.index == 5) == (c.source == "sensor"), "camera %d: %s (%s)", c.index, c.source.c_str(),
                  c.reason.c_str());
        spherestitch::writeReport(r, s.dir + "/out.json");
        std::stringstream ss;
        ss << std::ifstream(s.dir + "/out.json").rdbuf();
        CHECK(ss.str().find("\"no-consistent-matches\"") != std::string::npos, "report: %s", ss.str().c_str());
        std::printf("textureless frame: %s, reason %s\n", r.cameras[5].source.c_str(), r.cameras[5].reason.c_str());
    }
    {
        // Swapped pixels: matches exist but contradict the sensor geometry.
        auto sp = synth::smallSpec();
        sp.swapImages = {2, 3};
        auto s = synth::generate(synth::tmpDir("fallback_swap"), sp);
        auto r = spherestitch::stitch(s.dir, s.dir + "/out.jpg", o);
        CHECK(r.ok, "%s", r.error.c_str());
        for (auto& c : r.cameras)
            CHECK((c.index == 2 || c.index == 3) == (c.source == "sensor"), "camera %d: %s (%s)", c.index,
                  c.source.c_str(), c.reason.c_str());
        int rejected = 0;
        for (auto& p : r.pairs) rejected += !p.accepted && p.relativeErrorDeg > o.maxRelativeRotationDeg && p.inliers > 20;
        CHECK(rejected > 0, "no pair was rejected by the sensor consistency check");
        std::printf("swapped frames: %d pairs rejected by consistency check\n", rejected);
    }
    {
        auto s = synth::generate(synth::tmpDir("fallback_cancel"), synth::smallSpec());
        auto r = spherestitch::stitch(s.dir, s.dir + "/out.jpg", o,
                                      [](spherestitch::Phase p, double) { return p != spherestitch::Phase::Matching; });
        CHECK(r.cancelled && !r.ok, "not cancelled");
        CHECK(!std::filesystem::exists(s.dir + "/out.jpg"), "cancelled stitch wrote output");
    }
    std::printf("OK\n");
}
