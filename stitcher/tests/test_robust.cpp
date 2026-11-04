// Sensor rotations off by 1-3 deg plus a slow yaw drift (like the emulator): BA must recover the truth.
#include "spherestitch/stitch.hpp"
#include "synth.hpp"

static double run(const synth::Spec& sp, const char* name, double& rotErr, double& sensorErr) {
    auto s = synth::generate(synth::tmpDir(name), sp);
    spherestitch::Options o;
    o.width = 1024;
    auto r = spherestitch::stitch(s.dir, s.dir + "/out.jpg", o);
    CHECK(r.ok, "%s", r.error.c_str());
    CHECK(r.rotationSource == "bundle-adjusted", "%s %s", r.rotationSource.c_str(), r.fallbackReason.c_str());
    std::vector<cv::Matx33d> est;
    for (auto& c : r.cameras) est.push_back(c.rotation);
    rotErr = synth::maxErrorAfterAlignment(est, s.truth);
    sensorErr = 0;
    for (size_t i = 0; i < r.cameras.size(); ++i) sensorErr = std::max(sensorErr, r.cameras[i].deviationDeg);
    // The output frame follows the (noisy) sensors on average, so compare in that frame.
    cv::Matx33d G = synth::globalRotation(s.truth, est);
    std::printf("%s: output frame is %.2f deg from the true world frame\n", name,
                synth::angleDeg(G, cv::Matx33d::eye()));
    return synth::mae(cv::imread(s.dir + "/out.jpg"), synth::renderEquirect(s.texture, 1024, G));
}

int main() {
    double clean, noisy, rotClean, rotNoisy, devClean, devNoisy;
    clean = run(synth::smallSpec(), "robust_clean", rotClean, devClean);
    auto sp = synth::smallSpec();
    sp.noiseMinDeg = 1;
    sp.noiseMaxDeg = 3;
    sp.yawDriftDeg = 3;
    sp.seed = 7;
    noisy = run(sp, "robust_noisy", rotNoisy, devNoisy);
    std::printf("clean: mae %.2f rot err %.3f | noisy: mae %.2f rot err %.3f (max BA-vs-sensor %.2f deg)\n", clean,
                rotClean, noisy, rotNoisy, devNoisy);
    // Global rotation is unobservable from images; it is compared after removing the best-fit one.
    CHECK(rotNoisy < 0.3, "BA error %.3f deg", rotNoisy);
    CHECK(devNoisy > 1, "noise was not applied");
    CHECK(noisy < clean + 1.0, "noisy output much worse than clean");
    std::printf("OK\n");
}
