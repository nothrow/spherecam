// Synthetic sessions rendered from a procedural spherical texture with known rotations.
// The equirect mapping here is written independently of core/, straight from tools/preview_from_manifest.py,
// so a convention error in core shows up as a test failure instead of cancelling out.
#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

#include <opencv2/geometry.hpp>
#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace synth {

constexpr double kPi = 3.14159265358979323846;

#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        if (!(cond)) {                                                         \
            std::fprintf(stderr, "%s:%d CHECK(%s) failed: ", __FILE__, __LINE__, #cond); \
            std::fprintf(stderr, __VA_ARGS__);                                 \
            std::fprintf(stderr, "\n");                                        \
            std::exit(1);                                                      \
        }                                                                      \
    } while (0)

inline uint32_t hash(int32_t x, int32_t y, int32_t z, uint32_t seed) {
    uint32_t h = seed * 0x9E3779B1u;
    h ^= (uint32_t)x * 0x85EBCA77u;
    h = (h << 13 | h >> 19) * 0xC2B2AE3Du;
    h ^= (uint32_t)y * 0x27D4EB2Fu;
    h = (h << 13 | h >> 19) * 0x165667B1u;
    h ^= (uint32_t)z * 0x9E3779B1u;
    h ^= h >> 15;
    h *= 0x85EBCA77u;
    h ^= h >> 13;
    return h;
}

inline double valueNoise(double x, double y, double z, uint32_t seed) {
    double fx = std::floor(x), fy = std::floor(y), fz = std::floor(z);
    int ix = (int)fx, iy = (int)fy, iz = (int)fz;
    auto s = [](double t) { return t * t * (3 - 2 * t); };
    double tx = s(x - fx), ty = s(y - fy), tz = s(z - fz);
    double v[2][2][2];
    for (int a = 0; a < 2; ++a)
        for (int b = 0; b < 2; ++b)
            for (int c = 0; c < 2; ++c) v[a][b][c] = hash(ix + a, iy + b, iz + c, seed) / 4294967295.0;
    auto l = [](double p, double q, double t) { return p + (q - p) * t; };
    return l(l(l(v[0][0][0], v[1][0][0], tx), l(v[0][1][0], v[1][1][0], tx), ty),
             l(l(v[0][0][1], v[1][0][1], tx), l(v[0][1][1], v[1][1][1], tx), ty), tz);
}

// Octant colour (like tools/test_preview.py) modulated by multi-scale noise so SIFT has texture everywhere.
struct Texture {
    double maxFreq = 48;
    cv::Vec3b operator()(const cv::Vec3d& d) const {
        int bits = (d[0] > 0) * 4 + (d[1] > 0) * 2 + (d[2] > 0);
        // Soft-thresholded octaves give crisp blobs and corners at every scale, which SIFT needs.
        double n = 0, amp = 0, a = 1;
        for (double f = 3; f <= maxFreq; f *= 2, a *= 0.75) {
            n += a * std::tanh((valueNoise(d[0] * f, d[1] * f, d[2] * f, (uint32_t)f) - 0.5) * 10);
            amp += a;
        }
        double m = 0.8 + 0.45 * n / amp;
        cv::Vec3b out;
        for (int c = 0; c < 3; ++c) {
            double base = 40 + 190 * ((bits >> (2 - c)) & 1);
            out[c] = cv::saturate_cast<uchar>(base * m);
        }
        // Sparse discs (one per 3D cell, kept inside the cell so a single lookup suffices): strong blobs.
        for (double f = 4; f <= maxFreq; f *= 2) {
            double x = d[0] * f, y = d[1] * f, z = d[2] * f;
            int ix = (int)std::floor(x), iy = (int)std::floor(y), iz = (int)std::floor(z);
            uint32_t h = hash(ix, iy, iz, 77 + (uint32_t)f);
            if (h % 5 == 0) continue;
            double px = ix + 0.3 + 0.4 * ((h >> 4) & 255) / 255.0, py = iy + 0.3 + 0.4 * ((h >> 12) & 255) / 255.0,
                   pz = iz + 0.3 + 0.4 * ((h >> 20) & 255) / 255.0, rad = 0.08 + 0.2 * (h >> 28) / 15.0;
            if ((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz) < rad * rad)
                out = (h & 8) ? cv::Vec3b(20 + (h & 7) * 10, 30, 20 + (h >> 29) * 20)
                              : cv::Vec3b(235, 225 - (h & 7) * 15, 240);
        }
        return out;
    }
};

inline cv::Vec3d equirectDir(double u, double v, int W, int H) {
    double lon = kPi - (u + 0.5) / W * 2 * kPi;
    double lat = kPi / 2 - (v + 0.5) / H * kPi;
    return {std::cos(lat) * std::cos(lon), std::cos(lat) * std::sin(lon), std::sin(lat)};
}

// `G` rotates the source sphere, for comparing with an output whose frame differs by a global rotation.
inline cv::Mat renderEquirect(const Texture& t, int W, const cv::Matx33d& G = cv::Matx33d::eye()) {
    cv::Mat out(W / 2, W, CV_8UC3);
    cv::parallel_for_(cv::Range(0, out.rows), [&](const cv::Range& r) {
        for (int y = r.start; y < r.end; ++y)
            for (int x = 0; x < W; ++x) out.at<cv::Vec3b>(y, x) = t(G.t() * equirectDir(x, y, W, out.rows));
    });
    return out;
}

// R_world_from_camera looking at (yaw, pitch), image upright (camera x right, y down).
inline cv::Matx33d lookAt(double yawDeg, double pitchDeg) {
    double y = yawDeg * kPi / 180, p = pitchDeg * kPi / 180;
    cv::Vec3d z(std::cos(p) * std::cos(y), std::cos(p) * std::sin(y), std::sin(p));
    cv::Vec3d x(std::sin(y), -std::cos(y), 0);
    cv::Vec3d yy = z.cross(x);
    return cv::Matx33d(x[0], yy[0], z[0], x[1], yy[1], z[1], x[2], yy[2], z[2]);
}

inline cv::Matx33d rodrigues(const cv::Vec3d& axisAngle) {
    cv::Matx33d R;
    cv::Rodrigues(axisAngle, R);
    return R;
}

struct Spec {
    int width = 480, height = 640;
    double hfovDeg = 60;
    std::vector<std::pair<double, double>> shots;  // yaw, pitch
    bool nadir = true;
    double noiseMinDeg = 0, noiseMaxDeg = 0;  // random rotation error per frame
    double yawDriftDeg = 0;                   // grows linearly from 0 at the first frame
    int textureless = -1;                     // this frame is a uniform grey image
    std::pair<int, int> swapImages{-1, -1};   // these two frames get each other's pixels
    uint64_t seed = 1;
};

// 9 + 7 + 7 + zenith + nadir for a 60 deg wide portrait camera. Without nadir the lower ring is raised, which
// leaves a hole below about -72 deg.
inline Spec smallSpec(bool nadir = true) {
    Spec s;
    for (int i = 0; i < 9; ++i) s.shots.emplace_back(i * 40.0, 0);
    for (int i = 0; i < 7; ++i) s.shots.emplace_back(20 + i * 360.0 / 7, 50);
    for (int i = 0; i < 7; ++i) s.shots.emplace_back(10 + i * 360.0 / 7, nadir ? -50 : -35);
    s.shots.emplace_back(0, 90);
    if (nadir) s.shots.emplace_back(0, -90);
    s.nadir = nadir;
    return s;
}

// The 36-frame 1536x2048 layout of tools/test_preview.py.
inline Spec largeSpec() {
    Spec s;
    s.width = 1536;
    s.height = 2048;
    s.hfovDeg = 50;
    for (int i = 0; i < 11; ++i) s.shots.emplace_back(i * 360.0 / 11, 0);
    for (int p : {45, -45})
        for (int i = 0; i < 11; ++i) s.shots.emplace_back(i * 360.0 / 11, p);
    s.shots.emplace_back(0, 90);
    s.shots.emplace_back(0, -90);
    s.shots.emplace_back(180, -90);
    return s;
}

struct Session {
    std::string dir;
    std::vector<cv::Matx33d> truth;
    Texture texture;
};

inline Session generate(const std::string& dir, const Spec& sp) {
    namespace fs = std::filesystem;
    fs::remove_all(dir);
    fs::create_directories(dir);
    Session out{dir, {}, {}};
    const double f = sp.width / 2.0 / std::tan(sp.hfovDeg / 2 * kPi / 180);
    const double cx = sp.width / 2.0, cy = sp.height / 2.0;
    out.texture.maxFreq = f / 9;  // finest noise cells stay ~9 px wide
    cv::RNG rng(sp.seed);
    const int n = (int)sp.shots.size();
    std::vector<cv::Matx33d> sensor(n);
    for (int i = 0; i < n; ++i) {
        cv::Matx33d R = lookAt(sp.shots[i].first, sp.shots[i].second);
        out.truth.push_back(R);
        cv::Vec3d axis(rng.gaussian(1), rng.gaussian(1), rng.gaussian(1));
        double ang = rng.uniform(sp.noiseMinDeg, sp.noiseMaxDeg + 1e-12) * kPi / 180;
        cv::Matx33d noise = rodrigues(axis * (ang / cv::norm(axis)));
        double drift = n > 1 ? sp.yawDriftDeg * i / (n - 1) * kPi / 180 : 0;
        sensor[i] = rodrigues(cv::Vec3d(0, 0, drift)) * R * noise;
    }
    for (int i = 0; i < n; ++i) {
        int src = i == sp.swapImages.first ? sp.swapImages.second : i == sp.swapImages.second ? sp.swapImages.first : i;
        cv::Mat img(sp.height, sp.width, CV_8UC3, cv::Scalar::all(128));
        if (i != sp.textureless) {
            const cv::Matx33d R = out.truth[src];
            cv::parallel_for_(cv::Range(0, sp.height), [&](const cv::Range& r) {
                for (int v = r.start; v < r.end; ++v)
                    for (int u = 0; u < sp.width; ++u) {
                        cv::Vec3d d = R * cv::Vec3d((u - cx) / f, (v - cy) / f, 1);
                        img.at<cv::Vec3b>(v, u) = out.texture(d * (1 / cv::norm(d)));
                    }
            });
        }
        char name[32];
        std::snprintf(name, sizeof name, "img_%03d.jpg", i);
        cv::imwrite(dir + "/" + name, img, {cv::IMWRITE_JPEG_QUALITY, 95});
    }

    std::ofstream m(dir + "/manifest.json");
    m.precision(17);
    m << "{\"schemaVersion\":1,\"sessionId\":\"synthetic\",\"status\":\"captured\",\n"
      << "\"settings\":{\"nadir\":" << (sp.nadir ? "true" : "false") << ",\"targetOverlap\":0.3},\n"
      << "\"camera\":{\"imageWidth\":" << sp.width << ",\"imageHeight\":" << sp.height << ",\"fx\":" << f
      << ",\"fy\":" << f << ",\"cx\":" << cx << ",\"cy\":" << cy
      << ",\"lensIntrinsicCalibration\":null,\"timestampSource\":\"UNKNOWN\"},\n\"frames\":[";
    for (int i = 0; i < n; ++i) {
        char name[32];
        std::snprintf(name, sizeof name, "img_%03d.jpg", i);
        m << (i ? ",\n" : "\n") << "{\"index\":" << i << ",\"file\":\"" << name << "\",\"rotation\":[";
        for (int k = 0; k < 9; ++k) m << (k ? "," : "") << sensor[i].val[k];
        m << "],\"exposureTimestampNs\":" << 123456789000LL + i << ",\"rotationSource\":\"interpolated\"}";
    }
    m << "]}\n";
    return out;
}

// Mean absolute error after area-downsampling both to `w` columns: compares content, not texture aliasing.
inline double mae(const cv::Mat& a, const cv::Mat& b, int w = 256, cv::Range rows = cv::Range::all()) {
    cv::Mat sa, sb, d;
    cv::resize(a, sa, cv::Size(w, w / 2), 0, 0, cv::INTER_AREA);
    cv::resize(b, sb, cv::Size(w, w / 2), 0, 0, cv::INTER_AREA);
    if (rows != cv::Range::all()) {
        sa = sa.rowRange(rows.start * w / 2 / a.rows, rows.end * w / 2 / a.rows);
        sb = sb.rowRange(rows.start * w / 2 / b.rows, rows.end * w / 2 / b.rows);
    }
    cv::absdiff(sa, sb, d);
    cv::Scalar s = cv::mean(d);
    return (s[0] + s[1] + s[2]) / 3;
}

inline double angleDeg(const cv::Matx33d& a, const cv::Matx33d& b) {
    cv::Matx33d m = a.t() * b;
    double c = (m(0, 0) + m(1, 1) + m(2, 2) - 1) / 2;
    return std::acos(std::max(-1.0, std::min(1.0, c))) * 180 / kPi;
}

// Best global G with G * truth_i ~ est_i.
inline cv::Matx33d globalRotation(const std::vector<cv::Matx33d>& truth, const std::vector<cv::Matx33d>& est) {
    cv::Matx33d m = cv::Matx33d::zeros();
    for (size_t i = 0; i < est.size(); ++i) m += est[i] * truth[i].t();
    cv::Matx33d u, vt;
    cv::Matx31d w;
    cv::SVD::compute(m, w, u, vt);
    cv::Matx33d d = cv::Matx33d::eye();
    if (cv::determinant(u * vt) < 0) d(2, 2) = -1;
    return u * d * vt;
}

// Max angle between rotations after the best global rotation is removed.
inline double maxErrorAfterAlignment(const std::vector<cv::Matx33d>& est, const std::vector<cv::Matx33d>& truth) {
    cv::Matx33d G = globalRotation(est, truth);
    double e = 0;
    for (size_t i = 0; i < est.size(); ++i) e = std::max(e, angleDeg(G * est[i], truth[i]));
    return e;
}

inline std::string tmpDir(const std::string& name) {
    return (std::filesystem::temp_directory_path() / ("spherestitch_" + name)).string();
}

}  // namespace synth
