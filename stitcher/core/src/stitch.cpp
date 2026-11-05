#include <opencv2/imgcodecs.hpp>

#include "internal.hpp"

namespace spherestitch {

// The output is always the full sphere, so the cropped area is the whole pano.
static std::string gpanoXmp(int W, int H) {
    std::string w = std::to_string(W), h = std::to_string(H);
    // OpenCV writes the chunk as APP1 verbatim, so it must carry the XMP namespace header itself.
    return std::string("http://ns.adobe.com/xap/1.0/", 29) +
           "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
           "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
           "<rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\""
           " GPano:ProjectionType=\"equirectangular\" GPano:UsePanoramaViewer=\"True\""
           " GPano:FullPanoWidthPixels=\"" + w + "\" GPano:FullPanoHeightPixels=\"" + h + "\""
           " GPano:CroppedAreaImageWidthPixels=\"" + w + "\" GPano:CroppedAreaImageHeightPixels=\"" + h + "\""
           " GPano:CroppedAreaLeftPixels=\"0\" GPano:CroppedAreaTopPixels=\"0\"/>"
           "</rdf:RDF></x:xmpmeta>";
}

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
        // No EXIF, so the output carries no orientation tag.
        std::string xmp = gpanoXmp(out.cols, out.rows);
        std::vector<cv::Mat> meta{cv::Mat(1, (int)xmp.size(), CV_8U, xmp.data())};
        if (!cv::imwriteWithMetadata(outPath, out, {cv::IMAGE_METADATA_XMP}, meta,
                                     {cv::IMWRITE_JPEG_QUALITY, o.jpegQuality}))
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
    fs << "bodyOffset" << std::vector<double>{r.bodyOffset[0], r.bodyOffset[1], r.bodyOffset[2]}
       << "lever" << std::vector<double>{r.lever[0], r.lever[1], r.lever[2]} << "depthModel" << !r.depth.empty();
    fs << "cameras" << "[";
    for (const auto& c : r.cameras) {
        fs << "{" << "index" << c.index << "source" << c.source << "reason" << c.reason
           << "deviationDeg" << c.deviationDeg << "gain" << std::vector<double>{c.gain[0], c.gain[1], c.gain[2]}
           << "rotation" << std::vector<double>(c.rotation.val, c.rotation.val + 9)
           << "centre" << std::vector<double>{c.centre[0], c.centre[1], c.centre[2]} << "}";
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
