cmake_minimum_required(VERSION 3.14)
project(anomaly_test C)

set(CMAKE_C_STANDARD 11)
option(ANOMALY_DEBUG_TIMING "Enable anomaly detector stage timing instrumentation" OFF)
find_package(Python3 REQUIRED COMPONENTS Interpreter)

add_executable(anomaly_test
    test_anomaly.c
    ../../app/src/main/cpp/anomaly_detector.c
    ../../app/src/main/cpp/anomaly_detector_annotation.c
    ../../app/src/main/cpp/anomaly_runtime_budget.c
    ../../app/src/main/cpp/anomaly_runtime_handoff.c
    ../../app/src/main/cpp/anomaly_runtime_pressure.c
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
    ../../app/src/main/cpp/anomaly_runtime_budget.c
    ../../app/src/main/cpp/anomaly_runtime_handoff.c
    ../../app/src/main/cpp/anomaly_runtime_pressure.c
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
add_test(
    NAME anomaly_video_app_defaults_local_playback_config
    COMMAND anomaly_video_test
        ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/test/resources/vidcap/PowerHouse1.mp4
        --app-defaults
        --app-display-output
        --no-video
        --time-end
        0.1
        -c
        ${CMAKE_CURRENT_BINARY_DIR}/app_defaults_config_probe.csv
        --summary-json
        ${CMAKE_CURRENT_BINARY_DIR}/app_defaults_config_probe.json
)
set_tests_properties(
    anomaly_video_app_defaults_local_playback_config
    PROPERTIES
        PASS_REGULAR_EXPRESSION "algorithm  = 2 \\(thermal \\)"
)
add_test(
    NAME anomaly_video_powerhouse1_app_local_opening_recall
    COMMAND
        ${Python3_EXECUTABLE}
        ${CMAKE_CURRENT_SOURCE_DIR}/check_app_local_powerhouse1_opening.py
        --binary
        $<TARGET_FILE:anomaly_video_test>
        --clip
        ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/test/resources/vidcap/PowerHouse1.mp4
        --review
        ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/test/resources/vidcap/PowerHouse1.review.json
        --out-csv
        ${CMAKE_CURRENT_BINARY_DIR}/powerhouse1_app_local_opening.csv
        --out-summary
        ${CMAKE_CURRENT_BINARY_DIR}/powerhouse1_app_local_opening_summary.json
)
