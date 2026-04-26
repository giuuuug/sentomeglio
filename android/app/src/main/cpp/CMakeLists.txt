cmake_minimum_required(VERSION 3.22.1)
project("app")

find_package(oboe REQUIRED CONFIG)

add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    AudioEngine.cpp
    SimpleResampler.cpp
    StreamingOnnxEnhancer.cpp)

# Le intestazioni ONNX Runtime le abbiamo scaricate manualmente
include_directories(onnxruntime_headers)

# Definiamo la libreria onnxruntime come precompilata
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/onnx_libs/${ANDROID_ABI}/libonnxruntime.so)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O3 -ffast-math)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    oboe::oboe
    onnxruntime)