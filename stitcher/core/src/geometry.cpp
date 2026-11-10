#include <algorithm>
#include <cmath>

#include <opencv2/imgproc.hpp>

#include "internal.hpp"

namespace spherestitch {

namespace {
constexpr double kPi = 3.14159265358979323846;
}

const char* phaseName(Phase p) {
    static const char* names[] = {"load", "features", "matching", "ba", "warp", "exposure", "seams", "blend", "encode"};
    return names[(int)p];
}

void Reporter::begin(Phase p) {
    phase_ = p;
    t0_ = cv::getTickCount();
    step(0);
}

void Reporter::step(double fraction) {
    if (fn_ && !fn_(phase_, fraction)) throw Cancelled{};
}

void Reporter::end() {
    step(1);
    r_.phaseSeconds.emplace_back(phaseName(phase_), (cv::getTickCount() - t0_) / cv::getTickFrequency());
}

cv::Vec3d pixelToDir(double u, double v, int W, int H) {
    double lon = kPi - (u + 0.5) / W * 2 * kPi;
    double lat = kPi / 2 - (v + 0.5) / H * kPi;
    return {std::cos(lat) * std::cos(lon), std::cos(lat) * std::sin(lon), std::sin(lat)};
}

cv::Point2d dirToPixel(const cv::Vec3d& d, int W, int H) {
    double lon = std::atan2(d[1], d[0]);
    double lat = std::atan2(d[2], std::hypot(d[0], d[1]));
    return {(kPi - lon) / (2 * kPi) * W - 0.5, (kPi / 2 - lat) / kPi * H - 0.5};
}

cv::Vec3d Camera::look(const cv::Vec3d& d, double gu, double gv) const {
    if (!depth) return R.t() * d;
    const int GW = depth->cols, GH = depth->rows;
    double fu = std::floor(gu), fv = std::floor(gv), tu = gu - fu, tv = gv - fv;
    int u0 = (((int)fu % GW) + GW) % GW, u1 = (u0 + 1) % GW;
    int v0 = std::clamp((int)fv, 0, GH - 1), v1 = std::clamp((int)fv + 1, 0, GH - 1);
    const float* r0 = depth->ptr<float>(v0);
    const float* r1 = depth->ptr<float>(v1);
    double t = (1 - tv) * ((1 - tu) * r0[u0] + tu * r0[u1]) + tv * ((1 - tu) * r1[u0] + tu * r1[u1]);
    return R.t() * (d * t - C);
}

Camera scaledCamera(const Session& s, const Result& r, int i, double k) {
    double f = r.focal * k;
    const cv::Matx33d& R = r.cameras[i].rotation;
    return {R, f, f * s.fy / s.fx, (s.cx + 0.5) * k - 0.5, (s.cy + 0.5) * k - 0.5,
            (int)std::lround(s.width * k), (int)std::lround(s.height * k), r.cameras[i].centre,
            r.depth.empty() ? nullptr : &r.depth};
}

cv::Mat blurWrap(const cv::Mat& a, double sigma) {
    const int pad = std::min(a.cols, (int)(4 * sigma));
    cv::Mat p, out;
    cv::copyMakeBorder(a, p, 0, 0, pad, pad, cv::BORDER_WRAP);
    cv::GaussianBlur(p, out, cv::Size(), sigma, sigma, cv::BORDER_REPLICATE);
    return out.colRange(pad, pad + a.cols).clone();
}

void buildMap(const Camera& cam, int W, int H, const cv::Rect& roi, cv::Mat& mapx, cv::Mat& mapy) {
    mapx.create(roi.size(), CV_32F);
    mapy.create(roi.size(), CV_32F);
    std::vector<double> clon(roi.width), slon(roi.width), gu(roi.width);
    const int GW = cam.depth ? cam.depth->cols : 1, GH = cam.depth ? cam.depth->rows : 1;
    for (int x = 0; x < roi.width; ++x) {
        double lon = kPi - (roi.x + x + 0.5) / W * 2 * kPi;
        clon[x] = std::cos(lon);
        slon[x] = std::sin(lon);
        gu[x] = (roi.x + x + 0.5) / W * GW - 0.5;
    }
    const double umax = cam.width - 1, vmax = cam.height - 1;
    cv::parallel_for_(cv::Range(0, roi.height), [&](const cv::Range& rows) {
        for (int y = rows.start; y < rows.end; ++y) {
            double lat = kPi / 2 - (roi.y + y + 0.5) / H * kPi;
            double cl = std::cos(lat), sl = std::sin(lat), gv = (roi.y + y + 0.5) / H * GH - 0.5;
            float* mx = mapx.ptr<float>(y);
            float* my = mapy.ptr<float>(y);
            for (int x = 0; x < roi.width; ++x) {
                cv::Vec3d c;
                if (cam.flow) {
                    const int FW = cam.flow->cols, FH = cam.flow->rows;
                    double fx = (roi.x + x + 0.5) / W * FW - 0.5, fy = (roi.y + y + 0.5) / H * FH - 0.5;
                    double x0 = std::floor(fx), y0 = std::floor(fy), tx = fx - x0, ty = fy - y0;
                    int u0 = (((int)x0 % FW) + FW) % FW, u1 = (u0 + 1) % FW;
                    int v0 = std::clamp((int)y0, 0, FH - 1), v1 = std::clamp((int)y0 + 1, 0, FH - 1);
                    const cv::Vec2f* f0 = cam.flow->ptr<cv::Vec2f>(v0);
                    const cv::Vec2f* f1 = cam.flow->ptr<cv::Vec2f>(v1);
                    cv::Vec2f f = (1 - ty) * ((1 - tx) * f0[u0] + tx * f0[u1]) + ty * ((1 - tx) * f1[u0] + tx * f1[u1]);
                    double lon = kPi - (roi.x + x + 0.5) / W * 2 * kPi + f[0], la = lat + f[1];
                    c = cam.look(cv::Vec3d(std::cos(la) * std::cos(lon), std::cos(la) * std::sin(lon), std::sin(la)),
                                 (kPi - lon) / (2 * kPi) * GW - 0.5, (kPi / 2 - la) / kPi * GH - 0.5);
                } else {
                    c = cam.look(cv::Vec3d(cl * clon[x], cl * slon[x], sl), gu[x], gv);
                }
                float u = -1, v = -1;
                if (c[2] > 1e-6) {
                    double uu = cam.fx * c[0] / c[2] + cam.cx;
                    double vv = cam.fy * c[1] / c[2] + cam.cy;
                    if (uu >= 0 && uu <= umax && vv >= 0 && vv <= vmax) {
                        u = (float)uu;
                        v = (float)vv;
                    }
                }
                mx[x] = u;
                my[x] = v;
            }
        }
    });
}

std::vector<cv::Rect> footprint(const Camera& cam, int W, int H, int pad) {
    const int Wc = 360, Hc = 180;
    // Generous margin so cells partly covering the image are not missed by the centre sample.
    const double mu = 0.05 * cam.width, mv = 0.05 * cam.height;
    std::vector<char> col(Wc, 0);
    int r0 = Hc, r1 = -1;
    const int GW = cam.depth ? cam.depth->cols : 1, GH = cam.depth ? cam.depth->rows : 1;
    for (int r = 0; r < Hc; ++r)
        for (int c = 0; c < Wc; ++c) {
            cv::Vec3d p = cam.look(pixelToDir(c, r, Wc, Hc), (c + 0.5) / Wc * GW - 0.5, (r + 0.5) / Hc * GH - 0.5);
            if (p[2] <= 0.05) continue;
            double u = cam.fx * p[0] / p[2] + cam.cx, v = cam.fy * p[1] / p[2] + cam.cy;
            if (u < -mu || u > cam.width + mu || v < -mv || v > cam.height + mv) continue;
            col[c] = 1;
            r0 = std::min(r0, r);
            r1 = std::max(r1, r);
        }
    if (r1 < 0) return {};
    int y0 = std::max(0, (r0 - 1) * H / Hc), y1 = std::min(H, (r1 + 2) * H / Hc);

    // Longest circular run of uncovered columns; the image occupies the complement.
    int bestLen = 0, bestEnd = 0;
    for (int start = 0, len = 0; start < 2 * Wc; ++start) {
        len = col[start % Wc] ? 0 : len + 1;
        if (len > bestLen && len <= Wc) {
            bestLen = len;
            bestEnd = start;
        }
    }
    cv::Rect full(-pad, y0, W + 2 * pad, y1 - y0);
    if (bestLen <= 2) return {full};
    int cs = (bestEnd + 1) % Wc - 1;  // one coarse cell of margin on each side
    int lc = Wc - bestLen + 2;
    int xs = (int)std::floor((double)cs * W / Wc), L = (int)std::ceil((double)lc * W / Wc) + 1;
    if (xs < 0) xs += W;
    if (L >= W - 2 * pad) return {full};
    std::vector<cv::Rect> parts;
    cv::Rect canvas(-pad, 0, W + 2 * pad, H);
    for (int shift : {-W, 0, W}) {
        cv::Rect p = cv::Rect(xs + shift, y0, L, y1 - y0) & canvas;
        if (p.area() > 0) parts.push_back(p);
    }
    return parts;
}

double angleDeg(const cv::Matx33d& a, const cv::Matx33d& b) {
    double c = ((a.t() * b).val[0] + (a.t() * b).val[4] + (a.t() * b).val[8] - 1) / 2;
    return std::acos(std::clamp(c, -1.0, 1.0)) * 180 / kPi;
}

cv::Matx33d nearestRotation(const cv::Matx33d& m) {
    cv::Matx33d u, vt;
    cv::Matx31d w;
    cv::SVD::compute(m, w, u, vt);
    cv::Matx33d d = cv::Matx33d::eye();
    if (cv::determinant(u * vt) < 0) d(2, 2) = -1;
    return u * d * vt;
}

cv::Matx33d alignRotations(const std::vector<cv::Matx33d>& from, const std::vector<cv::Matx33d>& to) {
    cv::Matx33d m = cv::Matx33d::zeros();
    for (size_t i = 0; i < from.size(); ++i) m += to[i] * from[i].t();
    return nearestRotation(m);
}

}  // namespace spherestitch
