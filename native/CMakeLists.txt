cmake_minimum_required(VERSION 3.22.1)
project("streamify_core")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
endif()

add_library(streamify_core SHARED
    jni/jni_bridge.cc
    engine/StreamifyDB.cc
    engine/VectorStore.cc
    engine/RecommendEngine.cc
    engine/EventTracker.cc
    engine/TaskOrchestrator.cc
    engine/TelemetryEngine.cc
    engine/ChronosProfiler.cc
    engine/PtpEngine.cc
    engine/AirDropPhysicsEngine.cc
    ingest/AudioPipeline.cc
    dsp/SoftKneeLimiter.cc
    dsp/LufsNormalizer.cc
    dsp/kissfft/kiss_fft.c
    dsp/kissfft/kiss_fftr.c
    third_party/sqlite3/sqlite3.c
)

target_compile_options(streamify_core PRIVATE
    $<$<CONFIG:Release>:-O3 -ffast-math -flto -fvisibility=hidden -DNDEBUG>
    -Wall -Wextra
)

target_link_options(streamify_core PRIVATE
    $<$<CONFIG:Release>:-flto -Wl,--gc-sections>
)

target_include_directories(streamify_core PRIVATE 
    dsp/kissfft
)

target_link_libraries(streamify_core
    android
    log
)
