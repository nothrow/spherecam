# Session manifest, schemaVersion 1

## Purpose
`manifest.json` is the only contract between capture (Android) and the stitcher. All Android specifics
(sensor orientation, phone rotation, timestamp alignment) are resolved in capture before it is written.

## Folder layout
```
<sessionId>/
  manifest.json
  img_000.jpg, img_001.jpg, ...   (3-digit frame index)
  pano.jpg                        (stitched equirect, after a successful stitch)
  pano.json                       (stitcher report of the last stitch, also on failure)
```
ZIP export contains the manifest, the images and `pano.json` under a top-level `<sessionId>/` directory.

## Conventions
All rotations are active, Hamilton quaternions, right-handed.

- Device frame (Android): x right, y toward top of screen, z out of the screen. Natural orientation = portrait.
- Camera frame (OpenCV): x right, y down, z forward. The saved image is upright portrait (capture rotates the
  pixels), so R_device_from_camera = diag(1, -1, -1) regardless of `sensorOrientation`.
- Game world (TYPE_GAME_ROTATION_VECTOR): Z up against gravity, X/Y arbitrary horizontal. Internal to capture.
- World (manifest): Z up, X = horizontal projection of the first shot's camera forward, Y = left of it.
- `rotation` = R_world_from_camera, 3x3 row-major (9 numbers). `quaternion` = same rotation as (w, x, y, z), w >= 0.
- Yaw = atan2(d.y, d.x) (counter-clockwise seen from above, positive toward +Y/left), pitch = asin(d.z).
- Projecting a world direction d into image pixels: `c = R^T d`, `u = fx*c.x/c.z + cx`, `v = fy*c.y/c.z + cy`
  (valid only for `c.z > 0`). Origin is the top-left corner of the image, u right, v down.

## Image orientation
Pixels are physically upright, EXIF orientation = 1. `imageWidth`/`imageHeight` and all intrinsics refer exactly
to the saved pixels. `sensorOrientation` is informational only; do not apply it again.

## Timestamps
`exposureTimestampNs` and `captureTimestampNs` are both in the `SystemClock.elapsedRealtimeNanos` time base.
`exposureTimestampNs` is the sensor exposure start (`SENSOR_TIMESTAMP`) converted to that base; `captureTimestampNs`
is the moment the capture was triggered (typically 100-300 ms later).

`rotationSource` states how `rotation` was obtained:
- `interpolated`: slerp between the two sensor samples surrounding `exposureTimestampNs`.
- `nearest`: exposure time lies outside the sample range; the closest sample was used.
- `trigger-time`: fallback, the rotation at `captureTimestampNs` was used (set when `camera.timestampSource` is
  `UNKNOWN` or exposure time could not be aligned). Expect up to a few degrees of error while moving.

`camera.timestampSource` is `SENSOR_INFO_TIMESTAMP_SOURCE`: `REALTIME` (exposure time directly comparable with
sensor timestamps) or `UNKNOWN` (not comparable, fallback applies).

## Fields
| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | int | Always 1 here |
| `sessionId` | string | UUID, equals the folder name |
| `createdAt` | string | ISO-8601 UTC |
| `status` | string | See lifecycle |
| `app.versionName`, `app.versionCode` | string, int | Capturing app version |
| `device.manufacturer`, `device.model`, `device.sdkInt` | string, string, int | Build info |
| `settings.nadir` | bool | Nadir targets were requested |
| `settings.targetOverlap` | number | Overlap fraction used to lay out targets (0.30 = 30 %); target counts and row pitches follow from it and the camera FOV |
| `conventions.*` | strings | Human-readable restatement of the conventions above |
| `camera.cameraId` | string | Camera2 id |
| `camera.imageWidth`, `imageHeight` | int | Saved JPEG size in pixels |
| `camera.fx`, `fy`, `cx`, `cy` | number | Pinhole intrinsics in saved pixels |
| `camera.sensorOrientation` | int | `SENSOR_ORIENTATION`, informational |
| `camera.focalLengthMm` | number | Physical focal length |
| `camera.sensorPhysicalSizeMm` | [w, h] | `SENSOR_INFO_PHYSICAL_SIZE` |
| `camera.pixelArraySize` | [w, h] | `SENSOR_INFO_PIXEL_ARRAY_SIZE`, sensor orientation |
| `camera.activeArraySize` | [w, h] | `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, sensor orientation |
| `camera.lensIntrinsicCalibration` | [fx, fy, cx, cy, s] or null | `LENS_INTRINSIC_CALIBRATION` if the device reports it (sensor pixels, not the saved image) |
| `camera.intrinsicsSource` | string | Provenance of fx..cy; `sensor-physical-size` = computed from focal length, physical size and crop |
| `camera.timestampSource` | string | `REALTIME` or `UNKNOWN` |
| `camera.aeLocked`, `awbLocked`, `afLocked` | bool | Lock was requested after the first shot and verified |
| `frames[].index` | int | 0-based, capture order |
| `frames[].file` | string | `img_NNN.jpg` |
| `frames[].targetId` | string | Guidance target id |
| `frames[].targetYawDeg`, `targetPitchDeg` | number | Intended direction (world frame) |
| `frames[].rotation` | 9 numbers | R_world_from_camera, row-major |
| `frames[].quaternion` | 4 numbers | Same rotation, (w, x, y, z) |
| `frames[].exposureTimestampNs` | int | See timestamps |
| `frames[].captureTimestampNs` | int | See timestamps |
| `frames[].rotationSource` | string | See timestamps |
| `frames[].angularErrorDeg` | number | Angle between actual optical axis and the target at capture |

Readers must ignore unknown fields. `lensIntrinsicCalibration` is always present (null when unavailable).

## Status lifecycle
`capturing` (session open, frames being added) -> `captured` (capture finished) -> `stitched` | `failed`
(the last two are written by the stitcher phase). A session left in `capturing` after an app kill is valid and
contains every frame whose manifest write completed.

## Write guarantees
Each manifest write goes to `manifest.json.tmp`, is fsync'd, then renamed over `manifest.json` (atomic move).
An image is written and fsync'd before the manifest that references it. Hence `manifest.json` never references a
missing or partial image; a leftover `manifest.json.tmp` or an unreferenced `img_*.jpg` may exist after a crash and
must be ignored.

## Example
```json
{
  "schemaVersion": 1,
  "sessionId": "3f0c2a52-6a53-4c43-9d7e-1f6f4a1b7c10",
  "createdAt": "2026-01-01T12:00:00Z",
  "status": "captured",
  "app": { "versionName": "0.1", "versionCode": 1 },
  "device": { "manufacturer": "samsung", "model": "SM-A166B", "sdkInt": 34 },
  "settings": { "nadir": true, "targetOverlap": 0.3 },
  "conventions": {
    "camera": "opencv",
    "world": "z-up, x=first-shot-heading",
    "rotation": "R_world_from_camera, row-major"
  },
  "camera": {
    "cameraId": "0",
    "imageWidth": 1536,
    "imageHeight": 2048,
    "fx": 1500.0,
    "fy": 1500.0,
    "cx": 768.0,
    "cy": 1024.0,
    "sensorOrientation": 90,
    "focalLengthMm": 4.0,
    "sensorPhysicalSizeMm": [6.4, 4.8],
    "pixelArraySize": [4608, 3456],
    "activeArraySize": [4608, 3456],
    "lensIntrinsicCalibration": null,
    "intrinsicsSource": "sensor-physical-size",
    "timestampSource": "REALTIME",
    "aeLocked": true,
    "awbLocked": true,
    "afLocked": true
  },
  "frames": [
    {
      "index": 0,
      "file": "img_000.jpg",
      "targetId": "e_c0",
      "targetYawDeg": 0.0,
      "targetPitchDeg": 0.0,
      "rotation": [0.0, 0.0, 1.0, -1.0, 0.0, 0.0, 0.0, -1.0, 0.0],
      "quaternion": [0.5, -0.5, 0.5, -0.5],
      "exposureTimestampNs": 123456789000,
      "captureTimestampNs": 123556789000,
      "rotationSource": "interpolated",
      "angularErrorDeg": 0.8
    }
  ]
}
```
