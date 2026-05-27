cmake_minimum_required(VERSION 3.22.1)
project("app")

find_package(oboe REQUIRED CONFIG)

add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    AudioEngine.cpp
    SimpleResampler.cpp
    StreamingOnnxEnhancer.cpp
    speexdsp/resample.c)

include_directories(onnxruntime_headers)
include_directories(speexdsp)
include_directories(speexdsp/speex)


add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/onnx_libs/${ANDROID_ABI}/libonnxruntime.so)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O3 -ffast-math)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    oboe::oboe
    onnxruntime)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    OUTSIDE_SPEEX=1
    RANDOM_PREFIX=simple
    FLOATING_POINT=1
    EXPORT=)
