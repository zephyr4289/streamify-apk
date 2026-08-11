cmake_minimum_required(VERSION 3.15)
project(audio_recommendation_server CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Optimize for CPU
include(CheckCXXCompilerFlag)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|i386|i686")
    check_cxx_compiler_flag("-mavx2" COMPILER_SUPPORTS_AVX2)
    if(COMPILER_SUPPORTS_AVX2)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -mavx2")
    else()
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
    endif()
else()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
endif()

# Find Dependencies
find_package(Threads REQUIRED)

find_package(PkgConfig QUIET)
if(PkgConfig_FOUND)
    pkg_check_modules(SQLITE3 sqlite3)
    pkg_check_modules(DROGON drogon)
endif()

find_package(Drogon QUIET)
if(NOT Drogon_FOUND AND NOT DROGON_FOUND)
    find_library(DROGON_LIB drogon)
    find_path(DROGON_INCLUDE_DIR drogon/drogon.h)
    if(DROGON_LIB)
        add_library(Drogon::Drogon UNKNOWN IMPORTED)
        set_target_properties(Drogon::Drogon PROPERTIES
            IMPORTED_LOCATION "${DROGON_LIB}"
        )
        if(DROGON_INCLUDE_DIR)
            set_target_properties(Drogon::Drogon PROPERTIES
                INTERFACE_INCLUDE_DIRECTORIES "${DROGON_INCLUDE_DIR}"
            )
        endif()
        set(Drogon_FOUND TRUE)
    endif()
endif()

find_library(SQLITE3_LIB sqlite3)
find_library(FFTW3_LIB fftw3)
find_library(ONNXRUNTIME_LIB onnxruntime)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${SQLITE3_INCLUDE_DIRS}
)

set(SOURCES
    main.cc
    services/DatabaseService.cc
    src/services/VectorStore.cc
    controllers/EventController.cc
    controllers/RecommendController.cc
    src/ingest/AudioPipeline.cc
    src/ingest/DirectoryWatcher.cc
)

add_executable(audio_recommendation_server ${SOURCES})

target_link_libraries(audio_recommendation_server PRIVATE
    $<$<TARGET_EXISTS:Drogon::Drogon>:Drogon::Drogon>
    $<$<BOOL:${DROGON_LIBRARIES}>:${DROGON_LIBRARIES}>
    $<$<BOOL:${DROGON_LIB}>:${DROGON_LIB}>
    $<$<BOOL:${SQLITE3_LIBRARIES}>:${SQLITE3_LIBRARIES}>
    $<$<BOOL:${SQLITE3_LIB}>:${SQLITE3_LIB}>
    Threads::Threads
    $<$<BOOL:${FFTW3_LIB}>:${FFTW3_LIB}>
    $<$<BOOL:${ONNXRUNTIME_LIB}>:${ONNXRUNTIME_LIB}>
)

target_include_directories(audio_recommendation_server PRIVATE
    ${SQLITE3_INCLUDE_DIRS}
)
