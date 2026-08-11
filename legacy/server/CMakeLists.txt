cmake_minimum_required(VERSION 3.15)
project(streamify_server CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

find_package(Threads REQUIRED)

find_library(DROGON_LIB NAMES drogon libdrogon PATHS /usr/lib/aarch64-linux-gnu /usr/lib /usr/local/lib)
find_library(TRANTOR_LIB NAMES trantor libtrantor PATHS /usr/lib/aarch64-linux-gnu /usr/lib /usr/local/lib)
find_library(SQLITE3_LIB NAMES sqlite3 libsqlite3 PATHS /usr/lib/aarch64-linux-gnu /usr/lib /usr/local/lib)
find_library(JSONCPP_LIB NAMES jsoncpp libjsoncpp PATHS /usr/lib/aarch64-linux-gnu /usr/lib /usr/local/lib)

include_directories(
    /usr/include
    /usr/include/jsoncpp
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/services
    ${CMAKE_CURRENT_SOURCE_DIR}/controllers
    ${CMAKE_CURRENT_SOURCE_DIR}/../music-procengine/server
)

set(SOURCES
    main.cc
    services/StreamifyDB.cc
    controllers/CatalogController.cc
    controllers/ProxyController.cc
    controllers/AuthController.cc
    controllers/DownloadController.cc
)

add_executable(streamify_server ${SOURCES})

target_link_libraries(streamify_server PRIVATE
    ${DROGON_LIB}
    ${TRANTOR_LIB}
    ${SQLITE3_LIB}
    ${JSONCPP_LIB}
    jsoncpp
    Threads::Threads
)



