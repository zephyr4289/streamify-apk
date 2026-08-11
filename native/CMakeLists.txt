cmake_minimum_required(VERSION 3.22.1)
project("streamify_core")

add_library(streamify_core SHARED
    jni/jni_bridge.cc
)

target_link_libraries(streamify_core
    android
    log
)
