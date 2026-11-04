#include <algorithm>
#include <cmath>
#include <numeric>

#include <opencv2/features.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/matchers.hpp>
#include <opencv2/stitching/detail/motion_estimators.hpp>

#include "internal.hpp"

namespace spherestitch {

namespace dt = cv::detail;

namespace {

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

struct BaOutput {
    std::vector<cv::Matx33d> R;  // aligned to the sensor frame
    std::vector<double> focal;   // registration-scale pixels
    double residualDeg = 0;
};

double rayResidualDeg(const std::vector<int>& ids, const std::vector<dt::ImageFeatures>& feat,
                      const std::vector<dt::MatchesInfo>& pm, int n, const BaOutput& b, double aspect,
                      double ppx, double ppy) {
    double sum = 0;
    long cnt = 0;
    for (size_t a = 0; a < ids.size(); ++a)
        for (size_t c = a + 1; c < ids.size(); ++c) {
            const dt::MatchesInfo& m = pm[ids[a] * n + ids[c]];
            if (m.confidence <= 1) continue;
            for (size_t k = 0; k < m.matches.size(); ++k) {
                if (!m.inliers_mask[k]) continue;
                cv::Point2f p1 = feat[ids[a]].keypoints[m.matches[k].queryIdx].pt;
                cv::Point2f p2 = feat[ids[c]].keypoints[m.matches[k].trainIdx].pt;
                cv::Vec3d d1 = b.R[a] * cv::Vec3d((p1.x - ppx) / b.focal[a], (p1.y - ppy) / (b.focal[a] * aspect), 1);
                cv::Vec3d d2 = b.R[c] * cv::Vec3d((p2.x - ppx) / b.focal[c], (p2.y - ppy) / (b.focal[c] * aspect), 1);
                double cs = d1.dot(d2) / (cv::norm(d1) * cv::norm(d2));
                double ang = std::acos(std::clamp(cs, -1.0, 1.0)) * 180 / CV_PI;
                sum += ang * ang;
                ++cnt;
            }
        }
    return cnt ? std::sqrt(sum / cnt) : 0;
}

// fixedFocal == 0: BundleAdjusterRay refines rotations and per-camera focal, starting from the sensors.
// fixedFocal > 0: BundleAdjusterReproj refines rotations only, starting from `init`, with that shared focal.
bool runBa(const std::vector<int>& ids, const std::vector<dt::ImageFeatures>& feat,
           const std::vector<dt::MatchesInfo>& pm, int n, const Session& s, double scale, double fixedFocal,
           const std::vector<cv::Matx33d>& init, BaOutput& out) {
    const int k = (int)ids.size();
    std::vector<dt::ImageFeatures> f(k);
    std::vector<dt::MatchesInfo> m(k * k);
    std::vector<dt::CameraParams> cams(k);
    for (int a = 0; a < k; ++a) {
        f[a] = feat[ids[a]];
        f[a].img_idx = a;
        for (int c = 0; c < k; ++c) {
            m[a * k + c] = pm[ids[a] * n + ids[c]];
            m[a * k + c].src_img_idx = a;
            m[a * k + c].dst_img_idx = c;
        }
        cams[a].focal = fixedFocal > 0 ? fixedFocal : s.fx * scale;
        cams[a].aspect = s.fy / s.fx;
        cams[a].ppx = s.cx * scale;
        cams[a].ppy = s.cy * scale;
        cv::Mat(fixedFocal > 0 ? init[a] : s.frames[ids[a]].R).convertTo(cams[a].R, CV_32F);
    }
    cv::Ptr<dt::BundleAdjusterBase> ba;
    if (fixedFocal > 0) {
        ba = cv::makePtr<dt::BundleAdjusterReproj>();
        ba->setRefinementMask(cv::Mat::zeros(3, 3, CV_8U));
    } else {
        ba = cv::makePtr<dt::BundleAdjusterRay>();
    }
    ba->setConfThresh(1.0);
    // OpenCV's default (1000 iterations, DBL_EPSILON) keeps iterating long after the rotations stop moving.
    ba->setTermCriteria(cv::TermCriteria(cv::TermCriteria::COUNT + cv::TermCriteria::EPS, 100, 1e-6));
    if (!(*ba)(f, m, cams)) return false;

    std::vector<cv::Matx33d> raw(k), sensor(k);
    out.R.resize(k);
    out.focal.resize(k);
    for (int a = 0; a < k; ++a) {
        cv::Mat r;
        cams[a].R.convertTo(r, CV_64F);
        raw[a] = nearestRotation(cv::Matx33d(r));
        sensor[a] = s.frames[ids[a]].R;
        out.focal[a] = cams[a].focal;
        if (!std::isfinite(cams[a].focal) || cams[a].focal <= 0) return false;
    }
    // BA leaves the global rotation free (it pins it to some centre image); put it back into the sensor frame.
    cv::Matx33d G = alignRotations(raw, sensor);
    for (int a = 0; a < k; ++a) out.R[a] = G * raw[a];
    out.residualDeg = rayResidualDeg(ids, feat, pm, n, out, s.fy / s.fx, s.cx * scale, s.cy * scale);
    return true;
}

}  // namespace

void registerCameras(const Session& s, const Options& o, Reporter& rep, Result& r) {
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
    std::vector<dt::ImageFeatures> feat(n);
    {
        // Low contrast threshold: walls, ceilings and floors are smooth; default 0.04 finds almost nothing there.
        cv::Ptr<cv::SIFT> sift = cv::SIFT::create(3000, 3, 0.01);
        for (int i = 0; i < n; ++i) {
            cv::Mat img = readImage(s.frames[i].path, s), small;
            cv::resize(img, small, cv::Size(), scale, scale, cv::INTER_AREA);
            dt::computeImageFeatures(sift, small, feat[i]);
            feat[i].img_idx = i;
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

    std::vector<dt::MatchesInfo> pm(n * n);
    for (int i = 0; i < n; ++i)
        for (int j = 0; j < n; ++j) {
            pm[i * n + j].src_img_idx = i;
            pm[i * n + j].dst_img_idx = j;
        }
    // No upper confidence limit: OpenCV drops "too similar" pairs as duplicates, but the sensors already tell
    // us the pair geometry, and synthetic or very sharp overlaps would be thrown away.
    dt::BestOf2NearestMatcher matcher(false, 0.3f, 6, 6, 1e9);
    const double f0 = s.fx * scale, aspect = s.fy / s.fx;
    const int batch = std::max(1, cv::getNumThreads());
    std::vector<double> relErr(cand.size(), 0);
    for (size_t b0 = 0; b0 < cand.size(); b0 += batch) {
        size_t b1 = std::min(cand.size(), b0 + batch);
        cv::parallel_for_(cv::Range((int)b0, (int)b1), [&](const cv::Range& rg) {
            for (int p = rg.start; p < rg.end; ++p) {
                auto [i, j] = cand[p];
                cv::theRNG() = cv::RNG(0x5eed + p);  // RANSAC must not depend on thread scheduling
                dt::MatchesInfo m;
                // FLANN cannot index an empty descriptor set (e.g. a uniform image).
                if (feat[i].keypoints.size() >= 6 && feat[j].keypoints.size() >= 6) matcher(feat[i], feat[j], m);
                m.src_img_idx = i;
                m.dst_img_idx = j;
                if (!m.H.empty() && m.num_inliers >= 6) {
                    // Matcher H works on coordinates centred at the image middle.
                    cv::Matx33d K(f0, 0, s.cx * scale - feat[i].img_size.width * 0.5, 0, f0 * aspect,
                                  s.cy * scale - feat[i].img_size.height * 0.5, 0, 0, 1);
                    cv::Matx33d M = K.inv() * cv::Matx33d(m.H) * K;
                    double det = cv::determinant(M);
                    relErr[p] = det == 0 ? 180
                                         : angleDeg(nearestRotation(M * (1 / std::cbrt(det))),
                                                    s.frames[j].R.t() * s.frames[i].R);
                } else {
                    relErr[p] = 180;
                }
                if (relErr[p] > o.maxRelativeRotationDeg) m.confidence = 0;
                // BA cost grows with every correspondence; ~150 spread over the overlap constrain a rotation plenty.
                const int maxInliers = 150;
                if (m.num_inliers > maxInliers) {
                    int seen = 0, kept = 0;
                    for (auto& in : m.inliers_mask)
                        if (in && (long)seen++ * maxInliers / m.num_inliers >= kept) ++kept;
                        else in = 0;
                    m.num_inliers = kept;
                }
                dt::MatchesInfo d = m;
                d.src_img_idx = j;
                d.dst_img_idx = i;
                if (!m.H.empty()) d.H = m.H.inv();
                for (auto& mt : d.matches) std::swap(mt.queryIdx, mt.trainIdx);
                pm[i * n + j] = std::move(m);
                pm[j * n + i] = std::move(d);
            }
        });
        rep.step((double)b1 / cand.size());
    }
    for (size_t p = 0; p < cand.size(); ++p) {
        auto [i, j] = cand[p];
        const dt::MatchesInfo& m = pm[i * n + j];
        r.pairs.push_back({s.frames[i].index, s.frames[j].index, m.num_inliers, m.confidence, relErr[p],
                           m.confidence > 1});
    }
    rep.end();

    rep.begin(Phase::BundleAdjust);
    std::vector<int> parent(n);
    std::iota(parent.begin(), parent.end(), 0);
    for (const auto& [i, j] : cand)
        if (pm[i * n + j].confidence > 1) parent[findRoot(parent, i)] = findRoot(parent, j);
    std::vector<int> size(n, 0);
    for (int i = 0; i < n; ++i) ++size[findRoot(parent, i)];
    int root = (int)(std::max_element(size.begin(), size.end()) - size.begin());
    std::vector<int> ids;
    for (int i = 0; i < n; ++i) {
        if (findRoot(parent, i) == root && size[root] > 1)
            ids.push_back(i);
        else
            r.cameras[i].reason = "no-consistent-matches";
    }

    // Pass 1 (Ray) estimates the focal: manifest intrinsics can be far off (the emulator reports about half
    // the real focal). Pass 2 (Reproj, rotations only) re-solves with the one shared focal used for warping,
    // so rotations and focal stay consistent.
    BaOutput ba;
    bool ok = false;
    double focal = s.fx * scale;
    for (int iter = 0; iter < 3 && ids.size() >= 2; ++iter) {
        BaOutput first;
        if (!runBa(ids, feat, pm, n, s, scale, 0, {}, first)) {
            r.fallbackReason = "ba-failed";
            break;
        }
        std::vector<double> fs = first.focal, dev;
        std::nth_element(fs.begin(), fs.begin() + fs.size() / 2, fs.end());
        focal = fs[fs.size() / 2];
        for (double f : first.focal) dev.push_back(std::abs(f - focal));
        std::nth_element(dev.begin(), dev.begin() + dev.size() / 2, dev.end());
        // All cameras are the same lens; disagreeing focals mean BA did not converge to the real geometry.
        if (dev[dev.size() / 2] > 0.05 * focal) {
            r.fallbackReason = "ba-focal-inconsistent";
            break;
        }
        if (!runBa(ids, feat, pm, n, s, scale, focal, first.R, ba)) {
            r.fallbackReason = "ba-failed";
            break;
        }
        std::vector<int> keep;
        for (size_t a = 0; a < ids.size(); ++a) {
            if (angleDeg(ba.R[a], s.frames[ids[a]].R) > o.maxCameraDeviationDeg)
                r.cameras[ids[a]].reason = "ba-deviates-from-sensor";
            else
                keep.push_back(ids[a]);
        }
        if (keep.size() == ids.size()) {
            ok = true;
            break;
        }
        ids = keep;  // the outlier also pulled its neighbours, so solve again without it
    }
    if (ok && ba.residualDeg > o.maxRayResidualDeg) {
        ok = false;
        r.fallbackReason = "ba-residual";
    }
    if (!ok && r.fallbackReason.empty()) r.fallbackReason = "too-few-registered-cameras";
    r.baRayResidualDeg = ok ? ba.residualDeg : 0;

    if (ok) {
        r.fallbackReason.clear();
        r.focal = focal / scale;
        r.focalSource = "bundle-adjusted";
        for (size_t a = 0; a < ids.size(); ++a) {
            r.cameras[ids[a]].rotation = ba.R[a];
            r.cameras[ids[a]].source = "bundle-adjusted";
            r.cameras[ids[a]].reason.clear();
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
