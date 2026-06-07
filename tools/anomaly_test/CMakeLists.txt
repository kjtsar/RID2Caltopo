cmake_minimum_required(VERSION 3.14)
project(anomaly_test C)

set(CMAKE_C_STANDARD 11)
option(ANOMALY_DEBUG_TIMING "Enable anomaly detector stage timing instrumentation" OFF)

add_executable(anomaly_test
    test_anomaly.c
    ../../app/src/main/cpp/anomaly_detector.c
    ../../app/src/main/cpp/anomaly_detector_annotation.c
    ../../app/src/main/cpp/anomaly_analysis.c
    ../../app/src/main/cpp/anomaly_buffer.c
    ../../app/src/main/cpp/anomaly_roi_tracks.c
    ../../app/src/main/cpp/anomaly_roi_state.c
    ../../app/src/main/cpp/anomaly_saliency_tracks.c
    ../../app/src/main/cpp/anomaly_scratch.c
    ../../app/src/main/cpp/anomaly_color_detector.c
    ../../app/src/main/cpp/anomaly_debug_helpers.c
    ../../app/src/main/cpp/anomaly_frame_history.c
    ../../app/src/main/cpp/anomaly_grid_region.c
    ../../app/src/main/cpp/anomaly_linear_solve.c
    ../../app/src/main/cpp/anomaly_appearance_candidates.c
    ../../app/src/main/cpp/anomaly_motion_estimator.c
    ../../app/src/main/cpp/anomaly_registration_cache.c
    ../../app/src/main/cpp/anomaly_registration_image.c
    ../../app/src/main/cpp/anomaly_result_builder.c
    ../../app/src/main/cpp/anomaly_scan_planner.c
    ../../app/src/main/cpp/anomaly_target_observations.c
    ../../app/src/main/cpp/anomaly_target_revisit.c
    ../../app/src/main/cpp/anomaly_target_tracks.c
    ../../app/src/main/cpp/anomaly_thermal_detector.c
    ../../app/src/main/cpp/anomaly_thermal_state.c
)

target_include_directories(anomaly_test PRIVATE
    ../../app/src/main/cpp
)
target_compile_definitions(anomaly_test PRIVATE ANOMALY_DEBUG_TIMING=$<BOOL:${ANOMALY_DEBUG_TIMING}>)

target_link_libraries(anomaly_test m)

add_executable(anomaly_video_test
    anomaly_video_test.c
    ../../app/src/main/cpp/anomaly_detector.c
    ../../app/src/main/cpp/anomaly_detector_annotation.c
    ../../app/src/main/cpp/anomaly_analysis.c
    ../../app/src/main/cpp/anomaly_buffer.c
    ../../app/src/main/cpp/anomaly_roi_tracks.c
    ../../app/src/main/cpp/anomaly_roi_state.c
    ../../app/src/main/cpp/anomaly_saliency_tracks.c
    ../../app/src/main/cpp/anomaly_scratch.c
    ../../app/src/main/cpp/anomaly_color_detector.c
    ../../app/src/main/cpp/anomaly_debug_helpers.c
    ../../app/src/main/cpp/anomaly_frame_history.c
    ../../app/src/main/cpp/anomaly_grid_region.c
    ../../app/src/main/cpp/anomaly_linear_solve.c
    ../../app/src/main/cpp/anomaly_appearance_candidates.c
    ../../app/src/main/cpp/anomaly_motion_estimator.c
    ../../app/src/main/cpp/anomaly_registration_cache.c
    ../../app/src/main/cpp/anomaly_registration_image.c
    ../../app/src/main/cpp/anomaly_result_builder.c
    ../../app/src/main/cpp/anomaly_scan_planner.c
    ../../app/src/main/cpp/anomaly_target_observations.c
    ../../app/src/main/cpp/anomaly_target_revisit.c
    ../../app/src/main/cpp/anomaly_target_tracks.c
    ../../app/src/main/cpp/anomaly_thermal_detector.c
    ../../app/src/main/cpp/anomaly_thermal_state.c
)
target_include_directories(anomaly_video_test PRIVATE
    ../../app/src/main/cpp
)
target_compile_definitions(anomaly_video_test PRIVATE ANOMALY_DEBUG_TIMING=$<BOOL:${ANOMALY_DEBUG_TIMING}>)
target_link_libraries(anomaly_video_test m)

enable_testing()
add_test(NAME anomaly_test COMMAND anomaly_test)
