cmake_minimum_required(VERSION 3.22.1)
project(innolive_privacy LANGUAGES CXX)
add_library(innolive_privacy SHARED privacy_pixels.cpp)
target_compile_features(innolive_privacy PRIVATE cxx_std_17)
# The camera conversion must remain optimized in Debug performance/device validation too.
target_compile_options(innolive_privacy PRIVATE -O3 -Wall -Wextra)
target_link_options(innolive_privacy PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(innolive_privacy PRIVATE jnigraphics)
