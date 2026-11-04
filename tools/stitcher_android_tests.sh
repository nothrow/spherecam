#!/usr/bin/env bash
# Cross-builds the stitcher tests for arm64-v8a and runs them on a device over adb.
# Needs tools/build_opencv.sh run for the Android prefix first (see its header).
# Usage: tools/stitcher_android_tests.sh [adb serial]
# Use a real arm64 phone. The x86_64 emulator runs arm64 binaries through ndk_translation, which executes FCVTL2
# wrongly (converts the low half), so OpenCV's float->double convertTo and everything built on it (BA) fails there.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
ndk=${ANDROID_NDK:-$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)}
adb=${ADB:-/mnt/d/_android/platform-tools/adb.exe}
serial=(${1:+-s "$1"})
build=$root/stitcher/build-android-arm64
dev=/data/local/tmp/spherestitch

cmake -S "$root/stitcher" -B "$build" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release \
  -DOpenCV_DIR="$root/stitcher/.deps/opencv-android-arm64/sdk/native/jni"
cmake --build "$build" -j"$(nproc)"

tests=(convention cv_convention robust fallback nonadir emulator)
"$adb" "${serial[@]}" shell "rm -rf $dev && mkdir -p $dev/tmp $dev/testdata"
# adb.exe needs Windows paths for local files.
win() { if [[ "$adb" == *.exe ]]; then wslpath -w "$1"; else echo "$1"; fi; }
for t in "${tests[@]}"; do "$adb" "${serial[@]}" push "$(win "$build/tests/test_$t")" $dev/ >/dev/null; done
"$adb" "${serial[@]}" push "$(win "$root/testdata/emulator")" $dev/testdata/ >/dev/null
"$adb" "${serial[@]}" shell getprop ro.product.cpu.abi

failed=0
for t in "${tests[@]}"; do
  arg=$([ "$t" = emulator ] && echo "$dev/testdata" || true)
  echo "== $t"
  if ! "$adb" "${serial[@]}" shell "cd $dev && chmod +x test_$t && TMPDIR=$dev/tmp ./test_$t $arg; echo exit=\$?" | tee /dev/stderr | grep -q "exit=0"; then
    failed=1
  fi
done
exit $failed
