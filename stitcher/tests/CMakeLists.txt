foreach(t convention cv_convention robust fallback nonadir emulator parallax)
  add_executable(test_${t} test_${t}.cpp)
  target_link_libraries(test_${t} PRIVATE spherestitch)
  add_test(NAME ${t} COMMAND test_${t})
endforeach()
target_compile_definitions(test_emulator PRIVATE TESTDATA="${CMAKE_SOURCE_DIR}/../testdata")

add_executable(synth_session synth_session.cpp)
target_link_libraries(synth_session PRIVATE spherestitch)
