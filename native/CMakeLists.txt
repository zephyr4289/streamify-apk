cmake_minimum_required(VERSION 3.22.1)
project("streamify_core")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -march=armv8-a+fp+simd")
endif()

add_library(streamify_core SHARED
    jni/jni_bridge.cc
    engine/StreamifyDB.cc
    engine/VectorStore.cc
    engine/RecommendEngine.cc
    engine/EventTracker.cc
    ingest/AudioPipeline.cc
    dsp/kissfft/kiss_fft.c
    dsp/kissfft/kiss_fftr.c
    third_party/sqlite3/sqlite3.c
)

target_include_directories(streamify_core PRIVATE 
    dsp/kissfft
    third_party/onnxruntime/include
)

target_link_libraries(streamify_core
    android
    log
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/onnxruntime/lib/arm64-v8a/libonnxruntime.so
)
