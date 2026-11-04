#include <cmath>

#include <opencv2/imgcodecs.hpp>

#include "internal.hpp"

namespace spherestitch {

namespace {

std::runtime_error bad(const std::string& what) { return std::runtime_error("manifest: " + what); }

double number(const cv::FileNode& n, const char* name) {
    if (!n.isReal() && !n.isInt()) throw bad(std::string("missing number ") + name);
    return (double)n;
}

}  // namespace

Session loadSession(const std::string& dir) {
    cv::FileStorage fs(dir + "/manifest.json", cv::FileStorage::READ | cv::FileStorage::FORMAT_JSON);
    if (!fs.isOpened()) throw bad("cannot open " + dir + "/manifest.json");
    if ((int)fs["schemaVersion"] != 1) throw bad("unsupported schemaVersion");

    Session s;
    cv::FileNode cam = fs["camera"];
    s.width = (int)number(cam["imageWidth"], "imageWidth");
    s.height = (int)number(cam["imageHeight"], "imageHeight");
    s.fx = number(cam["fx"], "fx");
    s.fy = number(cam["fy"], "fy");
    s.cx = number(cam["cx"], "cx");
    s.cy = number(cam["cy"], "cy");
    if (s.width <= 0 || s.height <= 0 || s.fx <= 0 || s.fy <= 0) throw bad("invalid camera intrinsics");
    cv::FileNode nadir = fs["settings"]["nadir"];
    s.nadir = nadir.empty() ? true : (int)nadir != 0;

    for (const cv::FileNode& f : fs["frames"]) {
        Frame fr;
        fr.index = (int)f["index"];
        std::string file = (std::string)f["file"];
        if (file.empty() || file.find('/') != std::string::npos || file.find('\\') != std::string::npos)
            throw bad("frame " + std::to_string(fr.index) + ": invalid file name");
        fr.path = dir + "/" + file;
        cv::FileNode rot = f["rotation"];
        if (!rot.isSeq() || rot.size() != 9) throw bad("frame " + std::to_string(fr.index) + ": rotation");
        for (int k = 0; k < 9; ++k) fr.R.val[k] = (double)rot[k];
        cv::Matx33d e = fr.R * fr.R.t() - cv::Matx33d::eye();
        if (cv::norm(e, cv::NORM_INF) > 1e-3 || std::abs(cv::determinant(fr.R) - 1) > 1e-3)
            throw bad("frame " + std::to_string(fr.index) + ": rotation is not orthonormal with det +1");
        fr.R = nearestRotation(fr.R);
        s.frames.push_back(fr);
    }
    if (s.frames.size() < 2) throw bad("fewer than 2 frames");
    return s;
}

cv::Mat readImage(const std::string& path, const Session& s) {
    cv::Mat img = cv::imread(path, cv::IMREAD_COLOR | cv::IMREAD_IGNORE_ORIENTATION);
    if (img.empty()) throw std::runtime_error("cannot read " + path);
    if (img.cols != s.width || img.rows != s.height)
        throw std::runtime_error(path + ": size " + std::to_string(img.cols) + "x" + std::to_string(img.rows) +
                                 " does not match the manifest");
    return img;
}

}  // namespace spherestitch
