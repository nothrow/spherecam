# Project: 360° Photo Sphere for Android

App for capturing spherical 360° panoramas on a phone: gyroscope-guided capture,
on-device stitching, viewer with look-around. UX reference: the Travvir app.
Target: Google Play (personal account → internal/closed testing first).

## Working rules
- Talk to me in Czech. Code, comments and commit messages in English.
- Comments and commit messages only for non-obvious things (why, constraints, device quirks,
  conventions). Never write a comment that describes what the code does; the code says that.
- Be direct and concise. Never invent APIs. If unsure whether an API exists or which
  library version to use, verify it (docs, source) or say so.
- Propose deviations from the decisions below and ask first; do not implement them silently.
- Development happens in WSL2; the phone is connected via `adb` wireless debugging.
- Use the Android CLI (`android`) for SDK, templates and device management, not sdkmanager.
  Before camera work, check available Android skills (`android skills`), e.g. CameraX.

## Target device and platform
- Minimum: Samsung Galaxy A16 5G (has a gyroscope). Low/mid-range performance, limited RAM.
- Kotlin, Jetpack Compose (camera and GL via `AndroidView`), CameraX.
- minSdk 29, targetSdk per current Google Play requirement (verify).
- arm64-v8a ABI only (relevant once native code is added).

## Permissions: minimum
- Only runtime permission: `CAMERA`.
- `VIBRATE` (normal, no dialog).
- No location, no storage (MediaStore needs none from API 29), no notifications,
  no foreground service.

## Architecture (overall plan)
```
/android        Gradle project
  :app          UI, navigation (Compose)
  :capture      guidance, sensors, camera, session writing
  :stitcher     (later) thin JNI layer over /stitcher/core
  :viewer       (later) OpenGL ES equirect viewer
/stitcher       (later) pure C++17 library on OpenCV, no Android dependency
  core/ cli/ tests/
/tools          helper scripts (Python)
/docs           incl. docs/manifest.md: manifest specification
```
Key principle: **every capture is a session folder with images + `manifest.json`**.
The manifest is the only contract between capture and stitcher. The stitcher will be
developed and tested on desktop against sessions captured on the phone. Therefore all
Android specifics are resolved in capture before the manifest is written.

## Decisions for later phases (context only, do not implement now)
- On-device stitching: OpenCV detailed pipeline, sensor rotations as initial estimate,
  matching only between neighbours, BundleAdjusterRay, fallback to pure sensor rotations,
  spherical warp to equirect, exposure compensation, seam finder, multiband blend.
- Output 6144×3072 JPEG with GPano XMP, saved to Photos via MediaStore.
- With nadir disabled, the bottom hole is filled by blurred stretching of edge pixels;
  output is always full-size 2:1.
- Stitching runs in the foreground with a progress screen (screen kept on). If interrupted,
  the session can be stitched later.
- Viewer: OpenGL ES 3, fullscreen quad with ray-casting shader, gyro + drag + pinch.
  Also opens third-party 360 photos (Photo Picker, `ACTION_VIEW`), honours GPano `CroppedArea*`.
- No compass for now (maybe later only as `PoseHeadingDegrees` in metadata).
- OpenCV 5.0.0: same pinned version on desktop and Android (`tools/build_opencv.sh`). For Play,
  a custom minimal static build (modules core, imgproc, imgcodecs, features, flann, geometry,
  stitching; OpenCV 5 renamed features2d → features and calib3d's geometry part → geometry)
  with 16 KB page size support.

## UI flow (overall)
1. Home: "New panorama", "Capture ground" toggle (nadir, default off, remembers last choice),
   "Open 360 photo", list of sessions (thumbnail, date, state).
2. Capture: camera preview, 3D targets, aiming ring, arrow, counter (e.g. 12/31),
   "Undo last shot", "Cancel", "Preview already captured images".
3. Stitching: progress by phase (later).
4. Viewer (later).
5. Session detail (long press): "Stitch again", "Delete source images", "Delete all",
   "Export session (ZIP)".

---

# CURRENT PHASE: desktop stitcher

Full brief: `docs/prompts/phase2-stitcher.md` (read it before starting). Summary:
- Pure C++17 `/stitcher/core` + `/stitcher/cli` + `/stitcher/tests` on OpenCV (pinned version, same as the
  later Android build), developed and tested on desktop against session folders; no Android/JNI yet.
- Input contract: `docs/manifest.md`. Output equirect mapping must match `tools/preview_from_manifest.py`.
- Test data: synthetic sessions (see `tools/test_preview.py`) and `testdata/emulator/nadir-*`.
- Emulator sessions can be re-captured without a human: `tools/emu_capture.py` (Windows `adb.exe` +
  emulator gRPC via `tools/emu_pose.py`).
- Phase 1 brief (capture app): `docs/phases/phase1-capture.md`. Its device-only checks (A16/S23) are still
  open and listed in the phase 2 brief.

## Out of scope for this phase
Viewer, compass, icon and branding. Done beyond the original scope: `:stitcher` JNI module, MediaStore
save with GPano XMP, sharing and the Play release of version 2.0.
