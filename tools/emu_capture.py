"""Captures a full session on the Android emulator (VirtualScene back camera) without a human, then renders
the sensor-only preview. Runs in WSL; talks to the emulator via Windows adb.exe and emu_pose.py.

One-time setup on Windows:
    py -3 -m venv D:\\_android\\grpcctl\\.venv
    D:\\_android\\grpcctl\\.venv\\Scripts\\pip install grpcio grpcio-tools
Usage:
    tools/.venv/bin/python tools/emu_capture.py [--no-nadir] [--out DIR]

The emulator's fused orientation drifts (~2 deg over a session, always the same direction), so poses are
closed-loop corrected from the app's `SphereAim` log line (target rotation in the live camera frame).
Default FOV matches the emulator camera (focal 1 mm, sensor 2.56x1.92 mm, portrait); it must match what
the app computes, otherwise the target list differs.
"""
import argparse
import math
import re
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("--no-nadir", action="store_true")
ap.add_argument("--hfov", type=float, default=2 * math.degrees(math.atan(0.96)))
ap.add_argument("--vfov", type=float, default=2 * math.degrees(math.atan(1.28)))
ap.add_argument("--overlap", type=float, default=0.30)
ap.add_argument("--adb", default="/mnt/d/_android/platform-tools/adb.exe")
ap.add_argument("--winpy", default="/mnt/d/_android/grpcctl/.venv/Scripts/python.exe")
ap.add_argument("--serial", default=None, help="adb serial; default: the only emulator")
ap.add_argument("--out", default="emu_sessions")
args = ap.parse_args()

PKG = "cz.nothrow.spherecam"
POSE = subprocess.run(["wslpath", "-w", str(Path(__file__).with_name("emu_pose.py"))], capture_output=True, text=True).stdout.strip()
D = np.diag([1, -1, -1])  # R_device_from_camera == R_camera_from_device
GW_FROM_EW = np.array([[0, 0, -1], [-1, 0, 0], [0, 1, 0]])  # gw X = camera heading at emulator rotation 0


def adb(*a, **kw):
    serial = ["-s", args.serial] if args.serial else []
    return subprocess.run([args.adb, *serial, *a], capture_output=True, text=kw.get("text", True)).stdout


def targets():
    """Mirrors geo.layoutTargets: (id, yaw, pitch) in capture order."""
    hf, vf, ov = args.hfov, args.vfov, args.overlap
    gaps = math.ceil(90 / (vf * (1 - ov)))

    def row(prefix, p):
        c = max(3, math.ceil(360 * math.cos(math.radians(max(0, abs(p) - vf / 2))) / (hf * (1 - ov))))
        return [(f"{prefix}_c{i}", 360 * i / c, p) for i in range(c)]
    ps = [90 * k / gaps for k in range(1, gaps)]
    t = row("e", 0) + [x for k, p in enumerate(ps, 1) for x in row(f"u{k}", p)] + [("zenith", 0, 90)]
    t += [x for k, p in enumerate(ps, 1) for x in row(f"d{k}", -p)]
    return t + ([] if args.no_nadir else [("nadir_0", 0, -90), ("nadir_180", 180, -90)])


def ideal(yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    z = np.array([math.cos(p) * math.cos(y), math.cos(p) * math.sin(y), math.sin(p)])
    x = np.array([math.sin(y), -math.cos(y), 0])
    return np.column_stack([x, np.cross(z, x), z])


def euler_xyz(m):
    b = math.asin(max(-1, min(1, m[0, 2])))
    if abs(math.cos(b)) > 1e-6:
        a, c = math.atan2(-m[1, 2], m[2, 2]), math.atan2(-m[0, 1], m[0, 0])
    else:
        a, c = 0.0, math.atan2(m[1, 0], m[1, 1])
    return [math.degrees(v) for v in (a, b, c)]


def set_pose(m):
    subprocess.run([args.winpy, POSE, "set", *map(str, euler_xyz(m))], capture_output=True, check=True)


def quat_mat(w, x, y, z):
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
                     [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
                     [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)]])


def aim():
    adb("logcat", "-c")
    time.sleep(0.6)
    lines = adb("logcat", "-d", "-s", "SphereAim:V").strip().splitlines()
    return lines[-1].split()[-5:] if lines else None


def latest_session():
    return adb("shell", f"run-as {PKG} ls -t files/sessions").split()[0]


def frame_count(sid):
    return int(adb("shell", f"run-as {PKG} sh -c 'ls files/sessions/{sid} | grep -c img_'").strip() or 0)


def tap_text(text, xml=None):
    xml = xml or ui()
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml)
    if not m:
        sys.exit(f"'{text}' not on screen")
    x1, y1, x2, y2 = map(int, m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


set_pose(GW_FROM_EW.T @ ideal(0, 0) @ D)
adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(3)
xml = ui()
# The label is not clickable; the switch is the only checkable node on Home.
sw = re.search(r'checkable="true" checked="(true|false)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if (sw.group(1) == "true") == args.no_nadir:
    x1, y1, x2, y2 = map(int, sw.groups()[1:])
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.5)
tap_text("New panorama")
time.sleep(5)
sid = latest_session()
print("session", sid)

for tid, yaw, pitch in targets():
    cmd = GW_FROM_EW.T @ ideal(yaw, pitch) @ D
    n0 = frame_count(sid)
    for attempt in range(6):
        set_pose(cmd)
        time.sleep(1.5)
        if frame_count(sid) > n0:
            print(f"{tid:10} ok ({attempt} corrections)", flush=True)
            break
        a = aim()
        # The app aims at the nearest uncaptured target; correcting toward a different one would capture it instead.
        if a is None or a[0] != tid:
            break
        cmd = cmd @ D @ quat_mat(*map(float, a[1:])) @ D
    else:
        print(f"{tid:10} FAILED", flush=True)

time.sleep(2)
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
tar = subprocess.run([args.adb, *(["-s", args.serial] if args.serial else []), "exec-out",
                      "run-as", PKG, "tar", "c", "-C", "files/sessions", sid], capture_output=True).stdout
subprocess.run(["tar", "x", "-C", str(out)], input=tar, check=True)
subprocess.run([sys.executable, "-I", str(Path(__file__).with_name("preview_from_manifest.py")), str(out / sid)], check=True)
