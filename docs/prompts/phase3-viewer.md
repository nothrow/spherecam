# Phase 3 prompt: 360° viewer (`:viewer`)

You are continuing the project in `/home/yossarian/git/nothrow/spherecam`. Read `CLAUDE.md` (working rules,
architecture, "Decisions for later phases") first. Talk to the user in Czech; code, comments and commits in
English; comments only for non-obvious things. Never invent APIs or versions: verify against Android docs/source.
Propose any deviation from CLAUDE.md and wait for approval.

## Goal
An in-app viewer for equirectangular 360° photos: our own stitched panoramas and third-party ones. Plus the
device checks left open since phase 1, run together with the viewer test on the A16.

## State of the repo (phases 1, 2 and release 2.0 done)
- `:app` (Compose, hand-rolled navigation via `sealed interface Screen` in `MainActivity.kt`, restored after
  process death by `ScreenSaver`), `:capture`, `:stitcher` (JNI over `/stitcher/core`, OpenCV 5.0.0).
- The stitched panorama is `SessionStore.PANO` in the session dir: 6144×3072 JPEG with GPano XMP (full sphere,
  `CroppedArea*` = full size). It is also copied to Photos via MediaStore and can be shared from Detail.
- Activity is locked to portrait (`android:screenOrientation="portrait"`).
- Equirect convention (reference: `tools/preview_from_manifest.py`): lon = π − (u+0.5)/W·2π,
  lat = π/2 − (v+0.5)/H·π, centre column = yaw 0, no mirroring. World Z-up.
- Permissions stay `CAMERA` + `VIBRATE` only.

## Decisions to verify first (report, then proceed)
1. `GL_MAX_TEXTURE_SIZE` on the A16 (and the emulator). If 6144 wide does not fit, how to downsample or split
   the texture. Memory budget: 6144×3072 RGBA8 ≈ 75 MB; third-party panoramas can be much larger (e.g. 16k),
   so decode with `inSampleSize`/`ImageDecoder` target size, never at full resolution if it does not fit.
2. Reading XMP: does `androidx.exifinterface` expose the XMP packet (`TAG_XMP`) for JPEG reliably, also for
   extended XMP? If not, a small APP1 parser. Pick the smaller option that works; do not add a library for it.

## Viewer (per CLAUDE.md, adjust only with approval)
Decided with the user: the viewer is a separate `ViewerActivity` (in `:viewer`, or `:app` if the module
cannot own it cleanly) that allows landscape; the rest of the app stays portrait. All entry points (Detail,
Home picker, `ACTION_VIEW`) start this activity with the image URI.

1. New module `:viewer` (Android library), `GLSurfaceView` with GLES 3.0 inside `AndroidView`. Rendering:
   fullscreen quad, fragment shader turns the pixel into a view ray (inverse projection × view rotation),
   ray → lon/lat → texture coordinates. Same equirect convention as above; the lon wrap must be seamless
   (no visible line at u = 0/1: handle the derivative discontinuity, e.g. explicit `textureGrad` or
   `GL_REPEAT` with a computed gradient). Mipmaps for zoomed-out views.
2. Controls:
   - Gyro: `TYPE_GAME_ROTATION_VECTOR` (as in capture). Initial view = yaw 0, horizon level, regardless of
     where the phone points when the viewer opens.
   - Drag: yaw/pitch offset on top of the gyro; pitch clamped to ±90°.
   - Pinch: vertical FOV roughly 30–100°.
   - Toggle gyro on/off. Respect display rotation (landscape is allowed).
   - Stop sensors and GL rendering when not visible (lifecycle).
3. GPano: honour `CroppedAreaLeftPixels/TopPixels/ImageWidthPixels/ImageHeightPixels` and
   `FullPanoWidthPixels/HeightPixels`; outside the cropped area render a neutral colour. Missing XMP: assume a
   full sphere if the aspect is 2:1, otherwise show an error ("not a 360° photo").
   `PoseHeadingDegrees` etc. may be ignored.
4. Entry points:
   - Detail: "View" button when the panorama exists (and tap on the panorama thumbnail).
   - Home: "Open 360 photo" via the Photo Picker (`PickVisualMedia`, images only, no permission).
   - `ACTION_VIEW` intent filter for `image/jpeg` from other apps (content URI, read via `ContentResolver`).
   - Picker URIs: the read grant is tied to the receiving activity, so pass the URI to `ViewerActivity` with
     `FLAG_GRANT_READ_URI_PERMISSION`; after process death the activity re-reads it from its intent.

## Small UI gap
Detail: "Delete source images" (keep manifest + panorama, drop frame images; the session can then no longer be
stitched again, so hide "Stitch again" and say so in the confirmation dialog).

## Tests
- JVM unit test for the ray → lon/lat → uv math (the same formulas as the shader, kept in Kotlin): yaw 0 is
  the centre column, no mirroring, zenith/nadir, wrap at ±180°.
- XMP/`CroppedArea*` parsing on our own output and on a hand-cropped GPano file (add to `testdata/`).
- Emulator (GLES3 works there): opens our panorama, a third-party one, a non-panorama JPEG; drag/pinch work;
  no seam at the wrap; no crash on rotation/process death.

## Device checks on the A16 (together with the viewer test)
Do not run `adb` on the A16 without explicit permission; hand the user the commands. Open items left from
phase 1:
- REALTIME timestamps and exposure-time interpolation.
- AE/AWB/AF lock holding on the still capture.
- Real FOV vs `intrinsicsFromSensor`; actual ImageCapture resolution/crop.
- No-nadir capture end-to-end.
- App-kill survival during capture and stitching.
- ZIP export on the device.
Viewer on the A16: texture size, load time and memory of a 6144×3072 panorama, gyro latency, frame rate.

## Out of scope
Parallax-aware stitching and capture hints (planned for phase 4; see the A16 session in `captures/`), compass,
icon and branding, editing/cropping in the viewer, video.

## Working style
Plan first and show the plan to the user (in Czech) before writing code. Work in small, verifiable steps,
commit only when asked. Use cheaper subagents for well-specified, isolated tasks if available.
