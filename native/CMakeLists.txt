cmake_minimum_required(VERSION 3.22.1)
project("streamify_core")

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
)
