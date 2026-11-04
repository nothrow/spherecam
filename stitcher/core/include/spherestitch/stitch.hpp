#pragma once

#include <functional>
#include <string>
#include <vector>

#include <opencv2/core.hpp>

namespace spherestitch {

enum class Phase { Load, Features, Matching, BundleAdjust, Warp, Exposure, Seams, Blend, Encode };
const char* phaseName(Phase p);

// Called often; fraction is within the current phase (0..1). Return false to cancel.
using ProgressFn = std::function<bool(Phase, double fraction)>;

struct Options {
    int width = 6144;               // output is width x width/2
    std::string debugDir;           // empty = no debug output
    double registrationMegapix = 0.6;
    int seamWidth = 512;           // equirect width used for exposure and seam estimation
    double maxRelativeRotationDeg = 10.0; // pair rejected if its match disagrees with the sensors more than this
    double maxCameraDeviationDeg = 8.0;   // camera falls back to its sensor rotation beyond this
    double maxRayResidualDeg = 0.5;       // whole BA rejected if its RMS ray residual is larger
    int jpegQuality = 92;
};

struct CameraResult {
    int index = 0;
    std::string source;             // "bundle-adjusted" or "sensor"
    std::string reason;             // why sensor was used, empty otherwise
    double deviationDeg = 0;        // angle between final and sensor rotation
    cv::Matx33d rotation;           // final R_world_from_camera
    cv::Vec3d gain{1, 1, 1};        // exposure compensation, BGR
};

struct PairResult {
    int i = 0, j = 0, inliers = 0;
    double confidence = 0, relativeErrorDeg = 0;
    bool accepted = false;
};

struct Result {
    bool ok = false;
    bool cancelled = false;
    std::string error;
    std::string rotationSource;     // "bundle-adjusted", "mixed" or "sensor"
    std::string fallbackReason;     // set when BA was rejected as a whole
    double focal = 0;               // focal used for warping, in image pixels
    std::string focalSource;        // "manifest" or "bundle-adjusted"
    double manifestFocal = 0;
    double baRayResidualDeg = 0;
    std::vector<CameraResult> cameras;
    std::vector<PairResult> pairs;
    std::vector<std::pair<std::string, double>> phaseSeconds;
};

Result stitch(const std::string& sessionDir, const std::string& outPath, const Options& options = {},
              const ProgressFn& progress = {});

// The JSON report written next to the output (and into the debug dir).
void writeReport(const Result& r, const std::string& path);

}  // namespace spherestitch
