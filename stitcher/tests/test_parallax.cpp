// Handheld capture with the camera in front of the body axis: the lever model must recover the scene, and do
// clearly better than treating the frames as pure rotations.
#include "spherestitch/stitch.hpp"
#include "synth.hpp"

int main() {
    auto sp = synth::smallSpec();
    sp.bodyOffset = 0.35;
    sp.noiseMinDeg = 0.5;
    sp.noiseMaxDeg = 1.5;
    sp.yawDriftDeg = 3;
    auto s = synth::generate(synth::tmpDir("parallax"), sp);
    double mae[2];
    for (int lever = 0; lever < 2; ++lever) {
        spherestitch::Options o;
        o.width = 1024;
        o.parallax = lever;
        std::string out = s.dir + "/out" + std::to_string(lever) + ".jpg";
        auto r = spherestitch::stitch(s.dir, out, o);
        CHECK(r.ok, "%s", r.error.c_str());
        std::vector<cv::Matx33d> est;
        for (auto& c : r.cameras) est.push_back(c.rotation);
        cv::Matx33d G = synth::globalRotation(s.truth, est);
        mae[lever] = synth::mae(cv::imread(out), synth::renderEquirect(s.texture, 1024, G));
        std::printf("parallax %d: %s (%s), body offset %.3f %.3f %.3f, depth model %d, mae %.2f, rot err %.3f\n",
                    lever, r.rotationSource.c_str(), r.fallbackReason.c_str(), r.bodyOffset[0], r.bodyOffset[1],
                    r.bodyOffset[2], !r.depth.empty(), mae[lever], synth::maxErrorAfterAlignment(est, s.truth));
        if (lever) {
            // The offset is extreme for the floor 1.2 below the pivot; a frame or two may find no matches.
            std::vector<cv::Matx33d> ba, truth;
            for (size_t i = 0; i < r.cameras.size(); ++i)
                if (r.cameras[i].source == "bundle-adjusted") {
                    ba.push_back(est[i]);
                    truth.push_back(s.truth[i]);
                }
            double err = synth::maxErrorAfterAlignment(ba, truth);
            std::printf("  %zu/%zu bundle-adjusted, their rot err %.3f\n", ba.size(), est.size(), err);
            CHECK(ba.size() + 2 >= est.size(), "too few frames registered");
            CHECK(err < 1.5, "rotation error %.3f", err);
            CHECK(!r.depth.empty(), "no depth model");
            // Median scene distance is ~1.6, so the 0.35 offset is ~0.2 in scene units.
            CHECK(r.bodyOffset[0] > 0.1 && r.bodyOffset[0] < 0.35, "body offset %.3f", r.bodyOffset[0]);
        }
    }
    CHECK(mae[1] < 0.8 * mae[0], "lever model not better: %.2f vs %.2f", mae[1], mae[0]);
    std::printf("OK\n");
}
