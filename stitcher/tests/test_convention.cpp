// Exact rotations: output must reproduce the source sphere in the reference equirect mapping.
#include "spherestitch/stitch.hpp"
#include "synth.hpp"

int main() {
    const int W = 1024, H = W / 2;
    auto s = synth::generate(synth::tmpDir("convention"), synth::smallSpec());
    spherestitch::Options o;
    o.width = W;
    auto r = spherestitch::stitch(s.dir, s.dir + "/out.jpg", o);
    CHECK(r.ok, "%s", r.error.c_str());
    CHECK(r.rotationSource == "bundle-adjusted", "%s %s", r.rotationSource.c_str(), r.fallbackReason.c_str());

    std::vector<cv::Matx33d> est;
    for (auto& c : r.cameras) est.push_back(c.rotation);
    double rotErr = synth::maxErrorAfterAlignment(est, s.truth);
    CHECK(rotErr < 0.1, "BA moved exact rotations by %.3f deg", rotErr);

    cv::Mat out = cv::imread(s.dir + "/out.jpg"), truth = synth::renderEquirect(s.texture, W);
    CHECK(out.cols == W && out.rows == H, "size %dx%d", out.cols, out.rows);
    double e = synth::mae(out, truth);
    double top = synth::mae(out, truth, 256, cv::Range(0, H / 10));
    double bottom = synth::mae(out, truth, 256, cv::Range(H - H / 10, H));
    cv::Mat mirrored, rolled;
    cv::flip(truth, mirrored, 1);
    cv::hconcat(truth.colRange(W / 8, W), truth.colRange(0, W / 8), rolled);
    double em = synth::mae(out, mirrored), er = synth::mae(out, rolled);
    std::printf("mae %.2f zenith %.2f nadir %.2f | mirrored %.2f rolled-45deg %.2f\n", e, top, bottom, em, er);
    CHECK(e < 6 && top < 8 && bottom < 8, "output differs from source");
    CHECK(em > 4 * e && er > 4 * e, "mirrored or shifted output fits as well as the right one");

    // Manifest: yaw is positive toward +Y (left). At yaw +70, pitch +20 all of x, y, z > 0 (bright in every
    // channel); its mirror image at yaw -70 has y < 0, so the green channel is dark there.
    auto col = [&](double yaw) { return (int)((synth::kPi - yaw * synth::kPi / 180) / (2 * synth::kPi) * W); };
    int row = (90 - 20) * H / 180;
    cv::Scalar left = cv::mean(out(cv::Rect(col(70) - 20, row - 20, 41, 41)));
    cv::Scalar right = cv::mean(out(cv::Rect(col(-70) - 20, row - 20, 41, 41)));
    std::printf("yaw +70: %.0f %.0f %.0f, yaw -70: %.0f %.0f %.0f\n", left[0], left[1], left[2], right[0], right[1], right[2]);
    CHECK(col(70) < W / 2 && left[0] > 120 && left[1] > 120 && left[2] > 120, "yaw +70 not bright/left");
    CHECK(right[1] < 80 && right[0] > 120, "yaw -70 green %.0f", right[1]);

    // The 360 wrap must not be worse than the rest. The texture changes octant exactly at lon 180, so compare
    // against the source, and with lon 0, which is the same kind of colour edge but in the middle of the canvas.
    cv::Mat bo, bt, d;
    cv::GaussianBlur(out, bo, cv::Size(), 2);
    cv::GaussianBlur(truth, bt, cv::Size(), 2);
    cv::absdiff(bo, bt, d);
    cv::Scalar all = cv::mean(d.colRange(W / 2 - 4, W / 2 + 4)), edge = (cv::mean(d.colRange(0, 4)) + cv::mean(d.colRange(W - 4, W))) / 2;
    double ea = (all[0] + all[1] + all[2]) / 3, ee = (edge[0] + edge[1] + edge[2]) / 3;
    std::printf("error at lon 180 (wrap) %.2f, at lon 0 %.2f\n", ee, ea);
    CHECK(ee < 1.5 * ea + 1, "visible seam at lon 180");
    std::printf("OK\n");
}
