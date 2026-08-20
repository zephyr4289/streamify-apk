cmake_minimum_required(VERSION 3.22.1)
project(streamify_native_core LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization and Vectorization Flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -flto -fvisibility=hidden -Wall -Wextra")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -flto -fvisibility=hidden")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd+crypto")
endif()

include_directories(
    dsp
    dsp/kissfft
    engine
    ingest
    jni
    third_party/sqlite3
)

add_library(streamify_native_core SHARED
    engine/TaskOrchestrator.cc
    dsp/LufsNormalizer.cc
    dsp/SoftKneeLimiter.cc
    dsp/kissfft/kiss_fft.c
    dsp/kissfft/kiss_fftr.c
    engine/VectorStore.cc
    jni/jni_bridge.cc
    engine/StreamifyDB.cc
    engine/RecommendEngine.cc
    engine/EventTracker.cc
    engine/TelemetryEngine.cc
    engine/ChronosProfiler.cc
    engine/PtpEngine.cc
    engine/AirDropPhysicsEngine.cc
    ingest/AudioPipeline.cc
    dsp/LyricAligner.cc
    third_party/sqlite3/sqlite3.c
)

target_link_libraries(streamify_native_core
    android
    log
)

# --- STANDALONE TEST TARGETS (Host C++20 with ASan / UBSan) ---
option(STREAMIFY_BUILD_TESTS "Build native test suites" OFF)
if (STREAMIFY_BUILD_TESTS)
    add_executable(dsp_test_suite
        tests/test_dsp.cc
        dsp/SoftKneeLimiter.cc
        dsp/LufsNormalizer.cc
        dsp/LyricAligner.cc
        dsp/kissfft/kiss_fft.c
        dsp/kissfft/kiss_fftr.c
    )
    target_include_directories(dsp_test_suite PRIVATE dsp dsp/kissfft .)
    target_link_libraries(dsp_test_suite PRIVATE m pthread)

    add_executable(simd_physics_test_suite
        tests/test_simd_physics.cc
        engine/VectorStore.cc
        engine/AirDropPhysicsEngine.cc
    )
    target_include_directories(simd_physics_test_suite PRIVATE engine .)
    target_link_libraries(simd_physics_test_suite PRIVATE m pthread)

    add_executable(storage_telemetry_test_suite
        tests/test_storage_telemetry.cc
        engine/StreamifyDB.cc
        engine/TelemetryEngine.cc
        engine/ChronosProfiler.cc
        engine/EventTracker.cc
        third_party/sqlite3/sqlite3.c
    )
    target_include_directories(storage_telemetry_test_suite PRIVATE engine third_party/sqlite3 .)
    target_link_libraries(storage_telemetry_test_suite PRIVATE pthread dl)
endif()
