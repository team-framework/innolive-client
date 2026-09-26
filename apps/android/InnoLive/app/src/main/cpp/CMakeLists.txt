cmake_minimum_required(VERSION 3.22.1)
project(innolive_privacy LANGUAGES CXX)
add_library(innolive_privacy SHARED privacy_pixels.cpp privacy_gpu_model.cpp)
configure_file(vendor/litert/litert/build_common/build_config.h.in
    ${CMAKE_CURRENT_BINARY_DIR}/include/litert/build_common/build_config.h)
target_include_directories(innolive_privacy PRIVATE vendor/litert ${CMAKE_CURRENT_BINARY_DIR}/include)
target_compile_features(innolive_privacy PRIVATE cxx_std_17)
# The camera conversion must remain optimized in Debug performance/device validation too.
target_compile_options(innolive_privacy PRIVATE -O3 -Wall -Wextra -ffp-contract=off)
target_link_options(innolive_privacy PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(innolive_privacy PRIVATE jnigraphics neuralnetworks EGL GLESv3 dl)
