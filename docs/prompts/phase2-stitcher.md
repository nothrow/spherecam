# Phase 2 prompt: desktop stitcher (`/stitcher`)

You are continuing the project in `/home/yossarian/git/nothrow/spherecam`. Read `CLAUDE.md` (working rules,
architecture, "Decisions for later phases") and `docs/manifest.md` before anything else. Talk to the user in
Czech; code, comments and commits in English; comments only for non-obvious things. Never invent APIs or
versions: verify against OpenCV docs/source. Propose any deviation from CLAUDE.md and wait for approval.

## Goal
A pure C++17 library `/stitcher/core` + CLI `/stitcher/cli` + tests `/stitcher/tests` that turns one capture
session folder (images + `manifest.json`, schemaVersion 1) into a full 2:1 equirectangular JPEG, developed and
verified on desktop (WSL2, Linux). No Android code in this phase.

## State of the repo (phase 1 done)
- Capture app in `android/` writes sessions; contract is `docs/manifest.md` (rotation = R_world_from_camera,
  OpenCV camera frame, world Z-up, X = first-shot heading; intrinsics refer to the saved upright pixels).
- `tools/preview_from_manifest.py` renders a naive sensor-only equirect; its equirect mapping
  (lon = π − (u+0.5)/W·2π, lat = π/2 − (v+0.5)/H·π, centre column = yaw 0, no mirroring) is the reference the
  stitcher output must match exactly, so results are comparable pixel-for-pixel.
- `tools/test_preview.py` shows how to synthesise a session from a known equirect.
- Real test data: `testdata/emulator/nadir-1`, `nadir-2` (21 frames each, 960×1280, ~88°×104° FOV, emulator
  VirtualScene, rotations ±2° drift, `rotationSource` trigger-time, AE/AWB not locked). No phone sessions yet.

## Decisions to verify first (report, then proceed)
1. OpenCV version to pin. It must be the same on desktop and (later) the custom minimal Android build
   (modules core, imgproc, imgcodecs, features2d, flann, calib3d, stitching; 16 KB page size). Check the latest
   stable release and whether the `stitching` module and `cv::detail` APIs used below exist there unchanged
   (OpenCV 5 reorganised modules; the Python preview tool already uses opencv-python-headless 5.0). Build it
   from source on desktop with exactly those modules (static), via CMake FetchContent or a script in `tools/`.
2. Which `cv::detail` conventions apply to `CameraParams::R` (direction of the rotation, axis handedness) and
   how they map to our R_world_from_camera. Prove it with a test, not by reasoning.

## Pipeline (per CLAUDE.md, adjust only with approval)
1. Load manifest + images; K and R from the manifest; validate (orthonormal R, det +1, sizes match).
2. Registration at reduced resolution (~0.6 MP per image): features (prefer SIFT, it is in features2d since
   4.4), match **only neighbouring pairs** chosen from sensor rotations (optical axes closer than ~FOV, i.e.
   expected overlap), with a homography/rotation consistency check against the sensor-predicted relative
   rotation to reject bad matches.
3. Bundle adjustment: `BundleAdjusterRay` initialised with sensor rotations (and focal from the manifest;
   decide whether to refine focal - probably only one shared focal). Afterwards re-align the result to the
   sensor frame (best-fit global rotation between BA and sensor rotations) so the horizon stays level and
   yaw 0 stays the first shot's heading - BA alone has a free global rotation.
4. Fallback: if BA fails or diverges (define measurable criteria: residual, per-camera deviation from sensor
   rotation beyond a threshold, disconnected match graph), use pure sensor rotations - per camera if possible,
   globally otherwise. Record what was used in a JSON report next to the output.
5. Warp each image directly into the reference equirect mapping (own remap, not `SphericalWarper`, unless
   you show SphericalWarper produces the identical mapping). Handle the 360° wrap: images crossing lon ±180°
   must not be cut or blended wrongly (e.g. work on a canvas rolled so the wrap seam lies in a well-covered
   area, then roll back, or split such images).
6. Exposure compensation (`BlocksGainCompensator` or similar; AE may not be locked), seam finder
   (`GraphCutSeamFinder` at low resolution), `MultiBandBlender` at full resolution.
7. Output 6144×3072 JPEG. If the nadir was not captured (`settings.nadir == false`), fill the bottom hole by
   blurred stretching of edge pixels; output is always full 2:1. Also fill any other uncovered pixels the same
   way (emulator FOV is wide, but phone sessions may have small gaps).
8. GPano XMP is not part of this phase (Android writes it later), but keep output metadata-free and EXIF
   orientation 1.

## Constraints
- Target device is a Samsung A16 (low/mid range, limited RAM). Measure peak RSS and wall time on desktop
  for a 36-frame 1536×2048 synthetic session and keep peak memory realistic for the phone (aim < 1 GB; say
  what you measured). Prefer streaming per image over holding all full-res warped images if needed.
- Progress callback with phases (load, features, matching, BA, warp, exposure, seams, blend, encode) and
  cooperative cancellation - the Android progress screen will use them.
- No Android or JNI dependencies in `core`. Public API: one function/class taking a session dir (or loaded
  manifest + image paths), output path, options, progress callback.

## Tests (must run with one command, e.g. `ctest`)
- Convention test: synthetic session rendered from a known equirect with exact rotations → stitched output
  matches the source (mean abs error threshold), no mirroring, correct yaw 0 column and zenith/nadir.
- Robustness: same synthetic session with rotation noise (1–3°, plus a slow yaw drift like the emulator's) →
  BA recovers rotations to < 0.3° of ground truth; output error close to the noise-free case.
- Fallback: a session with a textureless image (e.g. uniform colour) or broken matches → stitch still
  completes using sensor rotations, report says so.
- No-nadir session → full 2:1 output, bottom filled.
- Real data: run on `testdata/emulator/nadir-*`; output exists, and seam misalignment is visibly smaller than
  in `preview_equirect.jpg` (produce side-by-side crops for the user to judge).
- Python tooling (synthetic generation, comparisons) may be reused from `tools/`; keep C++ tests self-contained
  where reasonable.

## CLI
`spherestitch <session_dir> [-o out.jpg] [--width 6144] [--debug-dir DIR]` - debug dir gets match graphs,
per-camera BA vs sensor deviations, seams, and the JSON report.

## Out of scope
Android `:stitcher` JNI module, viewer, MediaStore, GPano XMP, Play release.

## Open items left from phase 1 (not your task, do not fix silently; mention if they block you)
- Not yet verified on a real phone (A16 target, S23 available): REALTIME timestamps and exposure-time
  interpolation, AE/AWB/AF lock holding on the still capture, real FOV vs `intrinsicsFromSensor`, actual
  ImageCapture resolution/crop.
- No-nadir capture never run end-to-end (the emulator script had a nadir-switch bug), app-kill survival and
  ZIP export not tested on a device.
- `tools/emu_capture.py` was interrupted mid-edit; check it runs before relying on it.

## Working style
Plan first and show the plan to the user (in Czech) before writing code. Work in small, verifiable steps,
commit only when asked. Use cheaper subagents for well-specified, isolated tasks if available.
