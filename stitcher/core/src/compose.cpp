#include <algorithm>
#include <cmath>
#include <cstdio>

#include <opencv2/geometry/2d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>

#include "internal.hpp"

namespace spherestitch {

namespace dt = cv::detail;

namespace {

// The whole composition happens on a canvas extended by `pad` columns on both sides, filled periodically,
// so images crossing lon +-180 are never cut and blending sees the same content on both sides of the wrap.
struct Canvas {
    int W, H, pad;
    cv::Rect rect() const { return {-pad, 0, W + 2 * pad, H}; }
};

struct Piece {
    int cam;
    cv::Rect roi;  // canvas coordinates
    cv::Mat img, mask;
};

void warpPiece(const cv::Mat& img, const Camera& cam, const Canvas& c, const cv::Rect& roi, cv::Mat& out,
               cv::Mat& mask) {
    cv::Mat mx, my;
    buildMap(cam, c.W, c.H, roi, mx, my);
    cv::remap(img, out, mx, my, cv::INTER_LINEAR, cv::BORDER_REFLECT);
    mask = mx >= 0;
}

// Make the pad columns copies of the interior columns they alias.
void makePeriodic(cv::Mat& m, int W, int pad) {
    m.colRange(W, W + pad).copyTo(m.colRange(0, pad));
    m.colRange(pad, 2 * pad).copyTo(m.colRange(W + pad, W + 2 * pad));
}

cv::Mat colorLabels(const cv::Mat& label) {
    cv::Mat out(label.size(), CV_8UC3, cv::Scalar::all(0));
    for (int y = 0; y < label.rows; ++y)
        for (int x = 0; x < label.cols; ++x) {
            int l = label.at<int>(y, x);
            if (l >= 0) out.at<cv::Vec3b>(y, x) = cv::Vec3b(60 + (l * 97) % 196, 60 + (l * 57) % 196, 60 + (l * 37) % 196);
        }
    return out;
}

}  // namespace

void compose(const Session& s, const Options& o, Reporter& rep, Result& r, const std::vector<cv::Mat>& gray,
             cv::Mat& out) {
    const int n = (int)s.frames.size();
    const int W = o.width, H = W / 2;
    const int Ws = std::min(o.seamWidth, W) & ~1, Hs = Ws / 2;
    const Canvas full{W, H, W / 12}, low{Ws, Hs, Ws / 12 + 2};
    const double ls = (double)Ws / W;

    // Warp at seam resolution: oversample the canvas 2x at most.
    rep.begin(Phase::Warp);
    std::vector<cv::Mat> flows;
    if (!r.depth.empty()) flows = alignLocally(s, r, gray);
    if (!o.debugDir.empty() && !flows.empty()) {
        FILE* f = std::fopen((o.debugDir + "/flows.bin").c_str(), "wb");
        for (const cv::Mat& m : flows) std::fwrite(m.data, 1, m.total() * m.elemSize(), f);
        std::fclose(f);
    }
    rep.step(0.3);
    const double k = std::min(1.0, 2 * Ws / (2 * CV_PI) / r.focal);
    std::vector<Piece> pieces;
    for (int i = 0; i < n; ++i) {
        cv::Mat img = readImage(s.frames[i].path, s), small;
        Camera cam = scaledCamera(s, r, i, k);
        if (!flows.empty()) cam.flow = &flows[i];
        cv::resize(img, small, cv::Size(cam.width, cam.height), 0, 0, cv::INTER_AREA);
        for (const cv::Rect& roi : footprint(cam, low.W, low.H, low.pad)) {
            Piece p{i, roi, {}, {}};
            warpPiece(small, cam, low, roi, p.img, p.mask);
            if (cv::countNonZero(p.mask) > 0) pieces.push_back(std::move(p));
        }
        rep.step((i + 1.0) / n);
    }
    rep.end();

    rep.begin(Phase::Exposure);
    std::vector<cv::Point> corners;
    std::vector<cv::UMat> imgs, masks;
    for (const Piece& p : pieces) {
        corners.push_back(p.roi.tl() + cv::Point(low.pad, 0));
        imgs.push_back(p.img.getUMat(cv::ACCESS_READ));
        masks.push_back(p.mask.getUMat(cv::ACCESS_READ));
    }
    {
        dt::ChannelsCompensator comp;
        static_cast<dt::ExposureCompensator&>(comp).feed(corners, imgs, masks);
        std::vector<cv::Mat> g;
        comp.getMatGains(g);
        std::vector<cv::Vec3d> sum(n, cv::Vec3d(0, 0, 0));
        std::vector<int> cnt(n, 0);
        for (size_t p = 0; p < pieces.size(); ++p) {
            for (int c = 0; c < 3; ++c) sum[pieces[p].cam][c] += g[p].at<double>(c);
            ++cnt[pieces[p].cam];
        }
        // A camera split into several pieces gets one gain, otherwise the wrap would show a step.
        for (int i = 0; i < n; ++i)
            if (cnt[i]) r.cameras[i].gain = sum[i] * (1.0 / cnt[i]);
    }
    rep.end();

    rep.begin(Phase::Seams);
    std::vector<cv::UMat> fimgs(pieces.size()), smasks(pieces.size());
    std::vector<cv::Mat> dists(pieces.size());
    const float seamMargin = 0.01f * low.W;  // 3.6 deg
    for (size_t p = 0; p < pieces.size(); ++p) {
        cv::Mat f;
        cv::Vec3d g = r.cameras[pieces[p].cam].gain;
        pieces[p].img.convertTo(f, CV_32FC3);
        cv::multiply(f, cv::Scalar(g[0], g[1], g[2]), f);
        f.copyTo(fimgs[p]);
        // With frames that differ in brightness everywhere, the cheapest cut runs along an image border,
        // where the blender has nothing on the other side to blend with. Keep the seams a blend width inside.
        cv::distanceTransform(pieces[p].mask, dists[p], cv::DIST_L2, 3);
        cv::Mat(dists[p] > seamMargin).copyTo(smasks[p]);
    }
    dt::GraphCutSeamFinder(dt::GraphCutSeamFinderBase::COST_COLOR_GRAD).find(fimgs, corners, smasks);
    rep.step(0.8);
    cv::Mat label(low.H, low.W + 2 * low.pad, CV_32S, cv::Scalar(-1));
    cv::Mat depthIn(label.size(), CV_32F, cv::Scalar(0));  // how far inside its frame the owner of a pixel is
    for (size_t p = 0; p < pieces.size(); ++p) {
        cv::Rect at = pieces[p].roi + cv::Point(low.pad, 0);
        cv::Mat own = smasks[p].getMat(cv::ACCESS_READ);
        label(at).setTo(pieces[p].cam, own);
        depthIn(at).setTo(1e6, own);
    }
    // Pixels only the margins cover go to the frame they are deepest in.
    for (size_t p = 0; p < pieces.size(); ++p) {
        cv::Rect at = pieces[p].roi + cv::Point(low.pad, 0);
        cv::Mat better = dists[p] > depthIn(at);
        label(at).setTo(pieces[p].cam, better);
        dists[p].copyTo(depthIn(at), better);
    }
    makePeriodic(label, low.W, low.pad);
    if (!o.debugDir.empty()) cv::imwrite(o.debugDir + "/seams.png", colorLabels(label));
    pieces.clear();
    fimgs.clear();
    smasks.clear();
    imgs.clear();
    masks.clear();
    rep.end();

    rep.begin(Phase::Blend);
    // Full-resolution canvas pixel -> label pixel; the canvas origin is at -pad in both.
    std::vector<int> lx(W + 2 * full.pad), ly(H);
    for (int x = 0; x < (int)lx.size(); ++x)
        lx[x] = std::clamp((int)std::floor((x - full.pad + 0.5) * ls) + low.pad, 0, low.W + 2 * low.pad - 1);
    for (int y = 0; y < H; ++y) ly[y] = std::min(low.H - 1, (int)std::floor((y + 0.5) * ls));

    // Bands reach roughly 2^bands pixels; keep them inside the pad.
    int bands = std::clamp((int)std::floor(std::log2((double)full.pad)) - 2, 1, 7);
    dt::MultiBandBlender blender(false, bands);
    blender.prepare(full.rect());
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
    for (int i = 0; i < n; ++i) {
        cv::Mat own = label == i, img = readImage(s.frames[i].path, s);
        // Dilate so low-resolution seam edges never leave unowned full-resolution pixels.
        cv::dilate(own, own, kernel);
        Camera cam = scaledCamera(s, r, i, 1.0);
        if (!flows.empty()) cam.flow = &flows[i];
        for (const cv::Rect& roi : footprint(cam, full.W, full.H, full.pad)) {
            cv::Mat warped, valid;
            warpPiece(img, cam, full, roi, warped, valid);
            for (int y = 0; y < roi.height; ++y) {
                uchar* m = valid.ptr<uchar>(y);
                const uchar* lo = own.ptr<uchar>(ly[roi.y + y]);
                for (int x = 0; x < roi.width; ++x) m[x] &= lo[lx[roi.x + x + full.pad]];
            }
            if (cv::countNonZero(valid) == 0) continue;
            cv::Vec3d g = r.cameras[i].gain;
            cv::Mat w16;
            warped.convertTo(w16, CV_16SC3);
            cv::multiply(w16, cv::Scalar(g[0], g[1], g[2]), w16);
            blender.feed(w16, valid, roi.tl());
        }
        rep.step((i + 1.0) / (n + 1));
    }
    cv::Mat dst, dstMask;
    blender.blend(dst, dstMask);
    rep.end();

    rep.begin(Phase::Encode);
    dst.colRange(full.pad, full.pad + W).convertTo(out, CV_8UC3);
    cv::Mat covered = dstMask.colRange(full.pad, full.pad + W).clone();
    if (!o.debugDir.empty()) cv::imwrite(o.debugDir + "/coverage.png", covered);
    fillHoles(out, covered);
}

void fillHoles(cv::Mat& img, const cv::Mat& mask) {
    if (cv::countNonZero(mask) == (int)mask.total()) return;
    // The float pyramids below cost ~1 GB at 6144 wide; work only on the band of rows with holes, plus
    // enough context above and below for the coarse levels.
    cv::Mat rowMin;
    cv::reduce(mask, rowMin, 1, cv::REDUCE_MIN);
    int y0 = mask.rows, y1 = 0;
    for (int y = 0; y < mask.rows; ++y)
        if (rowMin.at<uchar>(y) == 0) y0 = std::min(y0, y), y1 = y + 1;
    const int margin = mask.rows / 8;
    y0 = std::max(0, y0 - margin);
    y1 = std::min(mask.rows, y1 + margin);
    if (y0 > 0 || y1 < mask.rows) {
        cv::Mat band = img.rowRange(y0, y1);
        fillHoles(band, mask.rowRange(y0, y1));
        return;
    }
    // The fill is a blur anyway: compute it at quarter resolution (only fully covered cells count as known).
    if (img.cols > 1024) {
        cv::Mat small, smallMask, up;
        cv::Size sz(img.cols / 4, std::max(1, img.rows / 4));
        cv::resize(img, small, sz, 0, 0, cv::INTER_AREA);
        cv::resize(mask, smallMask, sz, 0, 0, cv::INTER_AREA);
        smallMask = smallMask == 255;
        fillHoles(small, smallMask);
        cv::resize(small, up, img.size(), 0, 0, cv::INTER_LINEAR);
        up.copyTo(img, mask == 0);
        return;
    }
    // Push-pull: average the known pixels down a pyramid, then pull coarse colours back into the holes.
    // Horizontal wrap padding keeps the fill continuous across lon +-180.
    const int padx = img.cols / 8;
    cv::Mat a, w;
    cv::copyMakeBorder(img, a, 0, 0, padx, padx, cv::BORDER_WRAP);
    cv::copyMakeBorder(mask, w, 0, 0, padx, padx, cv::BORDER_WRAP);
    a.convertTo(a, CV_32FC3);
    w.convertTo(w, CV_32F, 1.0 / 255);
    cv::Mat w3;
    cv::merge(std::vector<cv::Mat>{w, w, w}, w3);
    a = a.mul(w3);

    std::vector<cv::Mat> A{a}, Wt{w};
    while (A.back().rows > 2 && A.back().cols > 2) {
        cv::Mat a2, w2;
        cv::pyrDown(A.back(), a2);
        cv::pyrDown(Wt.back(), w2);
        A.push_back(a2);
        Wt.push_back(w2);
    }
    auto color = [](const cv::Mat& a, const cv::Mat& w) {
        cv::Mat ww, w3, c;
        cv::max(w, 1e-6, ww);
        cv::merge(std::vector<cv::Mat>{ww, ww, ww}, w3);
        cv::divide(a, w3, c);
        return c;
    };
    cv::Mat F = color(A.back(), Wt.back());
    for (int l = (int)A.size() - 2; l >= 0; --l) {
        cv::Mat up, alpha, a3;
        cv::pyrUp(F, up, A[l].size());
        cv::min(Wt[l] * 4, 1.0, alpha);  // trust a level once it has a quarter of full weight
        cv::merge(std::vector<cv::Mat>{alpha, alpha, alpha}, a3);
        F = color(A[l], Wt[l]).mul(a3) + up.mul(cv::Scalar::all(1) - a3);
    }
    cv::Mat filled;
    F.colRange(padx, padx + img.cols).convertTo(filled, CV_8UC3);
    filled.copyTo(img, mask == 0);
}

}  // namespace spherestitch
