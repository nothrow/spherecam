# Panorama output and stitch progress

## Goal
After a stitch on the phone, the panorama:
- lands in Photos as a 360° photo;
- can be shared straight from the session detail.

The stitch screen shows overall progress and the remaining time.

## 1. GPano XMP (stitcher core, C++)
- `stitch()` writes the output with `cv::imwriteWithMetadata` (OpenCV 5.0.0, `IMAGE_METADATA_XMP`) instead of
  `cv::imwrite`.
- The OpenCV JPEG encoder writes the XMP chunk verbatim as APP1, so the chunk must start with the namespace header
  `http://ns.adobe.com/xap/1.0/\0`.
- XMP (`GPano` namespace `http://ns.google.com/photos/1.0/panorama/`):
  - `ProjectionType=equirectangular`, `UsePanoramaViewer=True`;
  - `FullPanoWidthPixels=W`, `FullPanoHeightPixels=H`;
  - `CroppedAreaImageWidthPixels=W`, `CroppedAreaImageHeightPixels=H`;
  - `CroppedAreaLeftPixels=0`, `CroppedAreaTopPixels=0`.
- The output is always the full 2:1 sphere, so the cropped area equals the full pano.
- No `PoseHeadingDegrees`: there is no compass.
- Still no EXIF, so there is no orientation tag.
- Test: a stitcher test reads the output with `cv::imreadWithMetadata` and checks:
  - the XMP chunk is present;
  - it contains `GPano:ProjectionType` with value `equirectangular`;
  - it contains the correct `FullPanoWidthPixels`.

## 2. Save to Photos (app, Kotlin)
- Runs after a successful stitch and the rename to `pano.jpg`.
- Copies `pano.jpg` into `MediaStore.Images`:
  - `RELATIVE_PATH=Pictures/SphereCam`, `DISPLAY_NAME=PANO_yyyyMMdd_HHmmss.jpg`;
  - `IS_PENDING=1` while writing, then `0`.
- No permission is needed: on API 29+ an app may insert its own media.
- Every successful stitch adds a new photo; a re-stitch does not replace the old one.
- Failure (insert or write):
  - the pending row is deleted;
  - the session stays `stitched` and `pano.jpg` remains in the session;
  - the stitch screen shows "Saved in the app, but saving to Photos failed: <reason>" with a Back button.
  - There is no separate retry: the next stitch saves again.

## 3. Share from session detail (app)
- When `pano.jpg` exists, the detail shows a "Share panorama" button.
- It sends `ACTION_SEND` (`image/jpeg`) with a FileProvider URI and `FLAG_GRANT_READ_URI_PERMISSION`.
- `file_paths.xml` gets `<files-path name="sessions" path="sessions/"/>`.
- Only the URI of this one file is granted.

## 4. Progress and remaining time (app)
- One overall `LinearProgressIndicator`. Below it a single line, e.g. `Matching images (3/9) · about 40 s left`.
- Phase weights:
  - Seconds per frame for each phase, taken from `phaseSeconds` in `pano.json` of the last successful stitch on
    this device.
  - Divided by the frame count, stored in `SharedPreferences` as a JSON string, parsed with the built-in `org.json`.
  - First stitch on a device: built-in defaults, the desktop 36-frame measurement divided by 36.
  - Only the ratios matter, so desktop numbers are a fair first guess.
- Overall fraction `p = (sum of weights of finished phases + weight of current phase × its fraction) / sum of all
  weights`.
- Remaining time:
  - `elapsed × (1 − p) / p`;
  - shown only once elapsed ≥ 5 s and p ≥ 0.05;
  - rounded to 10 s, and to whole minutes above 2 min.
- The math is a pure Kotlin function in the app with a JVM unit test (`testImplementation(junit)` added to `:app`).

## Out of scope
- An in-app viewer (later phase).
- Deleting or replacing older versions in Photos.
- Sharing the source images.
