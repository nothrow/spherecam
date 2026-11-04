cmake_minimum_required(VERSION 3.22)
project(spherestitch CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# Built by tools/build_opencv.sh; override with -DOpenCV_DIR for another prefix (e.g. the Android one).
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.deps/opencv/lib/cmake/opencv5" CACHE PATH "")
find_package(OpenCV 5.0.0 EXACT REQUIRED COMPONENTS core imgproc imgcodecs features flann geometry stitching)

add_library(spherestitch STATIC
  core/src/session.cpp core/src/geometry.cpp core/src/register.cpp core/src/compose.cpp core/src/stitch.cpp)
target_include_directories(spherestitch PUBLIC core/include)
target_link_libraries(spherestitch PUBLIC ${OpenCV_LIBS})
target_compile_options(spherestitch PRIVATE -Wall -Wextra)

option(SPHERESTITCH_TOOLS "Build the CLI and tests" ON)
if(NOT SPHERESTITCH_TOOLS)
  return()
endif()

add_executable(spherestitch_cli cli/main.cpp)
set_target_properties(spherestitch_cli PROPERTIES OUTPUT_NAME spherestitch)
target_link_libraries(spherestitch_cli PRIVATE spherestitch)

enable_testing()
add_subdirectory(tests)
