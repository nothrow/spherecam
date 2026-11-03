import contextlib
import io
import json
import math
import os
import tempfile

import cv2
import numpy as np

import preview_from_manifest as pfm

W, H = 2048, 1024
IW, IH = 1536, 2048


def make_source():
    lon = math.pi - (np.arange(W) + 0.5) / W * 2 * math.pi
    lat = math.pi / 2 - (np.arange(H) + 0.5) / H * math.pi
    lon, lat = np.meshgrid(lon, lat)
    d = np.stack([np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)], -1)
    bits = (d > 0).astype(int)
    pal = np.array([[40 + 190 * ((k >> 2) & 1), 40 + 190 * ((k >> 1) & 1), 40 + 190 * (k & 1)]
                    for k in range(8)], np.uint8)
    img = pal[bits[..., 0] * 4 + bits[..., 1] * 2 + bits[..., 2]]
    for deg in range(-180, 180, 30):
        img[:, int((math.pi - math.radians(deg)) / (2 * math.pi) * W) % W] = 255
    for deg in (-60, -30, 0, 30, 60):
        img[int((90 - deg) / 180 * H)] = 255
    return img, pal


def rot(yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    z = np.array([math.cos(p) * math.cos(y), math.cos(p) * math.sin(y), math.sin(p)])
    x = np.array([math.sin(y), -math.cos(y), 0])
    return np.stack([x, np.cross(z, x), z], 1)


def quat(R):
    rv, _ = cv2.Rodrigues(R)
    a = np.linalg.norm(rv)
    ax = (rv / a).ravel() if a > 1e-9 else np.array([1.0, 0, 0])
    return [math.cos(a / 2)] + list(math.sin(a / 2) * ax)


def make_session(path, src, mirror_last=False):
    f = IW / 2 / math.tan(math.radians(25))
    cx, cy = IW / 2, IH / 2
    shots = ([(i * 360 / 11, 0) for i in range(11)]
             + [(i * 360 / 11, s * 45) for s in (1, -1) for i in range(11)]
             + [(0, 90), (0, -90), (180, -90)])
    uu, vv = np.meshgrid(np.arange(IW), np.arange(IH))
    c = np.stack([(uu - cx) / f, (vv - cy) / f, np.ones_like(uu, float)], -1)
    frames = []
    for i, (yaw, pitch) in enumerate(shots):
        R = rot(yaw, pitch)
        mu, mv = pfm.dir_to_pixel(c @ R.T, W, H)
        img = cv2.remap(src, mu.astype(np.float32), mv.astype(np.float32), cv2.INTER_LINEAR,
                        borderMode=cv2.BORDER_WRAP)
        name = "img_%03d.jpg" % i
        cv2.imwrite(os.path.join(path, name), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
        q = quat(R)
        if mirror_last and i == len(shots) - 1:
            R = R * np.array([-1, 1, 1])
        frames.append({"index": i, "file": name, "rotation": R.ravel().tolist(), "quaternion": q})
    cam = {"imageWidth": IW, "imageHeight": IH, "fx": f, "fy": f, "cx": cx, "cy": cy}
    with open(os.path.join(path, "manifest.json"), "w") as fh:
        json.dump({"camera": cam, "frames": frames}, fh)


def run(path, *args):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        pfm.main([path, "--no-grid", *args])
    return cv2.imread(os.path.join(path, "preview_equirect.jpg")), buf.getvalue()


def col(u_deg_yaw):
    return int((math.pi - math.radians(u_deg_yaw)) / (2 * math.pi) * W)


def row(pitch):
    return int((90 - pitch) / 180 * H)


src, pal = make_source()
with tempfile.TemporaryDirectory() as t:
    make_session(t, src)
    for mode in ("nearest", "average"):
        out, log = run(t, "--mode", mode)
        assert "WARNING" not in log, log
        cov = out.sum(-1) > 0
        assert cov.mean() > 0.95, cov.mean()
        err = np.abs(out.astype(int) - src.astype(int))[cov].mean()
        print(mode, "coverage %.3f mean abs err %.2f" % (cov.mean(), err))
        assert err < 3, err

    out, _ = run(t)
    u = col(70)
    assert u < W // 2, "yaw +70 must be left of centre"
    exp = pal[7]
    assert np.abs(out[row(20), u].astype(int) - exp.astype(int)).max() < 30, out[row(20), u]
    mirrored = out[row(20), W - u]
    assert np.abs(mirrored.astype(int) - exp.astype(int)).max() > 60, "mirrored side must differ"
    assert np.abs(out[30].astype(int) - src[30].astype(int)).mean() < 15
    assert np.abs(out[-31].astype(int) - src[-31].astype(int)).mean() < 15

with tempfile.TemporaryDirectory() as t:
    make_session(t, src, mirror_last=True)
    _, log = run(t)
    assert "WARNING frame 35" in log and "det" in log, log

print("OK")
