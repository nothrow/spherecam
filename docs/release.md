# Building a Play release

One-time setup on a fresh checkout:
- The NDK must be installed: `android sdk install ndk/30.0.16248370`.
- The SDK CMake must be installed: `android sdk install cmake/4.1.2`.
- The static arm64 OpenCV that `:stitcher` links against must be built. It lives in the git-ignored
  `stitcher/.deps/`:
  ```
  tools/build_opencv.sh stitcher/.deps/opencv-android-arm64 \
    -DCMAKE_TOOLCHAIN_FILE=$HOME/Android/Sdk/ndk/30.0.16248370/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
  ```
  Without it the Gradle build stops with "OpenCV for arm64 missing".
- Signing: `~/.ssh/signing.jks` plus `spherecam.keyAlias`, `spherecam.storePassword` and `spherecam.keyPassword`
  in `~/.gradle/gradle.properties`.

For each release:
1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`.
2. Build the bundle: `cd android && ./gradlew :app:bundleRelease`.
3. Upload `android/app/build/outputs/bundle/release/app-release.aab` in Play Console. Native debug symbols are
   inside the bundle.
4. Release notes and listing texts are in `docs/store-listing.md`. Update the privacy policy
   (`docs/privacy.html`) when data handling changes.
