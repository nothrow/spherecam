# Project: 360° Photo Sphere for Android

App for capturing spherical 360° panoramas on a phone: gyroscope-guided capture,
on-device stitching, viewer with look-around. UX reference: the Travvir app.
Target: Google Play (personal account → internal/closed testing first).

## Working rules
- Talk to me in Czech. Code, comments and commit messages in English.
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
- OpenCV: same pinned version on desktop and Android. For Play, a custom minimal build
  (modules core, imgproc, imgcodecs, features2d, flann, calib3d, stitching) with
  16 KB page size support.

## UI flow (overall)
1. Home: "New panorama", "Capture ground" toggle (nadir, default on, remembers last choice),
   "Open 360 photo", list of sessions (thumbnail, date, state).
2. Capture: camera preview, 3D targets, aiming ring, arrow, counter (e.g. 12/31),
   "Undo last shot", "Cancel", "Preview already captured images".
3. Stitching: progress by phase (later).
4. Viewer (later).
5. Session detail (long press): "Stitch again", "Delete source images", "Delete all",
   "Export session (ZIP)".

---

# CURRENT PHASE: capture app + manifest

## Scope
Modules `:app` and `:capture`, manifest specification and a verification script.
Nothing from stitching or the viewer.

## Guidance
- Orientation: `Sensor.TYPE_GAME_ROTATION_VECTOR` (gyro + accelerometer, NO magnetometer),
  ~50 Hz. Ring buffer of samples with timestamps.
- World: gravity is known, yaw is arbitrary and fixed by the first shot
  (yaw of first shot = 0).
- Target layout is computed at runtime from the real camera FOV
  (`CameraCharacteristics`: `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, `SENSOR_INFO_PHYSICAL_SIZE`,
  account for crop of the saved image) with ~30 % target overlap. Phone in portrait.
  Roughly: equator ~12, ±45° rows ~8 each, zenith 1, nadir 2 (yaw 0° and 180° so feet
  are in a different place each time). Compute counts and row pitches, do not hardcode.
- Nadir optional per toggle. Zenith mandatory.
- Overlay: targets are projected into the preview using current orientation and the
  **preview** intrinsics. Mind the `PreviewView` crop (FILL_CENTER) and its different FOV
  vs the saved image. Aiming ring in the centre, arrow to the nearest uncaptured target.
- Auto-capture: angular error below ~2°, phone steady (angular velocity below threshold)
  for ~300 ms, roll within tolerance. Thresholds configurable. After capture: vibrate,
  target turns green.
- User hint: rotate the phone around the lens, not around your body (parallax).
- "Undo last shot" deletes the last image and returns its target to uncaptured.

## Camera
- CameraX `ImageCapture`, ~2–3 MP (6144 px output width does not need more).
- After the first shot lock AE, AWB and focus (Camera2Interop) to avoid banding.
  Verify the lock actually holds on the A16.
- Rotation per image by **exposure timestamp**, not by trigger time (difference can be
  100–300 ms). Check `SENSOR_INFO_TIMESTAMP_SOURCE` (REALTIME vs UNKNOWN) and align with
  `SensorEvent` timestamps accordingly. Interpolate rotation (slerp) between samples.
  If timestamps cannot be aligned, use a fallback and record it in the manifest.

## Session storage
- `filesDir/sessions/<uuid>/`: `img_000.jpg` …, `manifest.json`.
- Save JPEGs with pixels physically in correct orientation and EXIF orientation = 1.
  Intrinsics in the manifest refer exactly to the saved pixels.
- Write the manifest incrementally after each shot, atomically (tmp + rename), so an app
  crash does not lose the session. Session state: `capturing` | `captured`
  (later `stitched`, `failed`).
- Export session as ZIP via the system share sheet (FileProvider, no permission),
  to get it onto a PC.

## Manifest (draft, final spec in docs/manifest.md)
Conventions:
- Camera: OpenCV convention (x right, y down, z forward).
- World: Z up (against gravity), X = horizontal view direction of the first shot,
  right-handed.
- `rotation` = R_world_from_camera, 3×3 row-major. Plus quaternion (w, x, y, z).
- Device frame → camera frame conversion (incl. `SENSOR_ORIENTATION` and phone rotation)
  happens in capture, not in the stitcher.

```json
{
  "schemaVersion": 1,
  "sessionId": "uuid",
  "createdAt": "ISO-8601",
  "status": "captured",
  "app": { "versionName": "...", "versionCode": 1 },
  "device": { "manufacturer": "...", "model": "...", "sdkInt": 34 },
  "settings": { "nadir": true },
  "conventions": {
    "camera": "opencv",
    "world": "z-up, x=first-shot-heading",
    "rotation": "R_world_from_camera, row-major"
  },
  "camera": {
    "cameraId": "0",
    "imageWidth": 1536, "imageHeight": 2048,
    "fx": 0, "fy": 0, "cx": 0, "cy": 0,
    "sensorOrientation": 90,
    "focalLengthMm": 0, "sensorPhysicalSizeMm": [0, 0],
    "timestampSource": "REALTIME",
    "aeLocked": true, "awbLocked": true, "afLocked": true
  },
  "frames": [
    {
      "index": 0,
      "file": "img_000.jpg",
      "targetId": "r0_c0",
      "targetYawDeg": 0, "targetPitchDeg": 0,
      "rotation": [1,0,0, 0,1,0, 0,0,1],
      "quaternion": [1,0,0,0],
      "exposureTimestampNs": 0,
      "rotationSource": "interpolated",
      "angularErrorDeg": 0.8
    }
  ]
}
```

## Verification script: tools/preview_from_manifest.py
Python (numpy + opencv-python). From a session folder, renders a naive 2048×1024 equirect
**from sensor rotations and intrinsics only** (no feature matching; for overlaps simple
averaging or nearest image is fine). Purpose: validate conventions. If rotations and
intrinsics are correct, the panorama is roughly consistent (level horizon, no mirroring,
no swapped axes). This is the main acceptance test of this phase.

## Phase acceptance
- App installs on the A16 and requests only CAMERA.
- Guides the user through all targets (with and without nadir); auto-capture is reliable.
- A session survives the app being killed mid-capture.
- A session can be exported as ZIP.
- `preview_from_manifest.py` produces a recognisable, correctly oriented panorama from
  a real A16 session.
- `docs/manifest.md` documents the schema and conventions.

## Out of scope for this phase
Stitching, viewer, GPano XMP, MediaStore, Play release, compass, icon and branding.