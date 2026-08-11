cmake_minimum_required(VERSION 3.22.1)
project(phoneshm_dsp VERSION 1.0.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable unsafe FP contractions (v1.4.1 C2)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffp-contract=off")

add_library(phoneshm_dsp SHARED
    dsp_core.cpp
    fft_backend.cpp
    fdd_core.cpp
    dsp_jni.cpp
)

target_include_directories(phoneshm_dsp PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/eigen-3.4.0)
find_library(log-lib log)
target_link_libraries(phoneshm_dsp ${log-lib})
