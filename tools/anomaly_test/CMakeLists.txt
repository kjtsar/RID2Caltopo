cmake_minimum_required(VERSION 3.14)
project(anomaly_test C)

set(CMAKE_C_STANDARD 11)

add_executable(anomaly_test
    test_anomaly.c
    ../../app/src/main/cpp/anomaly_analysis.c
)

target_include_directories(anomaly_test PRIVATE
    ../../app/src/main/cpp
)

target_link_libraries(anomaly_test m)

add_executable(anomaly_video_test
    anomaly_video_test.c
    ../../app/src/main/cpp/anomaly_analysis.c
)
target_include_directories(anomaly_video_test PRIVATE
    ../../app/src/main/cpp
)
target_link_libraries(anomaly_video_test m)

enable_testing()
add_test(NAME anomaly_test COMMAND anomaly_test)
