"""Misalignment of a stitch, measured on matches the stitcher did not see.

usage: ghost_metric.py <session_dir> <debug_dir> [--cache matches.npz] [--focus 7,8,9]

For every overlapping pair, SIFT matches (fundamental-matrix inliers) are placed into the panorama by both
cameras through the stitcher's own model (rotation, centre, depth grid from the debug dir). The angle between
the two placements is the double edge a viewer would see before seams and blending hide part of it.
"""
import argparse, json, os
import cv2
import numpy as np

SCALE = 0.25


def matches(session, m, cache):
    if cache and os.path.exists(cache):
        z = np.load(cache)
        return {tuple(map(int, k.split('_'))): z[k] for k in z.files}
    sift = cv2.SIFT_create(6000, 3, 0.01)
    R = [np.array(f['rotation']).reshape(3, 3) for f in m['frames']]
    feats = []
    for f in m['frames']:
        g = cv2.resize(cv2.imread(os.path.join(session, f['file']), 0), None, fx=SCALE, fy=SCALE,
                       interpolation=cv2.INTER_AREA)
        feats.append(sift.detectAndCompute(g, None))
    out = {}
    for i in range(len(R)):
        for j in range(i + 1, len(R)):
            if R[i][:, 2] @ R[j][:, 2] < np.cos(np.radians(65)) or feats[i][1] is None or feats[j][1] is None:
                continue
            knn = cv2.BFMatcher().knnMatch(feats[i][1], feats[j][1], 2)
            g = [k[0] for k in knn if len(k) == 2 and k[0].distance < 0.75 * k[1].distance]
            if len(g) < 20:
                continue
            a = np.array([feats[i][0][x.queryIdx].pt for x in g]) / SCALE
            b = np.array([feats[j][0][x.trainIdx].pt for x in g]) / SCALE
            F, mask = cv2.findFundamentalMat(a, b, cv2.FM_RANSAC, 1.5 / SCALE, 0.999, 5000)
            if F is None or mask.sum() < 20:
                continue
            k = mask.ravel() > 0
            out[(i, j)] = np.hstack([a[k], b[k]])
    if cache:
        np.savez(cache, **{f'{i}_{j}': v for (i, j), v in out.items()})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('session')
    ap.add_argument('debug')
    ap.add_argument('--cache')
    ap.add_argument('--focus', default='')
    a = ap.parse_args()
    m = json.load(open(os.path.join(a.session, 'manifest.json')))
    r = json.load(open(os.path.join(a.debug, 'report.json')))
    dp = os.path.join(a.debug, 'depth.json')
    D = None
    if os.path.exists(dp):
        fs = cv2.FileStorage(dp, cv2.FILE_STORAGE_READ)
        D = fs.getNode('depth').mat()
    cam = m['camera']
    f, asp, cx, cy = r['focal'], cam['fy'] / cam['fx'], cam['cx'], cam['cy']
    R = [np.array(c['rotation']).reshape(3, 3) for c in r['cameras']]
    C = [np.array(c['centre']) for c in r['cameras']]

    def rays(p, Rm):
        return np.stack([(p[:, 0] - cx) / f, (p[:, 1] - cy) / (f * asp), np.ones(len(p))], 1) @ Rm.T

    def depth(d):
        GH, GW = D.shape
        u = (np.pi - np.arctan2(d[:, 1], d[:, 0])) / (2 * np.pi) * GW - 0.5
        v = (np.pi / 2 - np.arctan2(d[:, 2], np.hypot(d[:, 0], d[:, 1]))) / np.pi * GH - 0.5
        return D[np.clip(np.rint(v).astype(int), 0, GH - 1), np.rint(u).astype(int) % GW]

    fp = os.path.join(a.debug, 'flows.bin')
    FL = np.fromfile(fp, np.float32).reshape(len(R), 128, 256, 2) if os.path.exists(fp) else None

    def unflow(i, d):
        # the stitcher renders camera i at x from x + flow(x); the feature lands where that equals d
        if FL is None:
            return d
        lon, lat = np.arctan2(d[:, 1], d[:, 0]), np.arcsin(np.clip(d[:, 2], -1, 1))
        u = np.rint((np.pi - lon) / (2 * np.pi) * 256 - 0.5).astype(int) % 256
        v = np.clip(np.rint((np.pi / 2 - lat) / np.pi * 128 - 0.5).astype(int), 0, 127)
        lon, lat = lon - FL[i, v, u, 0], lat - FL[i, v, u, 1]
        return np.stack([np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)], 1)

    def place(c, d0):
        d = d0 / np.linalg.norm(d0, axis=1)[:, None]
        if D is None:
            return d
        aq = (d0 * d0).sum(1)
        bq = d0 @ c
        for _ in range(6):
            t = depth(d)
            s = (-bq + np.sqrt(np.maximum(bq * bq - aq * (c @ c - t * t), 0))) / aq
            p = c + s[:, None] * d0
            d = p / np.linalg.norm(p, axis=1)[:, None]
        return d

    focus = {int(x) for x in a.focus.split(',') if x}
    allg, foc, rows = [], [], []
    for (i, j), ab in sorted(matches(a.session, m, a.cache).items()):
        d1, d2 = unflow(i, place(C[i], rays(ab[:, :2], R[i]))), unflow(j, place(C[j], rays(ab[:, 2:], R[j])))
        g = np.degrees(np.arccos(np.clip((d1 * d2).sum(1), -1, 1)))
        allg.append(g)
        if i in focus or j in focus:
            foc.append(g)
        rows.append((np.median(g), i, j, len(g)))
    q = lambda v: 'median %.2f  p75 %.2f  p90 %.2f deg (n=%d)' % (*np.percentile(v, [50, 75, 90]), len(v))
    for med, i, j, n in sorted(rows, reverse=True)[:12]:
        print(f'  worst pair {i}-{j}: median {med:.2f} deg ({n} matches)')
    print('pair medians:', 'median %.2f  p90 %.2f deg (%d pairs)' % (
        np.median([x[0] for x in rows]), np.percentile([x[0] for x in rows], 90), len(rows)))
    print('all matches:', q(np.concatenate(allg)))
    if foc:
        print('focus      :', q(np.concatenate(foc)))


if __name__ == '__main__':
    main()
