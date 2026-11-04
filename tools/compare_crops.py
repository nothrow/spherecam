"""Side-by-side crops of two equirects (e.g. preview_equirect.jpg vs stitched output) for judging seams."""
import argparse
import sys

import cv2
import numpy as np

# (left, top, width, height) as fractions of the equirect
CROPS = [(0.375, 0.30, 0.25, 0.30), (0.125, 0.30, 0.25, 0.30), (0.625, 0.30, 0.25, 0.30),
         (0.375, 0.12, 0.25, 0.22), (0.375, 0.62, 0.25, 0.25)]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("a", help="left image, e.g. preview_equirect.jpg")
    ap.add_argument("b", help="right image, e.g. stitched output")
    ap.add_argument("-o", "--output", required=True)
    ap.add_argument("--width", type=int, default=2048, help="both are resized to this width first")
    a = ap.parse_args(argv)
    imgs = []
    for path in (a.a, a.b):
        img = cv2.imread(path)
        if img is None:
            sys.exit("cannot read " + path)
        imgs.append(cv2.resize(img, (a.width, a.width // 2), interpolation=cv2.INTER_AREA))
    w, h = a.width, a.width // 2
    rows = []
    for x, y, cw, ch in CROPS:
        r = (slice(int(y * h), int((y + ch) * h)), slice(int(x * w), int((x + cw) * w)))
        sep = np.full((r[0].stop - r[0].start, 6, 3), 255, np.uint8)
        rows.append(np.hstack([imgs[0][r], sep, imgs[1][r]]))
        rows.append(np.full((6, rows[-1].shape[1], 3), 255, np.uint8))
    cv2.imwrite(a.output, np.vstack(rows[:-1]))
    print("wrote", a.output)


if __name__ == "__main__":
    sys.exit(main())
