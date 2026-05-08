cmake_minimum_required(VERSION 3.14)
project(anomaly_test C)

set(CMAKE_C_STANDARD 11)
option(ANOMALY_DEBUG_TIMING "Enable anomaly detector stage timing instrumentation" OFF)

add_executable(anomaly_test
    test_anomaly.c
    ../../app/src/main/cpp/anomaly_analysis.c
)

target_include_directories(anomaly_test PRIVATE
    ../../app/src/main/cpp
)
target_compile_definitions(anomaly_test PRIVATE ANOMALY_DEBUG_TIMING=$<BOOL:${ANOMALY_DEBUG_TIMING}>)

target_link_libraries(anomaly_test m)

add_executable(anomaly_video_test
    anomaly_video_test.c
    ../../app/src/main/cpp/anomaly_analysis.c
)
target_include_directories(anomaly_video_test PRIVATE
    ../../app/src/main/cpp
)
target_compile_definitions(anomaly_video_test PRIVATE ANOMALY_DEBUG_TIMING=$<BOOL:${ANOMALY_DEBUG_TIMING}>)
target_link_libraries(anomaly_video_test m)

enable_testing()
add_test(NAME anomaly_test COMMAND anomaly_test)
