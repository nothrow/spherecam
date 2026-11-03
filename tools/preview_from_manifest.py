import argparse
import json
import math
import os
import sys

import cv2
import numpy as np


def pixel_to_dir(w, h):
    # Centre column is yaw 0; moving right in the panorama turns right (yaw decreases).
    lon = math.pi - (np.arange(w) + 0.5) / w * 2 * math.pi
    lat = math.pi / 2 - (np.arange(h) + 0.5) / h * math.pi
    lon, lat = np.meshgrid(lon, lat)
    return np.stack([np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)], -1)


def dir_to_pixel(d, w, h):
    lon = np.arctan2(d[..., 1], d[..., 0])
    lat = np.arctan2(d[..., 2], np.hypot(d[..., 0], d[..., 1]))
    return (math.pi - lon) / (2 * math.pi) * w - 0.5, (math.pi / 2 - lat) / math.pi * h - 0.5


def quat_to_matrix(q):
    w, x, y, z = np.asarray(q, float) / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def check_rotation(i, R, q):
    if not (np.allclose(R @ R.T, np.eye(3), atol=1e-3) and abs(np.linalg.det(R) - 1) < 1e-3):
        print("WARNING frame %d: rotation is not orthonormal with det +1 (det=%.3f)" % (i, np.linalg.det(R)))
        return
    # q and -q are the same rotation; the trace form is sign-agnostic
    cos = (np.trace(R.T @ quat_to_matrix(q)) - 1) / 2
    deg = math.degrees(math.acos(np.clip(cos, -1, 1)))
    if deg > 0.5:
        print("WARNING frame %d: rotation and quaternion differ by %.2f deg" % (i, deg))


def render(session, w, mode, grid):
    with open(os.path.join(session, "manifest.json")) as f:
        m = json.load(f)
    cam = m["camera"]
    iw, ih = cam["imageWidth"], cam["imageHeight"]
    h = w // 2
    d = pixel_to_dir(w, h)
    acc = np.zeros((h, w, 3), np.float32)
    cnt = np.zeros((h, w), np.float32)
    best = np.full((h, w), -1.0, np.float32)
    for fr in m["frames"]:
        R = np.array(fr["rotation"], float).reshape(3, 3)
        check_rotation(fr["index"], R, fr["quaternion"])
        img = cv2.imread(os.path.join(session, fr["file"]))
        if img is None:
            print("WARNING frame %d: cannot read %s" % (fr["index"], fr["file"]))
            continue
        c = d @ R
        z = c[..., 2]
        with np.errstate(divide="ignore", invalid="ignore"):
            u = cam["fx"] * c[..., 0] / z + cam["cx"]
            v = cam["fy"] * c[..., 1] / z + cam["cy"]
        valid = (z > 0) & (u >= 0) & (u <= iw - 1) & (v >= 0) & (v <= ih - 1)
        u = np.where(valid, u, -1).astype(np.float32)
        v = np.where(valid, v, -1).astype(np.float32)
        px = cv2.remap(img, u, v, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT).astype(np.float32)
        if mode == "average":
            acc[valid] += px[valid]
            cnt[valid] += 1
        else:
            sel = valid & (z > best)
            acc[sel] = px[sel]
            best[sel] = z[sel]
    if mode == "average":
        out = acc / np.maximum(cnt, 1)[..., None]
    else:
        out = acc
    out = out.astype(np.uint8)
    if grid:
        cv2.line(out, (0, h // 2), (w - 1, h // 2), (0, 255, 255), 1)
        cv2.line(out, (w // 2, 0), (w // 2, h - 1), (255, 0, 255), 1)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("session")
    ap.add_argument("-o", "--output")
    ap.add_argument("--width", type=int, default=2048)
    ap.add_argument("--mode", choices=["nearest", "average"], default="nearest")
    ap.add_argument("--no-grid", action="store_true")
    a = ap.parse_args(argv)
    out = render(a.session, a.width, a.mode, not a.no_grid)
    path = a.output or os.path.join(a.session, "preview_equirect.jpg")
    cv2.imwrite(path, out)
    print("wrote", path)


if __name__ == "__main__":
    sys.exit(main())
