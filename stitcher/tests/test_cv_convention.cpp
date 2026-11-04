// Proves how cv::detail::CameraParams::R relates to the manifest's R_world_from_camera, using OpenCV's own
// estimator on images rendered from known rotations (nothing from core is involved).
#include <opencv2/features.hpp>
#include <opencv2/stitching/detail/matchers.hpp>
#include <opencv2/stitching/detail/motion_estimators.hpp>

#include "synth.hpp"

namespace dt = cv::detail;

int main() {
    auto sp = synth::smallSpec();
    sp.shots.resize(9);  // the horizon ring is enough and keeps all-pairs matching cheap
    auto s = synth::generate(synth::tmpDir("cv_convention"), sp);
    const int n = (int)sp.shots.size();
    std::vector<dt::ImageFeatures> feat(n);
    auto sift = cv::SIFT::create(2000);
    for (int i = 0; i < n; ++i) {
        char name[32];
        std::snprintf(name, sizeof name, "/img_%03d.jpg", i);
        dt::computeImageFeatures(sift, cv::imread(s.dir + name), feat[i]);
        feat[i].img_idx = i;
    }
    std::vector<dt::MatchesInfo> pm;
    dt::BestOf2NearestMatcher(false, 0.3f, 6, 6, 1e9)(feat, pm);
    std::vector<dt::CameraParams> cams;
    CHECK(dt::HomographyBasedEstimator()(feat, pm, cams), "estimator failed");
    dt::BundleAdjusterRay ba;
    // The estimator's own focal guess is rough; the convention is the question here, not focal estimation.
    const double f = sp.width / 2.0 / std::tan(sp.hfovDeg / 2 * synth::kPi / 180);
    for (auto& c : cams) {
        c.R.convertTo(c.R, CV_32F);
        c.focal = f;
        c.ppx = sp.width / 2.0;
        c.ppy = sp.height / 2.0;
    }
    CHECK(ba(feat, pm, cams), "BA failed");

    std::vector<cv::Matx33d> asIs, transposed;
    for (auto& c : cams) {
        cv::Mat r;
        c.R.convertTo(r, CV_64F);
        asIs.push_back(cv::Matx33d(r));
        transposed.push_back(cv::Matx33d(r).t());
    }
    double e1 = synth::maxErrorAfterAlignment(asIs, s.truth);
    double e2 = synth::maxErrorAfterAlignment(transposed, s.truth);
    std::printf("R = G * R_world_from_camera: %.3f deg; R = G * R_camera_from_world: %.3f deg\n", e1, e2);
    CHECK(e1 < 0.3, "CameraParams::R is not R_world_from_camera up to a global rotation");
    CHECK(e2 > 5, "test cannot tell the two conventions apart");
    std::printf("OK\n");
}
