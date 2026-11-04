#!/usr/bin/env bash
# Minimal static OpenCV for the stitcher. The Android build (later) must use the same version and module list.
# Usage: tools/build_opencv.sh [prefix]   (default: stitcher/.deps/opencv)
set -euo pipefail

VERSION=5.0.0
# OpenCV 5 renamed features2d -> features and moved the geometry part of calib3d -> geometry.
MODULES=core,imgproc,imgcodecs,features,flann,geometry,stitching

root=$(cd "$(dirname "$0")/.." && pwd)
deps=$root/stitcher/.deps
prefix=${1:-$deps/opencv}
src=$deps/opencv-$VERSION-src
build=$deps/opencv-$VERSION-build

[ -d "$src" ] || git clone --depth 1 --branch "$VERSION" https://github.com/opencv/opencv.git "$src"

# IPP and OpenCL are off so desktop numerics stay close to the arm64 build.
cmake -S "$src" -B "$build" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$prefix" \
  -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_LIST="$MODULES" \
  -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF \
  -DBUILD_JAVA=OFF -DBUILD_opencv_python3=OFF -DBUILD_opencv_apps=OFF \
  -DBUILD_ZLIB=ON -DBUILD_JPEG=ON -DBUILD_PNG=ON -DWITH_SPNG=OFF \
  -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENJPEG=OFF -DWITH_JASPER=OFF -DWITH_OPENEXR=OFF \
  -DWITH_AVIF=OFF -DWITH_JPEGXL=OFF -DWITH_IMGCODEC_GIF=OFF -DWITH_IMGCODEC_HDR=OFF \
  -DWITH_IMGCODEC_SUNRASTER=OFF -DWITH_IMGCODEC_PFM=OFF \
  -DWITH_IPP=OFF -DWITH_OPENCL=OFF -DWITH_ITT=OFF -DWITH_PROTOBUF=OFF -DWITH_FLATBUFFERS=OFF \
  -DWITH_EIGEN=OFF -DWITH_LAPACK=OFF -DWITH_FFMPEG=OFF -DWITH_GSTREAMER=OFF -DWITH_GTK=OFF \
  -DWITH_QT=OFF -DWITH_V4L=OFF -DWITH_OPENVINO=OFF -DWITH_VULKAN=OFF -DWITH_ONNXRUNTIME=OFF \
  -DWITH_CUDA=OFF -DWITH_TBB=OFF -DWITH_OPENMP=OFF -DWITH_PTHREADS_PF=ON \
  -DOPENCV_GENERATE_PKGCONFIG=OFF
cmake --build "$build" -j"$(nproc)"
cmake --install "$build"
echo "OpenCV $VERSION installed to $prefix"
