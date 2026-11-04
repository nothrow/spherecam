cmake_minimum_required(VERSION 3.22)
project(spherestitch_jni CXX)

set(root "${CMAKE_CURRENT_SOURCE_DIR}/../../../../..")
set(OpenCV_DIR "${root}/stitcher/.deps/opencv-android-arm64/sdk/native/jni")
if(NOT EXISTS "${OpenCV_DIR}/OpenCVConfig.cmake")
  message(FATAL_ERROR "OpenCV for arm64 missing, build it first:\n"
    "  tools/build_opencv.sh stitcher/.deps/opencv-android-arm64 "
    "-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29")
endif()
set(SPHERESTITCH_TOOLS OFF)
add_subdirectory("${root}/stitcher" spherestitch)

add_library(spherestitch_jni SHARED jni.cpp)
target_link_libraries(spherestitch_jni PRIVATE spherestitch log)
# Static OpenCV would otherwise be exported whole; hiding it lets the linker drop the unused parts.
target_link_options(spherestitch_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
