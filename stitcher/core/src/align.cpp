#include <opencv2/imgproc.hpp>

#include "internal.hpp"

namespace spherestitch {

std::vector<cv::Mat> alignLocally(const Session& s, const Result& r, const std::vector<cv::Mat>& gray) {
    // Matching happens in a 2048 wide panorama (0.18 deg per pixel), periodic by `pad` on both sides so
    // that frames across lon +-180 meet. Flow grids are 1.4 deg per cell.
    const int W = 2048, H = W / 2, pad = W / 12, GW = 256, GH = 128;
    const int block = 21, radius = 8, stride = 12;
    const int n = (int)gray.size();
    std::vector<cv::Mat> flows(n);
    for (auto& f : flows) f = cv::Mat::zeros(GH, GW, CV_32FC2);

    struct Piece {
        int cam;
        cv::Rect roi;
        cv::Mat img, mask;
    };
    const double k = W / (2 * CV_PI) / r.focal;
    // Twice: frames seen by three cameras get pulled toward two neighbours at once, and the first pass only
    // splits the difference.
    for (int pass = 0; pass < 2; ++pass) {
        std::vector<Piece> pieces;
        for (int i = 0; i < n; ++i) {
            Camera cam = scaledCamera(s, r, i, k);
            cam.flow = &flows[i];
            cv::Mat small;
            cv::resize(gray[i], small, cv::Size(cam.width, cam.height), 0, 0, cv::INTER_AREA);
            for (const cv::Rect& roi : footprint(cam, W, H, pad)) {
                Piece p{i, roi, {}, {}};
                cv::Mat mx, my;
                buildMap(cam, W, H, roi, mx, my);
                cv::remap(small, p.img, mx, my, cv::INTER_LINEAR);
                p.mask = mx >= 0;
                pieces.push_back(std::move(p));
            }
        }

        std::vector<cv::Mat> num(n), den(n);
        for (int i = 0; i < n; ++i) {
            num[i] = cv::Mat::zeros(GH, GW, CV_32FC2);
            den[i] = cv::Mat::zeros(GH, GW, CV_32F);
        }
        for (size_t a = 0; a < pieces.size(); ++a)
            for (size_t b = 0; b < pieces.size(); ++b) {
                const Piece& A = pieces[a];
                const Piece& B = pieces[b];
                if (A.cam >= B.cam) continue;
                cv::Rect in = A.roi & B.roi;
                // Block centres stay inside [0, W): the periodic copies would count twice.
                for (int cy = in.y + radius + block / 2; cy + block / 2 + radius < in.y + in.height; cy += stride)
                    for (int cx = std::max(in.x + radius + block / 2, 0);
                         cx + block / 2 + radius < in.x + in.width && cx < W; cx += stride) {
                        cv::Rect tb(cx - block / 2 - B.roi.x, cy - block / 2 - B.roi.y, block, block);
                        cv::Rect sa(cx - block / 2 - radius - A.roi.x, cy - block / 2 - radius - A.roi.y,
                                    block + 2 * radius, block + 2 * radius);
                        if (cv::countNonZero(B.mask(tb)) < tb.area() || cv::countNonZero(A.mask(sa)) < sa.area())
                            continue;
                        cv::Scalar mean, dev;
                        cv::meanStdDev(B.img(tb), mean, dev);
                        if (dev[0] < 4) continue;  // bare wall: any shift matches
                        cv::Mat resp;
                        cv::matchTemplate(A.img(sa), B.img(tb), resp, cv::TM_CCOEFF_NORMED);
                        // Repeated patterns (pegboards, curtain folds) match at several shifts; the frames are
                        // already close, so the nearest one is the right one.
                        double best = -1;
                        cv::Point at;
                        for (int y = 0; y < resp.rows; ++y)
                            for (int x = 0; x < resp.cols; ++x) {
                                double v = resp.at<float>(y, x) - 0.01 * std::hypot(x - radius, y - radius);
                                if (v > best) best = v, at = cv::Point(x, y);
                            }
                        double ncc = resp.at<float>(at);
                        if (ncc < 0.8 || at.x == 0 || at.y == 0 || at.x == resp.cols - 1 || at.y == resp.rows - 1)
                            continue;
                        auto sub = [](double l, double c, double rr) {
                            double d = l - 2 * c + rr;
                            return d < -1e-9 ? 0.5 * (l - rr) / d : 0.0;
                        };
                        // The content of B at the block centre sits in A at centre + (dx, dy).
                        double dx = at.x - radius + sub(resp.at<float>(at.y, at.x - 1), ncc, resp.at<float>(at.y, at.x + 1));
                        double dy = at.y - radius + sub(resp.at<float>(at.y - 1, at.x), ncc, resp.at<float>(at.y + 1, at.x));
                        // Both meet half-way; pixels to (lon, lat), lon decreasing with x.
                        cv::Vec2f half((float)(-dx / 2 * 2 * CV_PI / W), (float)(-dy / 2 * CV_PI / H));
                        int gx = (int)std::floor((cx + dx / 2 + 0.5) / W * GW), gy = (int)std::floor((cy + dy / 2 + 0.5) / H * GH);
                        gx = ((gx % GW) + GW) % GW;
                        gy = std::clamp(gy, 0, GH - 1);
                        num[A.cam].at<cv::Vec2f>(gy, gx) += half;
                        num[B.cam].at<cv::Vec2f>(gy, gx) -= half;
                        den[A.cam].at<float>(gy, gx) += 1;
                        den[B.cam].at<float>(gy, gx) += 1;
                    }
            }
        for (int i = 0; i < n; ++i) {
            // Smooth, and fading to nothing away from the overlaps: a frame alone has nothing to align to.
            // Sigma 5.6 deg: at half of that the shifts follow depth edges and door frames come out wavy.
            cv::Mat d = blurWrap(den[i], 4) + 0.05, nb = blurWrap(num[i], 4), d2;
            cv::merge(std::vector<cv::Mat>{d, d}, d2);
            flows[i] += nb / d2;
        }
    }
    return flows;
}

}  // namespace spherestitch
