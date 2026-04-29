cmake_minimum_required(VERSION 3.22.1)
project(mediamtx_jni)

add_library(
    mediamtx_jni
    SHARED
    mediamtx_jni.c
)
add_library(
    ffmpeg_bridge
    SHARED
    ffmpeg_bridge.c
    anomaly_analysis.c
)

set(FFMPEG_LIB_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(FFMPEG_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/ffmpeg/include")

set(FFMPEG_LIBS_FOUND FALSE)
if (EXISTS "${FFMPEG_LIB_DIR}/libavformat.so" AND
    EXISTS "${FFMPEG_LIB_DIR}/libavcodec.so" AND
    EXISTS "${FFMPEG_LIB_DIR}/libavutil.so")
    set(FFMPEG_LIBS_FOUND TRUE)
endif()
set(FFMPEG_SWSCALE_FOUND FALSE)
if (EXISTS "${FFMPEG_LIB_DIR}/libswscale.so")
    set(FFMPEG_SWSCALE_FOUND TRUE)
endif()

find_library(
    log-lib
    log
)
find_library(
    android-lib
    android
)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

target_link_libraries(
    mediamtx_jni
    PRIVATE
    ${log-lib}
)
target_link_libraries(
    ffmpeg_bridge
    PRIVATE
    ${log-lib}
    ${android-lib}
)

if (FFMPEG_LIBS_FOUND)
    add_library(avformat SHARED IMPORTED)
    set_target_properties(avformat PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavformat.so")
    add_library(avcodec SHARED IMPORTED)
    set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavcodec.so")
    add_library(avutil SHARED IMPORTED)
    set_target_properties(avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavutil.so")
    if (FFMPEG_SWSCALE_FOUND)
        add_library(swscale SHARED IMPORTED)
        set_target_properties(swscale PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libswscale.so")
    endif()

    target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_FFMPEG=1)
    if (FFMPEG_SWSCALE_FOUND)
        target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_SWSCALE=1)
    else()
        target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_SWSCALE=0)
    endif()
    if (EXISTS "${FFMPEG_INCLUDE_DIR}")
        target_include_directories(ffmpeg_bridge PRIVATE "${FFMPEG_INCLUDE_DIR}")
    endif()
    target_link_libraries(
        ffmpeg_bridge
        PRIVATE
        avformat
        avcodec
        avutil
    )
    if (FFMPEG_SWSCALE_FOUND)
        target_link_libraries(ffmpeg_bridge PRIVATE swscale)
    endif()
else()
    target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_FFMPEG=0)
    target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_SWSCALE=0)
    message(WARNING "FFmpeg libs not found for ${ANDROID_ABI}. Using ffmpeg_bridge stub backend.")
endif()
