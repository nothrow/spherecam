// No nadir frames: the bottom hole is filled, output stays a full 2:1 image.
#include "spherestitch/stitch.hpp"
#include "synth.hpp"

int main() {
    const int W = 1024, H = W / 2;
    auto s = synth::generate(synth::tmpDir("nonadir"), synth::smallSpec(false));
    spherestitch::Options o;
    o.width = W;
    o.debugDir = s.dir + "/debug";
    std::filesystem::create_directories(o.debugDir);
    auto r = spherestitch::stitch(s.dir, s.dir + "/out.jpg", o);
    CHECK(r.ok, "%s", r.error.c_str());
    cv::Mat out = cv::imread(s.dir + "/out.jpg"), cov = cv::imread(o.debugDir + "/coverage.png", cv::IMREAD_GRAYSCALE);
    CHECK(out.cols == W && out.rows == H, "size %dx%d", out.cols, out.rows);
    double hole = 1 - cv::countNonZero(cov.rowRange(H - H / 20, H)) / (double)(W * (H / 20));
    CHECK(hole > 0.9, "test session unexpectedly covers the nadir (%.2f uncovered)", hole);

    cv::Mat gray;
    cv::cvtColor(out.rowRange(H - H / 10, H), gray, cv::COLOR_BGR2GRAY);
    double mn;
    cv::minMaxLoc(gray, &mn);
    CHECK(mn > 10, "unfilled pixels at the bottom (min %.0f)", mn);
    double e = synth::mae(out, synth::renderEquirect(s.texture, W), 256, cv::Range(0, H * 3 / 4));
    std::printf("bottom min %.0f, mae above the hole %.2f\n", mn, e);
    CHECK(e < 6, "covered part differs from source");
    std::printf("OK\n");
}
