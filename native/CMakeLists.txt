cmake_minimum_required(VERSION 3.22.1)
project("streamify_core")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
endif()

# Main Shared Library for Android JNI
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

if (ANDROID)
    target_link_libraries(streamify_core
        android
        log
    )
endif()

# --- STANDALONE TEST TARGETS (Host C++20 with ASan / UBSan) ---
option(STREAMIFY_BUILD_TESTS "Build native test suites" OFF)
if (STREAMIFY_BUILD_TESTS)
    add_executable(dsp_test_suite
        tests/test_dsp.cc
        dsp/SoftKneeLimiter.cc
        dsp/LufsNormalizer.cc
        dsp/kissfft/kiss_fft.c
        dsp/kissfft/kiss_fftr.c
    )
    target_include_directories(dsp_test_suite PRIVATE dsp dsp/kissfft .)

    add_executable(simd_physics_test_suite
        tests/test_simd_physics.cc
        engine/VectorStore.cc
        engine/AirDropPhysicsEngine.cc
    )
    target_include_directories(simd_physics_test_suite PRIVATE engine .)

    add_executable(storage_telemetry_test_suite
        tests/test_storage_telemetry.cc
        engine/StreamifyDB.cc
        engine/TelemetryEngine.cc
        engine/ChronosProfiler.cc
        engine/EventTracker.cc
        third_party/sqlite3/sqlite3.c
    )
    target_include_directories(storage_telemetry_test_suite PRIVATE engine third_party/sqlite3 .)
endif()

# --- LIBFUZZER TARGET (Nightly / Main PRs) ---
option(STREAMIFY_BUILD_FUZZERS "Build LibFuzzer harnesses" OFF)
if (STREAMIFY_BUILD_FUZZERS)
    add_executable(dsp_fuzzer_harness
        fuzz/dsp_fuzzer_harness.cc
        dsp/SoftKneeLimiter.cc
        dsp/LufsNormalizer.cc
        dsp/kissfft/kiss_fft.c
        dsp/kissfft/kiss_fftr.c
    )
    target_include_directories(dsp_fuzzer_harness PRIVATE dsp dsp/kissfft .)
    target_link_options(dsp_fuzzer_harness PRIVATE -fsanitize=fuzzer)
endif()
