# Panorama output and stitch progress: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A stitched panorama carries GPano XMP, is saved to Photos and can be shared from the session detail. The
stitch screen shows overall progress and an estimate of the remaining time.

**Architecture:**
- XMP is written by the C++ core, so desktop output has it too.
- The Photos save, sharing and the time estimate live in `:app`.
- The estimate is a pure function, `Eta.kt`, with a JVM test.

**Tech Stack:** OpenCV 5.0.0 `imwriteWithMetadata`, Android MediaStore, FileProvider, `org.json`, JUnit 4.

Spec: `docs/superpowers/specs/2026-11-04-pano-output-progress-design.md`.

## Global Constraints
- minSdk 29, no new permissions.
- Code, comments and commits in English. Comments only for non-obvious things.
- Existing tests must keep passing:
  - `ctest` in `stitcher/build`;
  - `./gradlew :capture:testDebugUnitTest`.

---

### Task 1: GPano XMP in stitcher output

**Files:**
- Modify: `stitcher/core/src/stitch.cpp`
- Modify: `stitcher/tests/test_convention.cpp`, which already stitches a synthetic session to a file

**Interfaces:**
- `std::string gpanoXmp(int W, int H)` in `stitch.cpp`, file-local.
- Output files written by `stitch()` contain the XMP.

- [ ] Add an assertion to `test_convention`:
  - read the output with `cv::imreadWithMetadata`;
  - require an `IMAGE_METADATA_XMP` chunk;
  - require that it contains `equirectangular` and `FullPanoWidthPixels="<W>"`.
- [ ] Run it and confirm the assertion fails.
- [ ] Implement:
  - build the XMP from the `http://ns.adobe.com/xap/1.0/\0` header plus an `x:xmpmeta` packet with the GPano
    attributes from the spec;
  - write the output with `cv::imwriteWithMetadata(outPath, out, {IMAGE_METADATA_XMP}, {xmpMat}, params)`.
- [ ] Run `ctest`; all tests pass.
- [ ] Commit.

### Task 2: Remaining-time estimate

**Files:**
- Create: `android/app/src/main/kotlin/cz/nothrow/spherecam/Eta.kt`
- Create: `android/app/src/test/kotlin/cz/nothrow/spherecam/EtaTest.kt`
- Modify: `android/app/build.gradle.kts` (add `testImplementation(libs.junit)`)

**Interfaces (produced):**
- `val DEFAULT_PHASE_WEIGHTS: DoubleArray`: desktop 36-frame seconds per frame, in the order of `Phase`.
  - Measured 2026-11-04 on the desktop: load 0.0005, features 4.92, matching 5.68, ba 3.91, warp 1.65,
    exposure 0.018, seams 0.61, blend 6.05, encode 0.39 (seconds over 36 frames).
- `fun overallFraction(weights: DoubleArray, phase: Int, fraction: Double): Double`
- `fun remainingSeconds(elapsedS: Double, p: Double): Double?`
  - Returns null while `elapsedS < 5` or `p < 0.05`.
- `fun formatRemaining(s: Double): String`
  - Rounds to 10 s below 2 min; above that, to whole minutes.
  - Returns `"40 s"` or `"3 min"`.

- [ ] Write tests:
  - the overall fraction at a phase start equals the weight share of the finished phases;
  - it is 1 at the end of the last phase;
  - `remainingSeconds` returns null early and `elapsed × (1 − p) / p` otherwise;
  - the formatting boundaries hold.
- [ ] Implement and run `./gradlew :app:testDebugUnitTest`.
- [ ] Commit.

### Task 3: Save to Photos, share, progress UI

**Files:**
- Create: `android/app/src/main/kotlin/cz/nothrow/spherecam/Gallery.kt`
  - `fun Context.saveToPhotos(pano: File)`: MediaStore insert, `IS_PENDING`, copy, clear pending; on failure delete
    the row and throw.
  - `fun Context.sharePano(pano: File)`.
  - `fun Context.phaseWeights(): DoubleArray` and `fun Context.rememberPhaseWeights(report: File, frames: Int)`
    (`SharedPreferences` + `org.json`).
- Modify:
  - `StitchScreen.kt`: overall bar, ETA line, save after success, save-failure message.
  - `DetailScreen.kt`: "Share panorama" button.
  - `res/xml/file_paths.xml`: `files-path sessions/`.
  - `strings.xml`.

- [ ] Implement.
- [ ] Build with `./gradlew :app:assembleDebug :app:assembleRelease`.
- [ ] Test on the emulator (never on the A16 without permission):
  - stitch `nadir-1`;
  - the bar advances and the ETA appears;
  - after success a photo exists in `Pictures/SphereCam` (`content query --uri content://media/external/images/media`);
  - the Share button opens the chooser.
- [ ] Commit.
