#include <algorithm>
#include <cmath>
#include <numeric>

#include <opencv2/features.hpp>
#include <opencv2/flann.hpp>
#include <opencv2/geometry.hpp>
#include <opencv2/imgproc.hpp>

#include "internal.hpp"

namespace spherestitch {

namespace {

constexpr double kDeg = 180 / CV_PI;

// Fraction of a sample grid of image j that lands inside image i under the sensor rotations.
double overlap(const Session& s, const cv::Matx33d& Ri, const cv::Matx33d& Rj) {
    const int n = 12;
    cv::Matx33d M = Ri.t() * Rj;
    int in = 0;
    for (int a = 0; a < n; ++a)
        for (int b = 0; b < n; ++b) {
            double u = (a + 0.5) * s.width / n, v = (b + 0.5) * s.height / n;
            cv::Vec3d c = M * cv::Vec3d((u - s.cx) / s.fx, (v - s.cy) / s.fy, 1);
            if (c[2] <= 0) continue;
            double ui = s.fx * c[0] / c[2] + s.cx, vi = s.fy * c[1] / c[2] + s.cy;
            in += ui >= 0 && ui < s.width && vi >= 0 && vi < s.height;
        }
    return (double)in / (n * n);
}

int findRoot(std::vector<int>& p, int x) {
    while (p[x] != x) x = p[x] = p[p[x]];
    return x;
}

cv::Matx33d expRot(const cv::Vec3d& w) {
    double t = cv::norm(w);
    if (t < 1e-12) return cv::Matx33d::eye();
    cv::Vec3d k = w / t;
    cv::Matx33d K(0, -k[2], k[1], k[2], 0, -k[0], -k[1], k[0], 0);
    return cv::Matx33d::eye() + std::sin(t) * K + (1 - std::cos(t)) * K * K;
}

// Matched pixel pair at registration scale.
struct Match {
    int i, j;
    cv::Point2d pi, pj;
};

// Registration-scale intrinsics; f is the variable shared focal, fy = f * aspect.
struct Intr {
    double f, aspect, cx, cy;
    cv::Vec3d ray(const cv::Point2d& p) const { return {(p.x - cx) / f, (p.y - cy) / (f * aspect), 1}; }
};

// Rotation-only alignment of a pair (R_j^T R_i from the images): Kabsch on rays, refit a few times on the
// rays it explains, since a single stray match tilts a plain least-squares fit by a degree.
cv::Matx33d pairRotation(const std::vector<Match>& ms, const Intr& k) {
    std::vector<cv::Vec3d> A, B;
    for (const Match& x : ms) {
        A.push_back(cv::normalize(k.ray(x.pi)));
        B.push_back(cv::normalize(k.ray(x.pj)));
    }
    cv::Matx33d R = cv::Matx33d::eye();
    std::vector<double> e(A.size(), 0);
    for (int it = 0; it < 4; ++it) {
        double lim = INFINITY;
        if (it > 0) {
            std::vector<double> s = e;
            std::nth_element(s.begin(), s.begin() + s.size() / 2, s.end());
            lim = std::max(3 * s[s.size() / 2], 2 / k.f);
        }
        cv::Matx33d m = cv::Matx33d::zeros();
        for (size_t q = 0; q < A.size(); ++q)
            if (e[q] <= lim) m += B[q] * A[q].t();
        R = nearestRotation(m);
        for (size_t q = 0; q < A.size(); ++q) e[q] = cv::norm(R * A[q] - B[q]);
    }
    return R;
}

// Median reprojection error in pixels of image j under the best pure rotation.
double pairResidualSq(const std::vector<Match>& ms, const Intr& k) {
    cv::Matx33d R = pairRotation(ms, k);
    std::vector<double> e;
    for (const Match& x : ms) {
        cv::Vec3d a = R * k.ray(x.pi);
        if (a[2] <= 1e-6) {
            e.push_back(1e9);
            continue;
        }
        e.push_back(std::hypot(k.f * a[0] / a[2] + k.cx - x.pj.x, k.f * k.aspect * a[1] / a[2] + k.cy - x.pj.y));
    }
    // Parallax and stray matches inflate the tail; the median tracks the focal.
    std::nth_element(e.begin(), e.begin() + e.size() / 2, e.end());
    return e[e.size() / 2] * e[e.size() / 2];
}

// Shared focal that best explains all pairs as pure rotations; only used when the manifest focal is far off
// (the emulator reports about half the real one). Real phones report a good focal, and parallax biases this.
double searchFocal(const std::vector<std::vector<Match>>& pairs, Intr k) {
    auto cost = [&](double f) {
        k.f = f;
        double c = 0;
        // Capped: a mismatched pair fits no focal and would otherwise dominate.
        for (const auto& ms : pairs) c += std::min(pairResidualSq(ms, k), 64.0);
        return c;
    };
    const double f0 = k.f;
    double best = f0, bestCost = cost(f0);
    for (double s = 0.4; s <= 2.6; s *= 1.05) {
        double c = cost(f0 * s);
        if (c < bestCost) bestCost = c, best = f0 * s;
    }
    double lo = best / 1.05, hi = best * 1.05;
    for (int it = 0; it < 20; ++it) {
        double a = lo + (hi - lo) * 0.382, b = lo + (hi - lo) * 0.618;
        if (cost(a) < cost(b)) hi = b;
        else lo = a;
    }
    return (lo + hi) / 2;
}

// Heading of a camera as a rotation about world z: x axis -> horizontal viewing direction. Uses image-up near
// the poles, where the viewing direction has no horizontal part (the capture keeps image-up toward the heading
// when looking down and away from it when looking up).
cv::Matx33d headingOf(const cv::Matx33d& R) {
    cv::Vec3d z(R(0, 2), R(1, 2), R(2, 2)), up(-R(0, 1), -R(1, 1), -R(2, 1));
    double sp = z[2], cp = std::hypot(z[0], z[1]);
    double hx = z[0] * cp - up[0] * sp, hy = z[1] * cp - up[1] * sp, hn = std::hypot(hx, hy);
    if (hn < 1e-9) return cv::Matx33d::eye();
    hx /= hn;
    hy /= hn;
    return {hx, -hy, 0, hy, hx, 0, 0, 0, 1};
}

// Bundle adjustment of a handheld capture. The camera centre is not fixed: people turn their body to change
// heading and tilt the phone with wrist or elbow. So C_i = H_i B + R_i L + t_i: B is the phone's offset from
// the body axis in the heading frame (x forward, z up), L a lever in the camera frame, and t_i a small free
// offset per frame, because nobody orbits on a level circle (without it the solver tilts all cameras together
// by ~20 deg to fake a tilted orbit). Each match gets its own
// inverse depth along the ray of camera i (anchored point), which the normal equations eliminate per match
// (Schur complement), leaving a dense system over rotations, B, L and focal only. Rotations are tied to the
// sensors by a prior, which also fixes the global rotation. A weak prior on the inverse depths fixes the
// scale between offsets and scene, and keeps depth defined when there is no parallax.
struct Ba {
    static constexpr int G = 7;  // shared parameters: B, L, log focal
    using Shared = cv::Vec<double, G>;

    const std::vector<Match>& ms;
    std::vector<int> cam;           // session index -> BA index, -1 if not solved
    std::vector<cv::Matx33d> R0, H; // sensor rotations and headings of solved cameras
    Intr k0;
    bool lever = true;

    // State
    std::vector<cv::Vec3d> w;       // R = exp(w) R0
    std::vector<cv::Vec3d> t;
    Shared g = Shared::all(0);
    std::vector<double> rho;
    std::vector<char> active;       // match used (not an outlier, both cameras solved)

    static constexpr double kHuber = 2.0;          // px
    // Gyro drift makes the absolute sensor rotation wander by several degrees over a capture, while the
    // rotation between frames taken one after another stays good: tie the corrections of consecutive frames
    // (frames are in capture order) tightly, and the absolute ones only loosely.
    // Yaw has no reference (no compass) and drifts; tilt is tied to gravity by the sensor fusion.
    static constexpr double kSigmaRot = 10 / kDeg;
    static constexpr double kSigmaTilt = 3 / kDeg;
    // Of the scene distance, i.e. a centimetre or two. Looser, and shifts start to stand in for rotations
    // (the two look alike in a narrow field of view).
    static constexpr double kSigmaShift = 0.01;
    static constexpr double kSigmaRelRot = 2 / kDeg;
    static constexpr double kSigmaRho = 1.0;
    static constexpr double kSigmaOffset = 0.5;
    // Tight when the manifest focal is trusted: moving the camera along its axis looks like a zoom for near
    // points, so focal and offsets trade off.
    double sigmaLogF = 0.02;

    Ba(const std::vector<Match>& m, const std::vector<int>& ids, const Session& s, const Intr& k, bool useLever)
        : ms(m), cam(s.frames.size(), -1), k0(k), lever(useLever) {
        for (size_t a = 0; a < ids.size(); ++a) {
            cam[ids[a]] = (int)a;
            R0.push_back(s.frames[ids[a]].R);
            H.push_back(headingOf(s.frames[ids[a]].R));
        }
        w.assign(ids.size(), cv::Vec3d(0, 0, 0));
        t.assign(ids.size(), cv::Vec3d(0, 0, 0));
        rho.assign(ms.size(), 1.0);
        active.resize(ms.size());
        for (size_t m_ = 0; m_ < ms.size(); ++m_) active[m_] = cam[ms[m_].i] >= 0 && cam[ms[m_].j] >= 0;
    }

    int np() const { return 6 * (int)w.size() + G; }
    // B.z only moves the pivot, and L.x is B.y for a camera without roll: both stay zero, or the solver
    // finds two huge offsets that cancel and any roll turns the difference into a bogus centre.
    bool fixedShared(int c) const { return c < 6 && (!lever || c == 2 || c == 3); }
    cv::Matx33d rot(int a) const { return expRot(w[a]) * R0[a]; }
    double focal() const { return k0.f * std::exp(g[6]); }
    static cv::Vec3d B(const Shared& v) { return {v[0], v[1], v[2]}; }
    static cv::Vec3d L(const Shared& v) { return {v[3], v[4], v[5]}; }
    cv::Vec3d model(int a, const cv::Matx33d& R, const Shared& v) const { return H[a] * B(v) + R * L(v); }
    cv::Vec3d centre(int a) const { return model(a, rot(a), g) + t[a]; }
    static double rotPrior(int c) { return 1 / (c == 2 ? kSigmaRot * kSigmaRot : kSigmaTilt * kSigmaTilt); }

    // dt: t_i - t_j
    cv::Vec2d residual(const Match& x, const cv::Matx33d& Ri, const cv::Matx33d& Rj, const cv::Vec3d& dt,
                       const Shared& v, double r) const {
        Intr k = k0;
        k.f = k0.f * std::exp(v[6]);
        int ai = cam[x.i], aj = cam[x.j];
        cv::Vec3d a = Ri * k.ray(x.pi);
        cv::Vec3d p = cv::normalize(Rj.t() * (r * (model(ai, Ri, v) - model(aj, Rj, v) + dt) + a));
        // Ray error in the tangent plane of the observed ray, scaled to pixels: bounded and smooth even for a
        // point that lands behind the camera, where a projective residual explodes.
        cv::Vec3d b = cv::normalize(k.ray(x.pj));
        cv::Vec3d t1 = cv::normalize(cv::Vec3d(1, 0, 0) - b * b[0]), t2 = b.cross(t1);
        return {k.f * t1.dot(p - b), k.f * t2.dot(p - b)};
    }

    double robustWeight(const cv::Vec2d& r) const {
        double n = cv::norm(r);
        return n <= kHuber ? 1 : kHuber / n;
    }

    double priorCost() const {
        double c = 0;
        for (const auto& v : w)
            for (int q = 0; q < 3; ++q) c += v[q] * v[q] * rotPrior(q);
        for (const auto& v : t) c += v.dot(v) / (kSigmaShift * kSigmaShift);
        for (size_t a = 1; a < w.size(); ++a) {
            cv::Vec3d d = w[a] - w[a - 1];
            c += d.dot(d) / (kSigmaRelRot * kSigmaRelRot);
        }
        for (int q = 0; q < 6; ++q) c += g[q] * g[q] / (kSigmaOffset * kSigmaOffset);
        return c + g[6] * g[6] / (sigmaLogF * sigmaLogF);
    }

    double cost() const {
        double c = priorCost();
        std::vector<cv::Matx33d> R(w.size());
        for (size_t a = 0; a < w.size(); ++a) R[a] = rot((int)a);
        for (size_t m_ = 0; m_ < ms.size(); ++m_) {
            if (!active[m_]) continue;
            const Match& x = ms[m_];
            double n = cv::norm(residual(x, R[cam[x.i]], R[cam[x.j]], t[cam[x.i]] - t[cam[x.j]], g, rho[m_]));
            c += n <= kHuber ? n * n : 2 * kHuber * n - kHuber * kHuber;
            c += (rho[m_] - 1) * (rho[m_] - 1) / (kSigmaRho * kSigmaRho);
        }
        return c;
    }

    // One Levenberg-Marquardt step with damping lambda; returns false if the system could not be solved.
    bool step(double lambda) {
        constexpr int K = 12 + G;  // parameters one match touches besides its depth
        const int P = np(), n = (int)w.size();
        cv::Mat A = cv::Mat::zeros(P, P, CV_64F), b = cv::Mat::zeros(P, 1, CV_64F);
        std::vector<cv::Matx33d> R(n);
        for (int a = 0; a < n; ++a) R[a] = rot(a);
        struct Back {
            double V, g;
            cv::Vec<double, K> wv;
            int idx[K];
        };
        std::vector<Back> back(ms.size());
        const double h = 1e-6;
        for (size_t m_ = 0; m_ < ms.size(); ++m_) {
            if (!active[m_]) continue;
            const Match& x = ms[m_];
            int ai = cam[x.i], aj = cam[x.j];
            const cv::Vec3d dt = t[ai] - t[aj];
            cv::Vec2d r0 = residual(x, R[ai], R[aj], dt, g, rho[m_]);
            double wt = robustWeight(r0);
            cv::Matx<double, 2, K> J;
            Back bk;
            for (int c = 0; c < 3; ++c) {
                cv::Vec3d d(0, 0, 0);
                d[c] = h;
                cv::Vec2d ri = (residual(x, expRot(w[ai] + d) * R0[ai], R[aj], dt, g, rho[m_]) - r0) / h;
                cv::Vec2d rj = (residual(x, R[ai], expRot(w[aj] + d) * R0[aj], dt, g, rho[m_]) - r0) / h;
                cv::Vec2d rt = lever ? (residual(x, R[ai], R[aj], dt + d, g, rho[m_]) - r0) / h : cv::Vec2d(0, 0);
                for (int q = 0; q < 2; ++q) {
                    J(q, c) = ri[q];
                    J(q, 3 + c) = rt[q];
                    J(q, 6 + c) = rj[q];
                    J(q, 9 + c) = -rt[q];
                }
                bk.idx[c] = 6 * ai + c;
                bk.idx[3 + c] = 6 * ai + 3 + c;
                bk.idx[6 + c] = 6 * aj + c;
                bk.idx[9 + c] = 6 * aj + 3 + c;
            }
            for (int c = 0; c < G; ++c) {
                bk.idx[12 + c] = 6 * n + c;
                if (fixedShared(c)) {
                    J(0, 12 + c) = J(1, 12 + c) = 0;
                    continue;
                }
                Shared gv = g;
                gv[c] += h;
                cv::Vec2d rg = (residual(x, R[ai], R[aj], dt, gv, rho[m_]) - r0) / h;
                J(0, 12 + c) = rg[0];
                J(1, 12 + c) = rg[1];
            }
            double hr = h * std::max(1.0, rho[m_]);
            cv::Vec2d Jr = (residual(x, R[ai], R[aj], dt, g, rho[m_] + hr) - r0) / hr;

            cv::Matx<double, K, K> U = wt * J.t() * J;
            cv::Vec<double, K> gc = wt * J.t() * r0;
            bk.wv = wt * J.t() * Jr;
            bk.V = (wt * Jr.dot(Jr) + 1 / (kSigmaRho * kSigmaRho)) * (1 + lambda) + 1e-12;
            bk.g = wt * Jr.dot(r0) + (rho[m_] - 1) / (kSigmaRho * kSigmaRho);
            for (int p = 0; p < K; ++p) {
                b.at<double>(bk.idx[p]) += gc[p] - bk.wv[p] * bk.g / bk.V;
                for (int q = 0; q < K; ++q)
                    A.at<double>(bk.idx[p], bk.idx[q]) += U(p, q) - bk.wv[p] * bk.wv[q] / bk.V;
            }
            back[m_] = bk;
        }
        const double ir = 1 / (kSigmaRelRot * kSigmaRelRot), is = 1 / (kSigmaShift * kSigmaShift);
        for (int a = 0; a < n; ++a)
            for (int c = 0; c < 3; ++c) {
                int p = 6 * a + c;
                A.at<double>(p, p) += rotPrior(c);
                b.at<double>(p) += w[a][c] * rotPrior(c);
                A.at<double>(p + 3, p + 3) += lever ? is : 1;
                b.at<double>(p + 3) += t[a][c] * is;
                if (a == 0) continue;
                int q = p - 6;
                double d = w[a][c] - w[a - 1][c];
                A.at<double>(p, p) += ir;
                A.at<double>(q, q) += ir;
                A.at<double>(p, q) -= ir;
                A.at<double>(q, p) -= ir;
                b.at<double>(p) += d * ir;
                b.at<double>(q) -= d * ir;
            }
        for (int c = 0; c < G; ++c) {
            double s2 = c == 6 ? sigmaLogF * sigmaLogF : kSigmaOffset * kSigmaOffset;
            A.at<double>(6 * n + c, 6 * n + c) += fixedShared(c) ? 1 : 1 / s2;
            b.at<double>(6 * n + c) += g[c] / s2;
        }
        for (int p = 0; p < P; ++p) A.at<double>(p, p) *= 1 + lambda;

        cv::Mat d;
        if (!cv::solve(A, -b, d, cv::DECOMP_CHOLESKY)) return false;
        for (int a = 0; a < n; ++a)
            for (int c = 0; c < 3; ++c) {
                w[a][c] += d.at<double>(6 * a + c);
                if (lever) t[a][c] += d.at<double>(6 * a + 3 + c);
            }
        for (int c = 0; c < G; ++c)
            if (!fixedShared(c)) g[c] += d.at<double>(6 * n + c);
        for (size_t m_ = 0; m_ < ms.size(); ++m_) {
            if (!active[m_]) continue;
            const Back& bk = back[m_];
            double s = bk.g;
            for (int p = 0; p < K; ++p) s += bk.wv[p] * d.at<double>(bk.idx[p]);
            rho[m_] = std::max(0.0, rho[m_] - s / bk.V);
        }
        return true;
    }

    void solve(Reporter& rep, double from, double to) {
        double lambda = 1e-3, c = cost();
        const int maxIter = 60;
        for (int it = 0; it < maxIter; ++it) {
            auto saved = std::make_tuple(w, t, g, rho);
            bool ok = step(lambda);
            double c2 = ok ? cost() : INFINITY;
            if (c2 < c) {
                // The last per cent takes 50 more iterations and moves no camera by a visible amount.
                bool done = (c - c2) < 1e-4 * c;
                c = c2;
                lambda = std::max(1e-9, lambda / 3);
                if (done) break;
            } else {
                std::tie(w, t, g, rho) = saved;
                lambda *= 8;
                if (lambda > 1e8) break;
            }
            rep.step(from + (to - from) * (it + 1) / maxIter);
        }
    }

    // Residuals in px of the active matches.
    std::vector<double> errors() const {
        std::vector<double> e;
        for (size_t m_ = 0; m_ < ms.size(); ++m_) {
            if (!active[m_]) continue;
            const Match& x = ms[m_];
            e.push_back(cv::norm(residual(x, rot(cam[x.i]), rot(cam[x.j]), t[cam[x.i]] - t[cam[x.j]], g, rho[m_])));
        }
        return e;
    }

    void dropOutliers() {
        std::vector<double> e = errors(), s = e;
        if (s.empty()) return;
        std::nth_element(s.begin(), s.begin() + s.size() / 2, s.end());
        const double lim = std::max(3 * kHuber, 4 * s[s.size() / 2]);
        size_t k = 0;
        for (size_t m_ = 0; m_ < ms.size(); ++m_)
            if (active[m_]) active[m_] = e[k++] <= lim;
    }
};

// Scene distance from the pivot on a coarse equirect grid: floor/ceiling planes and a sphere where points
// support them, corrected locally by the log-ratio of the triangulated points (normalized convolution).
cv::Mat depthGrid(const std::vector<cv::Vec3d>& pts) {
    const int GW = 128, GH = 64;
    std::vector<double> dist, floorZ, ceilZ, wallD;
    for (const auto& p : pts) {
        double d = cv::norm(p), pitch = std::asin(std::clamp(p[2] / d, -1.0, 1.0)) * kDeg;
        dist.push_back(d);
        if (pitch < -35) floorZ.push_back(-p[2]);
        if (pitch > 35) ceilZ.push_back(p[2]);
        if (std::abs(pitch) < 30) wallD.push_back(d);
    }
    auto median = [](std::vector<double> v) {
        std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
        return v[v.size() / 2];
    };
    const size_t minPts = 20;
    const double sphere = median(wallD.size() >= minPts ? wallD : dist);
    const double hf = floorZ.size() >= minPts ? median(floorZ) : 0, hc = ceilZ.size() >= minPts ? median(ceilZ) : 0;
    auto base = [&](const cv::Vec3d& d) {
        double t = sphere;
        if (hf > 0 && d[2] < -1e-6) t = std::min(t, hf / -d[2]);
        if (hc > 0 && d[2] > 1e-6) t = std::min(t, hc / d[2]);
        return t;
    };

    cv::Mat num = cv::Mat::zeros(GH, GW, CV_64F), den = cv::Mat::zeros(GH, GW, CV_64F);
    for (size_t k = 0; k < pts.size(); ++k) {
        cv::Vec3d d = pts[k] / dist[k];
        double lr = std::log(dist[k] / base(d));
        if (std::abs(lr) > 2) continue;
        cv::Point2d g = dirToPixel(d, GW, GH);
        int gx = ((int)std::lround(g.x) % GW + GW) % GW, gy = std::clamp((int)std::lround(g.y), 0, GH - 1);
        num.at<double>(gy, gx) += lr;
        den.at<double>(gy, gx) += 1;
    }
    auto blur = [](const cv::Mat& a) { return blurWrap(a, 2.5); };
    // +0.5: a few points pull the proxy only part of the way.
    cv::Mat corr = blur(num) / (blur(den) + 0.5);
    cv::Mat grid(GH, GW, CV_32F);
    for (int y = 0; y < GH; ++y)
        for (int x = 0; x < GW; ++x)
            grid.at<float>(y, x) = (float)(base(pixelToDir(x, y, GW, GH)) * std::exp(corr.at<double>(y, x)));
    return grid;
}

}  // namespace

void registerCameras(const Session& s, const Options& o, Reporter& rep, Result& r, std::vector<cv::Mat>& grays) {
    const int n = (int)s.frames.size();
    const double scale = std::min(1.0, std::sqrt(o.registrationMegapix * 1e6 / ((double)s.width * s.height)));

    r.cameras.resize(n);
    for (int i = 0; i < n; ++i) {
        r.cameras[i].index = s.frames[i].index;
        r.cameras[i].rotation = s.frames[i].R;
        r.cameras[i].source = "sensor";
    }
    r.focal = s.fx;
    r.focalSource = "manifest";
    r.manifestFocal = s.fx;

    rep.begin(Phase::Features);
    std::vector<std::vector<cv::KeyPoint>> kps(n);
    std::vector<cv::Mat> desc(n);
    grays.resize(n);
    {
        // Low contrast threshold: walls, ceilings and floors are smooth; default 0.04 finds almost nothing there.
        cv::Ptr<cv::SIFT> sift = cv::SIFT::create(3000, 3, 0.01);
        for (int i = 0; i < n; ++i) {
            cv::Mat img = readImage(s.frames[i].path, s), small, gray;
            cv::resize(img, small, cv::Size(), scale, scale, cv::INTER_AREA);
            cv::cvtColor(small, gray, cv::COLOR_BGR2GRAY);
            sift->detectAndCompute(gray, cv::noArray(), kps[i], desc[i]);
            grays[i] = gray;
            rep.step((i + 1.0) / n);
        }
    }
    rep.end();

    rep.begin(Phase::Matching);
    std::vector<std::pair<int, int>> cand;
    for (int i = 0; i < n; ++i)
        for (int j = i + 1; j < n; ++j)
            if (std::max(overlap(s, s.frames[i].R, s.frames[j].R), overlap(s, s.frames[j].R, s.frames[i].R)) > 0.05)
                cand.emplace_back(i, j);

    // Geometric verification with the fundamental matrix: unlike a homography it accepts parallax, and unlike
    // the essential matrix it does not need the focal, which may still be wrong here.
    std::vector<std::vector<Match>> pairMatches(cand.size());
    std::vector<int> rawMatches(cand.size(), 0);
    const int batch = std::max(1, cv::getNumThreads());
    for (size_t b0 = 0; b0 < cand.size(); b0 += batch) {
        size_t b1 = std::min(cand.size(), b0 + batch);
        cv::parallel_for_(cv::Range((int)b0, (int)b1), [&](const cv::Range& rg) {
            for (int p = rg.start; p < rg.end; ++p) {
                auto [i, j] = cand[p];
                // FLANN cannot index an empty descriptor set (e.g. a uniform image).
                if (kps[i].size() < 8 || kps[j].size() < 8) continue;
                cv::theRNG() = cv::RNG(0x5eed + p);  // RANSAC must not depend on thread scheduling
                std::vector<std::vector<cv::DMatch>> knn;
                cv::FlannBasedMatcher().knnMatch(desc[i], desc[j], knn, 2);
                std::vector<cv::Point2f> a, b;
                for (const auto& k : knn)
                    if (k.size() == 2 && k[0].distance < 0.7f * k[1].distance) {
                        a.push_back(kps[i][k[0].queryIdx].pt);
                        b.push_back(kps[j][k[0].trainIdx].pt);
                    }
                rawMatches[p] = (int)a.size();
                if (a.size() < 15) continue;
                std::vector<uchar> mask;
                cv::Mat F = cv::findFundamentalMat(a, b, cv::FM_RANSAC, 1.5, 0.999, 2000, mask);
                if (F.empty()) continue;
                for (size_t k = 0; k < a.size(); ++k)
                    if (mask[k]) pairMatches[p].push_back({i, j, a[k], b[k]});
            }
        });
        rep.step((double)b1 / cand.size() * 0.5);
    }

    Intr k{s.fx * scale, s.fy / s.fx, s.cx * scale, s.cy * scale};
    bool focalSearched = false;
    {
        std::vector<std::vector<Match>> good;
        for (const auto& pm : pairMatches)
            if (pm.size() >= 30) good.push_back(pm);
        if (!good.empty()) {
            double f = searchFocal(good, k);
            if (std::abs(std::log(f / k.f)) > std::log(1.25)) {
                k.f = f;
                focalSearched = true;
            }
        }
    }
    rep.step(0.7);

    // Pairs whose image rotation contradicts the sensors are mismatches (repeated patterns, swapped frames).
    std::vector<std::vector<Match>> used(cand.size());
    for (size_t p = 0; p < cand.size(); ++p) {
        auto [i, j] = cand[p];
        const auto& pm = pairMatches[p];
        int inl = (int)pm.size();
        double relErr = 180, conf = inl / (8 + 0.3 * rawMatches[p]);
        if (inl >= 8) relErr = angleDeg(pairRotation(pm, k), s.frames[j].R.t() * s.frames[i].R);
        // Low-texture frames (cabinet doors, curtains) never reach the confidence of a textured pair; agreeing
        // with the sensors is evidence enough that their few matches are real.
        bool accepted = inl >= 15 && relErr <= o.maxRelativeRotationDeg && (conf > 1 || (inl >= 20 && relErr <= 10));
        if (accepted) {
            // The fundamental matrix is degenerate for a pure rotation and keeps stray matches near epipolar
            // lines. Parallax beyond this angle only comes from objects within arm's reach.
            cv::Matx33d Rp = pairRotation(pm, k);
            for (const Match& x : pm)
                if (cv::normalize(Rp * k.ray(x.pi)).dot(cv::normalize(k.ray(x.pj))) > std::cos(12 / kDeg))
                    used[p].push_back(x);
        }
        r.pairs.push_back({s.frames[i].index, s.frames[j].index, inl, accepted ? conf : 0, relErr, accepted});
    }
    rep.end();

    rep.begin(Phase::BundleAdjust);
    std::vector<Match> matches;
    std::vector<int> ids, connected;
    auto connect = [&]() {
        matches.clear();
        std::vector<int> parent(n), size(n, 0);
        std::iota(parent.begin(), parent.end(), 0);
        for (size_t p = 0; p < cand.size(); ++p) {
            if (!r.pairs[p].accepted) continue;
            matches.insert(matches.end(), used[p].begin(), used[p].end());
            parent[findRoot(parent, cand[p].first)] = findRoot(parent, cand[p].second);
        }
        for (int i = 0; i < n; ++i) ++size[findRoot(parent, i)];
        int root = (int)(std::max_element(size.begin(), size.end()) - size.begin());
        connected.clear();
        for (int i = 0; i < n; ++i) {
            if (findRoot(parent, i) == root && size[root] > 1)
                connected.push_back(i);
            else
                r.cameras[i].reason = "no-consistent-matches";
        }
    };
    connect();

    std::unique_ptr<Ba> ba;
    auto solveAll = [&](bool lever) {
        ids = connected;
        for (int i : ids) r.cameras[i].reason.clear();
        for (int iter = 0; iter < 3 && ids.size() >= 2; ++iter) {
            ba = std::make_unique<Ba>(matches, ids, s, k, lever);
            if (focalSearched) ba->sigmaLogF = 0.1;
            ba->solve(rep, 0, 0.5);
            ba->dropOutliers();
            ba->solve(rep, 0.5, 0.9);
            std::vector<int> keep;
            for (size_t a = 0; a < ids.size(); ++a) {
                if (cv::norm(ba->w[a]) * kDeg > o.maxCameraDeviationDeg)
                    r.cameras[ids[a]].reason = "ba-deviates-from-sensor";
                else
                    keep.push_back(ids[a]);
            }
            if (keep.size() == ids.size()) return true;
            ids = keep;  // the outlier also pulled its neighbours, so solve again without it
        }
        return false;
    };
    bool ok = solveAll(o.parallax);

    // Gyro drift correction of a frame the BA did not solve, from the solved frames around it in capture order.
    auto drift = [&](int i) {
        int before = -1, after = -1;
        for (size_t a = 0; a < ids.size(); ++a) {
            if (ids[a] < i) before = (int)a;
            if (ids[a] > i && after < 0) after = (int)a;
        }
        if (before < 0 || after < 0) return ba->w[before < 0 ? after : before];
        double t = double(i - ids[before]) / (ids[after] - ids[before]);
        return ba->w[before] * (1 - t) + ba->w[after] * t;
    };

    // Second matching round along the epipolar lines of the first solution. Frames of cabinet doors, curtains
    // or bare walls have a few hundred features and fail the global ratio test; with the search limited to
    // where the poses allow the match to be, their few features are enough.
    if (ok && o.parallax) {
        Intr kf = k;
        kf.f = ba->focal();
        std::vector<cv::Matx33d> Rg(n);
        std::vector<cv::Vec3d> Cg(n);
        for (int i = 0; i < n; ++i) {
            int a = ba->cam[i];
            Rg[i] = a >= 0 ? ba->rot(a) : expRot(drift(i)) * s.frames[i].R;
            Cg[i] = a >= 0 ? ba->centre(a) : headingOf(s.frames[i].R) * Ba::B(ba->g) + Rg[i] * Ba::L(ba->g);
        }
        const double maxRho = 4;  // nearest scene content, relative to the typical one
        std::vector<std::vector<Match>> found(cand.size());
        cv::parallel_for_(cv::Range(0, (int)cand.size()), [&](const cv::Range& rg) {
            for (int p = rg.start; p < rg.end; ++p) {
                auto [i, j] = cand[p];
                if (used[p].size() >= 60 || kps[i].size() < 8 || kps[j].size() < 8) continue;
                // An unsolved frame is only known to the gyro drift.
                const double tol = (ba->cam[i] >= 0 && ba->cam[j] >= 0 ? 1.5 : 6.0) / kDeg * kf.f;
                const cv::Matx33d M = Rg[j].t() * Rg[i];
                const cv::Vec3d base = Rg[j].t() * (Cg[i] - Cg[j]) * maxRho;
                auto proj = [&](const cv::Vec3d& c) {
                    return cv::Point2d(kf.f * c[0] / c[2] + kf.cx, kf.f * kf.aspect * c[1] / c[2] + kf.cy);
                };
                cv::Mat mask = cv::Mat::zeros((int)kps[i].size(), (int)kps[j].size(), CV_8U);
                for (size_t a = 0; a < kps[i].size(); ++a) {
                    cv::Vec3d q0 = M * kf.ray(kps[i][a].pt), q1 = q0 + base;
                    if (q0[2] < 0.05 || q1[2] < 0.05) continue;
                    cv::Point2d e0 = proj(q0), d = proj(q1) - e0;
                    double len2 = d.dot(d);
                    uchar* row = mask.ptr<uchar>((int)a);
                    for (size_t b = 0; b < kps[j].size(); ++b) {
                        cv::Point2d v = cv::Point2d(kps[j][b].pt) - e0;
                        double t = len2 > 0 ? std::clamp(v.dot(d) / len2, 0.0, 1.0) : 0;
                        row[b] = cv::norm(v - t * d) < tol ? 255 : 0;
                    }
                }
                cv::theRNG() = cv::RNG(0x5eed + p);
                // With few candidates left in the band the ratio test passes chance matches; require the
                // match to win in both directions.
                std::vector<std::vector<cv::DMatch>> knn, back;
                cv::BFMatcher(cv::NORM_L2).knnMatch(desc[i], desc[j], knn, 2, mask);
                cv::BFMatcher(cv::NORM_L2).knnMatch(desc[j], desc[i], back, 2, mask.t());
                auto best = [](const std::vector<cv::DMatch>& q) {
                    return q.size() == 2 && q[0].distance < 0.8f * q[1].distance ? q[0].trainIdx : -1;
                };
                std::vector<cv::Point2f> a, b;
                for (const auto& q : knn) {
                    int tj = best(q);
                    if (tj < 0 || best(back[tj]) != q[0].queryIdx) continue;
                    a.push_back(kps[i][q[0].queryIdx].pt);
                    b.push_back(kps[j][tj].pt);
                }
                if (a.size() < 15) continue;
                std::vector<uchar> inl;
                cv::Mat F = cv::findFundamentalMat(a, b, cv::FM_RANSAC, 1.5, 0.999, 2000, inl);
                if (F.empty()) continue;
                for (size_t q = 0; q < a.size(); ++q)
                    if (inl[q]) found[p].push_back({i, j, a[q], b[q]});
            }
        });
        bool any = false;
        for (size_t p = 0; p < cand.size(); ++p) {
            if (found[p].size() < 15 || found[p].size() <= used[p].size()) continue;
            // The search band is long in the direction of the baseline, enough for a frame with wrong pixels
            // (or a repeated pattern) to find support; these never fit the sensors.
            double relErr = angleDeg(pairRotation(found[p], kf),
                                     s.frames[cand[p].second].R.t() * s.frames[cand[p].first].R);
            if (relErr > 10) continue;
            used[p] = found[p];
            r.pairs[p].inliers = (int)used[p].size();
            r.pairs[p].accepted = any = true;
            r.pairs[p].guided = true;
        }
        if (any) {
            connect();
            ok = solveAll(true);
        }
    }
    std::vector<cv::Vec3d> pts;
    double unit = 1;
    auto triangulate = [&]() {
        pts.clear();
        Intr kf = k;
        kf.f = ba->focal();
        for (size_t m_ = 0; m_ < matches.size(); ++m_) {
            if (!ba->active[m_] || ba->rho[m_] <= 1e-6) continue;
            const Match& x = matches[m_];
            cv::Matx33d Ri = ba->rot(ba->cam[x.i]);
            pts.push_back(ba->centre(ba->cam[x.i]) + Ri * kf.ray(x.pi) / ba->rho[m_]);
        }
        std::vector<double> d;
        for (const auto& p : pts) d.push_back(cv::norm(p));
        if (d.empty()) return;
        std::nth_element(d.begin(), d.begin() + d.size() / 2, d.end());
        unit = d[d.size() / 2];
    };
    if (ok && o.parallax) {
        triangulate();
        // Per-match depths absorb noise, so a lever is always found; below this (relative to the scene) it
        // is not worth the focal/lever ambiguity it brings, and a pure rotation is the better model.
        double spread = 0;
        for (size_t a = 0; a < ids.size(); ++a) spread = std::max(spread, cv::norm(ba->centre((int)a)));
        if (pts.size() < 50 || spread / unit < 0.03) {
            ok = solveAll(false);
            pts.clear();
        }
    }
    double rmsDeg = 0;
    if (ok) {
        std::vector<double> e = ba->errors();
        double sq = 0;
        for (double v : e) sq += v * v;
        const double f = ba->focal();
        rmsDeg = e.empty() ? 0 : std::sqrt(sq / e.size()) / f * kDeg;
        if (e.empty() || rmsDeg > o.maxRayResidualDeg) {
            ok = false;
            r.fallbackReason = "ba-residual";
        }
    }
    if (!ok && r.fallbackReason.empty()) r.fallbackReason = "too-few-registered-cameras";
    r.baRayResidualDeg = ok ? rmsDeg : 0;

    if (ok) {
        r.fallbackReason.clear();
        r.focal = ba->focal() / scale;
        r.focalSource = "bundle-adjusted";
        for (size_t a = 0; a < ids.size(); ++a) {
            r.cameras[ids[a]].rotation = ba->rot((int)a);
            r.cameras[ids[a]].source = "bundle-adjusted";
            r.cameras[ids[a]].reason.clear();
        }
        // Unregistered frames (bare ceilings) keep their sensor rotation, but with the gyro drift the
        // registered frames around them in capture order were corrected for, or they would jump against them.
        for (int i = 0; i < n; ++i) {
            if (ba->cam[i] >= 0) continue;
            r.cameras[i].rotation = expRot(drift(i)) * s.frames[i].R;
        }

        if (!pts.empty()) {
            for (auto& p : pts) p /= unit;
            r.bodyOffset = Ba::B(ba->g) / unit;
            r.lever = Ba::L(ba->g) / unit;
            r.depth = depthGrid(pts);
            for (int i = 0; i < n; ++i) {
                const cv::Matx33d& R = r.cameras[i].rotation;
                r.cameras[i].centre = ba->cam[i] >= 0 ? ba->centre(ba->cam[i]) / unit
                                                      : headingOf(s.frames[i].R) * r.bodyOffset + R * r.lever;
            }
        }
    } else {
        for (auto& c : r.cameras) c.reason = c.reason.empty() ? r.fallbackReason : c.reason;
    }
    int nba = 0;
    for (int i = 0; i < n; ++i) {
        r.cameras[i].deviationDeg = angleDeg(r.cameras[i].rotation, s.frames[i].R);
        nba += r.cameras[i].source == "bundle-adjusted";
    }
    r.rotationSource = nba == n ? "bundle-adjusted" : nba == 0 ? "sensor" : "mixed";
    rep.end();
}

}  // namespace spherestitch
