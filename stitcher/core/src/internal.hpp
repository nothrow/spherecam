#pragma once

#include <memory>
#include <stdexcept>
#include <tuple>
#include <string>
#include <vector>

#include <opencv2/core.hpp>

#include "spherestitch/stitch.hpp"

namespace spherestitch {

struct Cancelled {};

struct Frame {
    int index = 0;
    std::string path;
    cv::Matx33d R;  // R_world_from_camera from the sensors
};

struct Session {
    int width = 0, height = 0;
    double fx = 0, fy = 0, cx = 0, cy = 0;
    bool nadir = true;
    std::vector<Frame> frames;
};

Session loadSession(const std::string& dir);
// Contract: pixels are already upright, EXIF orientation must not be applied again.
cv::Mat readImage(const std::string& path, const Session& s);

class Reporter {
public:
    Reporter(const ProgressFn& fn, Result& r) : fn_(fn), r_(r) {}
    void begin(Phase p);
    void step(double fraction);  // throws Cancelled
    void end();

private:
    const ProgressFn& fn_;
    Result& r_;
    Phase phase_ = Phase::Load;
    int64_t t0_ = 0;
};

// Equirect mapping identical to tools/preview_from_manifest.py.
cv::Vec3d pixelToDir(double u, double v, int W, int H);
cv::Point2d dirToPixel(const cv::Vec3d& d, int W, int H);

struct Camera {
    cv::Matx33d R;  // R_world_from_camera
    double fx, fy, cx, cy;
    int width, height;
    cv::Vec3d C{0, 0, 0};             // centre relative to the pivot
    const cv::Mat* depth = nullptr;   // Result::depth; null: scene at infinity
    // Local alignment (alignLocally): CV_32FC2 equirect grid of (lon, lat) offsets in radians, added to the
    // panorama direction before this camera is looked up. Null: none.
    const cv::Mat* flow = nullptr;

    // Camera-frame direction of the scene point seen from the pivot in direction d; (gu, gv) are d's
    // coordinates on the depth grid.
    cv::Vec3d look(const cv::Vec3d& d, double gu, double gv) const;
};

// Camera i with the image scaled by k.
Camera scaledCamera(const Session& s, const Result& r, int i, double k);

// Gaussian blur of an equirect grid, periodic in longitude.
cv::Mat blurWrap(const cv::Mat& a, double sigma);

// Canvas x may be outside [0, W): the canvas extends periodically so images crossing lon +-180 stay whole.
void buildMap(const Camera& cam, int W, int H, const cv::Rect& roi, cv::Mat& mapx, cv::Mat& mapy);

// Parts of the periodic canvas [-pad, W+pad) x [0, H) that one image covers.
std::vector<cv::Rect> footprint(const Camera& cam, int W, int H, int pad);

double angleDeg(const cv::Matx33d& a, const cv::Matx33d& b);
cv::Matx33d nearestRotation(const cv::Matx33d& m);
// Rotation G minimising sum |G*from_i - to_i|.
cv::Matx33d alignRotations(const std::vector<cv::Matx33d>& from, const std::vector<cv::Matx33d>& to);

// gray: the frames in grey at registration scale, kept for the later phases.
void registerCameras(const Session& s, const Options& o, Reporter& rep, Result& r, std::vector<cv::Mat>& gray);

// What the depth model leaves misaligned (depth edges, its own smoothness) is taken out in the panorama
// plane: neighbouring frames are block-matched where they overlap and each is shifted half-way, smoothly.
// Returns one Camera::flow grid per camera.
std::vector<cv::Mat> alignLocally(const Session& s, const Result& r, const std::vector<cv::Mat>& gray);

void compose(const Session& s, const Options& o, Reporter& rep, Result& r, const std::vector<cv::Mat>& gray,
             cv::Mat& out);
void fillHoles(cv::Mat& img, const cv::Mat& mask);

}  // namespace spherestitch
