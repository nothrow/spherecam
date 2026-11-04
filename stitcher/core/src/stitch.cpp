#include <opencv2/imgcodecs.hpp>

#include "internal.hpp"

namespace spherestitch {

Result stitch(const std::string& sessionDir, const std::string& outPath, const Options& o,
              const ProgressFn& progress) {
    Result r;
    Reporter rep(progress, r);
    try {
        if (o.width < 256 || o.width % 2) throw std::invalid_argument("width must be even and >= 256");
        rep.begin(Phase::Load);
        Session s = loadSession(sessionDir);
        rep.end();

        registerCameras(s, o, rep, r);
        cv::Mat out;
        compose(s, o, rep, r, out);
        // imwrite adds no EXIF, so the output carries no orientation tag and no metadata.
        if (!cv::imwrite(outPath, out, {cv::IMWRITE_JPEG_QUALITY, o.jpegQuality}))
            throw std::runtime_error("cannot write " + outPath);
        rep.end();
        r.ok = true;
    } catch (const Cancelled&) {
        r.cancelled = true;
        r.error = "cancelled";
    } catch (const std::exception& e) {
        r.error = e.what();
    }
    return r;
}

void writeReport(const Result& r, const std::string& path) {
    cv::FileStorage fs(path, cv::FileStorage::WRITE | cv::FileStorage::FORMAT_JSON);
    if (!fs.isOpened()) throw std::runtime_error("cannot write " + path);
    fs << "ok" << r.ok << "cancelled" << r.cancelled << "error" << r.error;
    fs << "rotationSource" << r.rotationSource << "fallbackReason" << r.fallbackReason;
    fs << "focal" << r.focal << "focalSource" << r.focalSource << "manifestFocal" << r.manifestFocal
       << "baRayResidualDeg" << r.baRayResidualDeg;
    fs << "cameras" << "[";
    for (const auto& c : r.cameras) {
        fs << "{" << "index" << c.index << "source" << c.source << "reason" << c.reason
           << "deviationDeg" << c.deviationDeg << "gain" << std::vector<double>{c.gain[0], c.gain[1], c.gain[2]}
           << "rotation" << std::vector<double>(c.rotation.val, c.rotation.val + 9) << "}";
    }
    fs << "]" << "pairs" << "[";
    for (const auto& p : r.pairs)
        fs << "{" << "i" << p.i << "j" << p.j << "inliers" << p.inliers << "confidence" << p.confidence
           << "relativeErrorDeg" << p.relativeErrorDeg << "accepted" << p.accepted << "}";
    fs << "]" << "phaseSeconds" << "{";
    for (const auto& [name, sec] : r.phaseSeconds) fs << name << sec;
    fs << "}";
}

}  // namespace spherestitch
