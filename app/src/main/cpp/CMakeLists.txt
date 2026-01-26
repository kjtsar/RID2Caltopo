cmake_minimum_required(VERSION 3.22.1)
project(mediamtx_jni)

add_library(
    mediamtx_jni
    SHARED
    mediamtx_jni.c
)

find_library(
    log-lib
    log
)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

target_link_libraries(
    mediamtx_jni
    PRIVATE
    ${log-lib}
)
