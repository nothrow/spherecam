#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>

#include <opencv2/imgcodecs.hpp>

#include "spherestitch/stitch.hpp"

namespace fs = std::filesystem;

static int usage() {
    std::fprintf(stderr, "usage: spherestitch <session_dir> [-o out.jpg] [--width 6144] [--debug-dir DIR]\n");
    return 2;
}

int main(int argc, char** argv) {
    std::string session, out, report;
    spherestitch::Options o;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        bool hasValue = i + 1 < argc;
        if (a == "-o" && hasValue) out = argv[++i];
        else if (a == "--width" && hasValue) o.width = std::atoi(argv[++i]);
        else if (a == "--debug-dir" && hasValue) o.debugDir = argv[++i];
        else if (a.size() && a[0] != '-' && session.empty()) session = a;
        else return usage();
    }
    if (session.empty()) return usage();
    if (out.empty()) out = (fs::path(session) / "equirect.jpg").string();
    if (!o.debugDir.empty()) fs::create_directories(o.debugDir);

    spherestitch::Phase last = spherestitch::Phase::Load;
    auto progress = [&](spherestitch::Phase p, double f) {
        if (p != last || f == 0) std::fprintf(stderr, "%s ", spherestitch::phaseName(p));
        if (f >= 1) std::fprintf(stderr, "done\n");
        last = p;
        return true;
    };
    spherestitch::Result r = spherestitch::stitch(session, out, o, progress);

    std::string reportPath = fs::path(out).replace_extension(".json").string();
    spherestitch::writeReport(r, reportPath);
    if (!o.debugDir.empty()) spherestitch::writeReport(r, (fs::path(o.debugDir) / "report.json").string());
    if (!o.debugDir.empty() && !r.depth.empty()) {
        cv::FileStorage f((fs::path(o.debugDir) / "depth.json").string(), cv::FileStorage::WRITE);
        f << "depth" << r.depth;
        cv::Mat vis;
        cv::divide(255.0 / 4, r.depth, vis, CV_8U);  // inverse distance, white = a quarter of the median
        cv::imwrite((fs::path(o.debugDir) / "depth.png").string(), vis);
    }
    if (!r.ok) {
        std::fprintf(stderr, "\nerror: %s\n", r.error.c_str());
        return 1;
    }
    int nba = 0;
    for (const auto& c : r.cameras) nba += c.source == "bundle-adjusted";
    std::fprintf(stderr, "wrote %s (rotations: %s, %d/%zu bundle-adjusted, residual %.3f deg, focal %.1f)\n",
                 out.c_str(), r.rotationSource.c_str(), nba, r.cameras.size(), r.baRayResidualDeg, r.focal);
    return 0;
}
